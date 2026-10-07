package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.model.NaruModelRegistration;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NIllegalArgumentException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The two-file registration store: a literal credential lands in the private file,
 * everything else (including {@code $NAME} references) in the public one, and reads
 * always see the merged registration.
 */
public class NaruRegistrationStoreTest {

    private NPath dir;
    private NPath publicFile;
    private NPath privateFile;
    private NaruRegistrationStore store;

    @BeforeAll
    public static void setUpWorkspace() {
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            try {
                NWorkspace ws = Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Exception ignored) {
            }
        }
    }

    @BeforeEach
    public void setUp() {
        dir = NPath.ofTempFolder("naru-registrations-" + System.nanoTime());
        publicFile = dir.resolve(".naru/config/registrations.tson");
        privateFile = dir.resolve(".naru/local/config/registrations.tson");
        store = new NaruRegistrationStore(publicFile, privateFile);
    }

    private static NaruModelRegistration registration(String id, String apiKey) {
        return NaruModelRegistration.of(id, Map.of(
                "provider", NElement.ofString("gemini"),
                "apiKey", NElement.ofString(apiKey),
                "url", NElement.ofString("https://example.test/v1")
        ));
    }

    @Test
    public void literalSecretIsStoredPrivateReferenceStaysPublic() {
        store.put(registration("personal", "sk-abcdef1234"));

        String pub = publicFile.readString();
        String priv = privateFile.readString();
        Assertions.assertTrue(pub.contains("gemini"));
        Assertions.assertTrue(pub.contains("https://example.test/v1"));
        Assertions.assertFalse(pub.contains("sk-abcdef1234"), "literal secret leaked into the public file");
        Assertions.assertTrue(priv.contains("sk-abcdef1234"));

        // a reference carries no secret: it belongs to the checked-in file
        store.put(registration("work", "$WORK_GEMINI_KEY"));
        pub = publicFile.readString();
        priv = privateFile.readString();
        Assertions.assertTrue(pub.contains("$WORK_GEMINI_KEY"));
        Assertions.assertFalse(priv.contains("WORK_GEMINI_KEY"));
    }

    @Test
    public void readsMergeTheTwoFiles() {
        store.put(registration("personal", "sk-abcdef1234"));
        store.put(registration("alpha", "$ALPHA_GEMINI_KEY"));

        // a fresh store proves the view comes from the files, not from memory
        NaruRegistrationStore fresh = new NaruRegistrationStore(publicFile, privateFile);
        NaruModelRegistration r = fresh.get("personal").get();
        Assertions.assertEquals("gemini", r.provider());
        Assertions.assertEquals("https://example.test/v1", r.stringValue("url").get());
        Assertions.assertEquals("sk-abcdef1234", r.stringValue("apiKey").get());

        // without the private file only the public half of the registration exists
        NaruRegistrationStore publicOnly = new NaruRegistrationStore(
                publicFile, dir.resolve("absent/registrations.tson"));
        NaruModelRegistration pubR = publicOnly.get("personal").get();
        Assertions.assertEquals("gemini", pubR.provider());
        Assertions.assertFalse(pubR.param("apiKey").isPresent());
        // a reference-based registration survives on its own
        Assertions.assertEquals("$ALPHA_GEMINI_KEY",
                publicOnly.get("alpha").get().stringValue("apiKey").get());

        Assertions.assertFalse(fresh.get("nope").isPresent());
        Assertions.assertFalse(fresh.get(null).isPresent());
        Assertions.assertEquals(List.of("alpha", "personal"),
                new ArrayList<>(fresh.toMap().keySet()));
    }

    @Test
    public void handEditedFilesMergeFieldByField() {
        publicFile.parent().mkdirs();
        privateFile.parent().mkdirs();
        publicFile.writeString("{ personal : { provider : \"gemini\" , url : \"https://example.test/v1\" } }");
        privateFile.writeString("{ personal : { apiKey : \"sk-abcdef1234\" } }");

        NaruModelRegistration r = store.get("personal").get();
        Assertions.assertEquals("gemini", r.provider());
        Assertions.assertEquals("https://example.test/v1", r.stringValue("url").get());
        Assertions.assertEquals("sk-abcdef1234", r.stringValue("apiKey").get());
    }

    @Test
    public void brokenEntryFailsLoudly() {
        publicFile.parent().mkdirs();
        publicFile.writeString("{ broken : { url : \"https://example.test/v1\" } }");

        Assertions.assertThrows(NIllegalArgumentException.class, () -> store.get("broken"));
        Assertions.assertThrows(NIllegalArgumentException.class, () -> store.toMap());
    }

    @Test
    public void rePuttingRecomputesTheSplit() {
        store.put(registration("personal", "sk-abcdef1234"));

        // reference replaces literal: the secret must leave the private file
        store.put(store.get("personal").get().withParam("apiKey", "$GEMINI_KEY_A"));
        Assertions.assertFalse(privateFile.readString().contains("sk-abcdef1234"));
        Assertions.assertTrue(publicFile.readString().contains("$GEMINI_KEY_A"));

        // literal replaces reference: the new secret must leave the public file
        store.put(store.get("personal").get().withParam("apiKey", "sk-newKey9876"));
        Assertions.assertTrue(privateFile.readString().contains("sk-newKey9876"));
        Assertions.assertFalse(publicFile.readString().contains("sk-newKey9876"));
        Assertions.assertEquals("sk-newKey9876", store.get("personal").get().stringValue("apiKey").get());
    }

    @Test
    public void removeDeletesFromBothFiles() {
        store.put(registration("personal", "sk-abcdef1234"));
        store.put(registration("work", "$WORK_GEMINI_KEY"));

        Assertions.assertTrue(store.remove("personal"));
        Assertions.assertFalse(store.remove("personal"));
        Assertions.assertFalse(store.get("personal").isPresent());
        Assertions.assertFalse(publicFile.readString().contains("personal"));
        Assertions.assertFalse(privateFile.readString().contains("personal"));

        // untouched registrations survive
        Assertions.assertEquals("$WORK_GEMINI_KEY", store.get("work").get().stringValue("apiKey").get());
        Assertions.assertFalse(store.remove(null));
        Assertions.assertFalse(store.remove("  "));
    }
}
