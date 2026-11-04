package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.model.NaruModelRegistration;
import net.thevpc.naru.api.model.NaruRegistrationOrigin;
import net.thevpc.naru.api.model.NaruRegistrationScope;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The user-global layer beneath the project's two registration files (design: "project + user
 * fallback"): a personal registration is usable from every project, a project may complete it
 * field by field, and neither write ever copies the other layer's values into its own file.
 *
 * <p>What the merge refuses is the point of these tests: when the two layers name different
 * endpoints under one id, the project's entry wins <b>entire</b>. A field-by-field overlay there
 * would hand the project's OpenAI model the personal gemini key — a hybrid that fails as
 * "the model rejected my key", three layers of files away from the cause.
 */
public class NaruRegistrationStoreLayeringTest {

    private NPath userHome;
    private NPath projectDir;
    private NaruRegistrationStore store;

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    @BeforeEach
    public void setUp() {
        long tag = System.nanoTime();
        userHome = NPath.ofTempFolder("naru-reg-layer-home-" + tag);
        projectDir = NPath.ofTempFolder("naru-reg-layer-project-" + tag);
        store = NaruRegistrationStore.of(userHome, projectDir);
    }

    private static NaruModelRegistration registration(String id, Object... pairs) {
        NaruModelRegistration.Builder b = NaruModelRegistration.builder(id);
        for (int i = 0; i < pairs.length; i += 2) {
            b.put((String) pairs[i], NElement.ofString(String.valueOf((String) pairs[i + 1])));
        }
        return b.build();
    }

    private NPath userPublic() {
        return userHome.resolve(".naru/config/registrations.tson");
    }

    private NPath userPrivate() {
        return userHome.resolve(".naru/local/config/registrations.tson");
    }

    private NPath projectPublic() {
        return projectDir.resolve(".naru/config/registrations.tson");
    }

    private NPath projectPrivate() {
        return projectDir.resolve(".naru/local/config/registrations.tson");
    }

    // ── the user layer is a fallback, not a copy ──────────────────────────────

    @Test
    public void aUserRegistrationIsUsableFromAnyProject() {
        store.put(registration("personal", "provider", "gemini",
                "url", "https://personal.test/v1", "apiKey", "sk-personal"), NaruRegistrationScope.USER);

        NaruModelRegistration r = store.get("personal").get();
        Assertions.assertEquals("gemini", r.provider().get());
        Assertions.assertEquals("sk-personal", r.apiKey().get());

        // machine-wide means exactly that: another project, same home, same registration
        NaruRegistrationStore otherProject =
                NaruRegistrationStore.of(userHome, NPath.ofTempFolder("naru-reg-layer-other-" + System.nanoTime()));
        Assertions.assertEquals("https://personal.test/v1",
                otherProject.get("personal").get().url().get());
    }

    @Test
    public void aProjectCanCompleteAPersonalRegistrationFieldByField() {
        store.put(registration("shared", "provider", "gemini", "url", "https://shared.test/v1"),
                NaruRegistrationScope.USER);
        // every entry names its own endpoint — provider is not inherited — which is exactly what
        // lets the merge tell a completion from a different registration wearing the same id
        store.put(registration("shared", "provider", "gemini", "apiKey", "sk-team-key"),
                NaruRegistrationScope.PROJECT);

        NaruModelRegistration r = store.get("shared").get();
        Assertions.assertEquals("gemini", r.provider().get());
        Assertions.assertEquals("https://shared.test/v1", r.url().get());
        Assertions.assertEquals("sk-team-key", r.apiKey().get());

        NaruRegistrationOrigin o = store.origin("shared").get();
        Assertions.assertEquals(NaruRegistrationScope.USER, o.scopeOf("url"));
        Assertions.assertEquals(NaruRegistrationScope.PROJECT, o.scopeOf("apiKey"));
        // the merged entry is the project's to edit: that is the scope a write or delete acts on
        Assertions.assertEquals(NaruRegistrationScope.PROJECT, o.scope());
    }

    @Test
    public void anOverrideOfAnotherEndpointReplacesTheWholeRegistration() {
        store.put(registration("fast", "provider", "gemini", "url", "https://gemini.test/v1",
                "apiKey", "sk-gemini-key", "model", "gemini-pro"), NaruRegistrationScope.USER);
        store.put(registration("fast", "provider", "openai", "url", "https://openai.test/v1",
                "model", "gpt-4o"), NaruRegistrationScope.PROJECT);

        NaruModelRegistration r = store.get("fast").get();
        Assertions.assertEquals("openai", r.provider().get());
        Assertions.assertEquals("gpt-4o", r.model().get());
        Assertions.assertEquals("https://openai.test/v1", r.url().get());
        // the personal gemini key is gone rather than being aimed at OpenAI's API
        Assertions.assertFalse(r.apiKey().isPresent(), "a key from another endpoint leaked into the merge");

        NaruRegistrationOrigin o = store.origin("fast").get();
        Assertions.assertEquals(NaruRegistrationScope.PROJECT, o.scope());
        Assertions.assertTrue(o.fieldsOf(NaruRegistrationScope.USER).isEmpty());
    }

    @Test
    public void anOverrideSpellingTheSameProviderDifferentlyStillOverlays() {
        store.put(registration("fast", "provider", "Gemini", "url", "https://gemini.test/v1"),
                NaruRegistrationScope.USER);
        store.put(registration("fast", "provider", "gemini", "temperature", "0.7"),
                NaruRegistrationScope.PROJECT);

        NaruModelRegistration r = store.get("fast").get();
        Assertions.assertEquals("https://gemini.test/v1", r.url().get());
        Assertions.assertEquals(0.7f, r.temperature().get(), "a quoted number parses as the float it names");
    }

    @Test
    public void aRewrittenWireProtocolIsAnotherEndpointToo() {
        store.put(registration("proxy", "protocol", "openai", "url", "https://openai-shape.test/v1",
                "models", "a,b"), NaruRegistrationScope.USER);
        store.put(registration("proxy", "protocol", "gemini", "url", "https://gemini-shape.test/v1",
                "models", "c"), NaruRegistrationScope.PROJECT);

        NaruModelRegistration r = store.get("proxy").get();
        Assertions.assertEquals("gemini", r.protocol().get());
        Assertions.assertEquals("https://gemini-shape.test/v1", r.url().get());
        Assertions.assertEquals(List.of("c"), r.models());
    }

    // ── writes and deletes stay in their own scope ────────────────────────────

    @Test
    public void writingTheUserScopeNeverTouchesTheProjectFiles() {
        store.put(registration("personal", "provider", "gemini", "apiKey", "sk-personal"),
                NaruRegistrationScope.USER);

        Assertions.assertTrue(userPrivate().isRegularFile(), "the literal key belongs in the user private file");
        Assertions.assertTrue(userPrivate().readString().contains("sk-personal"));
        Assertions.assertFalse(userPublic().readString().contains("sk-personal"));
        Assertions.assertFalse(projectPublic().isRegularFile());
        Assertions.assertFalse(projectPrivate().isRegularFile());
    }

    @Test
    public void writingTheProjectScopeNeverTouchesTheUserFiles() {
        store.put(registration("personal", "provider", "gemini", "apiKey", "sk-personal"),
                NaruRegistrationScope.USER);
        store.put(registration("project-only", "provider", "stub", "url", "https://project.test"),
                NaruRegistrationScope.PROJECT);

        Assertions.assertFalse(userPublic().readString().contains("project-only"));
        Assertions.assertEquals(1, store.ids(NaruRegistrationScope.USER).size());
        Assertions.assertEquals(List.of("project-only"), new ArrayList<>(store.ids(NaruRegistrationScope.PROJECT)));
    }

    @Test
    public void removingAProjectOverrideUncoversThePersonalRegistration() {
        store.put(registration("shared", "provider", "gemini", "url", "https://shared.test/v1"),
                NaruRegistrationScope.USER);
        store.put(registration("shared", "provider", "gemini", "model", "gemini-flash"),
                NaruRegistrationScope.PROJECT);

        Assertions.assertEquals("gemini-flash", store.get("shared").get().model().get());
        Assertions.assertTrue(store.remove("shared", NaruRegistrationScope.PROJECT));

        NaruModelRegistration still = store.get("shared").get();
        Assertions.assertEquals("gemini", still.provider().get());
        Assertions.assertFalse(still.model().isPresent());
        Assertions.assertEquals(NaruRegistrationScope.USER, store.origin("shared").get().scope());

        // the second delete has nothing left in the project, and does not reach the user file either
        Assertions.assertFalse(store.remove("shared", NaruRegistrationScope.PROJECT));
        Assertions.assertTrue(store.origin("shared").isPresent());
        Assertions.assertTrue(store.remove("shared", NaruRegistrationScope.USER));
        Assertions.assertFalse(store.origin("shared").isPresent());
    }

    @Test
    public void theProjectViewAndTheUserViewAreListedSeparately() {
        store.put(registration("alpha", "provider", "stub"), NaruRegistrationScope.USER);
        store.put(registration("beta", "provider", "stub"), NaruRegistrationScope.USER);
        store.put(registration("gamma", "provider", "stub"), NaruRegistrationScope.PROJECT);

        Assertions.assertEquals(List.of("alpha", "beta"), new ArrayList<>(store.ids(NaruRegistrationScope.USER)));
        Assertions.assertEquals(List.of("gamma"), new ArrayList<>(store.ids(NaruRegistrationScope.PROJECT)));
        Assertions.assertEquals(List.of("alpha", "beta", "gamma"),
                new ArrayList<>(store.toMap().keySet()));
    }

    @Test
    public void aStoreWithoutAUserLayerIsTheProjectAlone() {
        NaruRegistrationStore projectOnly = new NaruRegistrationStore(projectPublic(), projectPrivate());
        projectOnly.put(registration("fast", "provider", "gemini"));

        Assertions.assertEquals("gemini", projectOnly.get("fast").get().provider().get());
        Assertions.assertEquals(NaruRegistrationScope.PROJECT, projectOnly.origin("fast").get().scope());
        Assertions.assertTrue(projectOnly.ids(NaruRegistrationScope.USER).isEmpty());
        Assertions.assertThrows(IllegalStateException.class, () -> projectOnly.remove("fast", NaruRegistrationScope.USER));
    }
}
