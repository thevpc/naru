package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelRegistration;
import net.thevpc.naru.api.model.NaruRegistrationOrigin;
import net.thevpc.naru.api.model.NaruRegistrationScope;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * The user-global registration layer as a session sees it: {@code naru.userHome} decides where the
 * personal layer lives, a registration written to {@code USER} materializes as a provider instance
 * like any other, and a project override of the same id neither rewrites nor leaks into it.
 *
 * <p>The home is a temp folder, not the developer's real {@code ~/.naru}: these assertions are
 * about the layering, and a personal registration on the machine running the build would otherwise
 * decide them.
 */
public class NaruSessionUserRegistrationTest {

    private NPath userHome;
    private NaruWorkspace agent;
    private NaruSession session;

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    @BeforeEach
    public void setUp() {
        userHome = NPath.ofTempFolder("naru-user-reg-home-" + System.nanoTime());
        System.setProperty("naru.userHome", userHome.toString());
        agent = NaruWorkspace.of();
        agent.projectDirectory(NPath.ofTempFolder("naru-user-reg-project-" + System.nanoTime()));
        // configureDefaults=false: no SPI provider discovery, no network, no real models
        session = agent.newSession()
                .sessionDir(agent.projectDirectory())
                .interaction(null)
                .configureDefaults(false)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
        session.registry().registerModelProvider(new RegistrationWiringTest.StubProvider());
        session.registry().registerModelProvider(new RegistrationWiringTest.SystemEnvStubProvider());
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty("naru.userHome");
        if (session != null) {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private static NaruModelRegistration personal(String id) {
        return NaruModelRegistration.of(id, Map.of(
                "provider", NElement.ofString("stub"),
                "model", NElement.ofString("s-model"),
                "url", NElement.ofString("https://personal.test/v1"),
                "temperature", NElement.of(0.3f)
        ));
    }

    @Test
    public void aUserRegistrationIsStoredInTheHomeAndMaterializesLikeAnyOther() {
        session.putRegistration(personal("personal"), NaruRegistrationScope.USER);

        Assertions.assertTrue(userHome.resolve(".naru/config/registrations.tson").isRegularFile(),
                "a user registration never reached the home directory");
        Assertions.assertFalse(agent.projectDirectory().resolve(".naru/config/registrations.tson").isRegularFile(),
                "a user write also touched the project");

        NaruModelProvider instance = session.registry().modelProviders().get("personal");
        Assertions.assertNotNull(instance, "the user registration did not materialize a provider instance");
        Assertions.assertEquals("https://personal.test/v1", instance.rawParam("url").get());
        Assertions.assertEquals(0.3f, session.findModel("personal").get().temperature(), 0.0);
        Assertions.assertEquals(NaruRegistrationScope.USER, session.registrationOrigin("personal").get().scope());
    }

    @Test
    public void aProjectOverrideKeepsItsCredentialOutOfTheHomeAndTheHomeOutOfItsFile() {
        session.putRegistration(personal("shared"), NaruRegistrationScope.USER);
        session.putRegistration(NaruModelRegistration.of("shared", Map.of(
                "provider", NElement.ofString("stub"),
                "model", NElement.ofString("s-model"),
                "temperature", NElement.of(0.9f),
                "apiKey", NElement.ofString("sk-project-key")
        )), NaruRegistrationScope.PROJECT);

        Assertions.assertEquals(0.9f, session.findModel("shared").get().temperature(), 0.0,
                "the project override did not reach the resolved model");
        NaruRegistrationOrigin o = session.registrationOrigin("shared").get();
        Assertions.assertEquals(NaruRegistrationScope.PROJECT, o.scope());
        Assertions.assertEquals(NaruRegistrationScope.PROJECT, o.scopeOf("apiKey"));
        Assertions.assertEquals(NaruRegistrationScope.USER, o.scopeOf("url"));

        // the personal key rotation that follows must not rewrite the project's credential, and
        // the project's literal key must not appear in a home file nobody asked it to touch
        session.putRegistration(personal("shared"), NaruRegistrationScope.USER);
        NaruModelProvider instance = session.registry().modelProviders().get("shared");
        Assertions.assertEquals("sk-project-key", instance.rawParam("apiKey").get(),
                "a user write overwrote the project's credential");
        Assertions.assertFalse(userHome.resolve(".naru/config/registrations.tson").readString()
                .contains("sk-project-key"));
        Assertions.assertFalse(userHome.resolve(".naru/local/config/registrations.tson").isRegularFile());

        // deleting the project override uncovers the personal registration instead of removing it
        Assertions.assertTrue(session.removeRegistration("shared", NaruRegistrationScope.PROJECT));
        Assertions.assertEquals(NaruRegistrationScope.USER, session.registrationOrigin("shared").get().scope());
        Assertions.assertNotNull(session.registry().modelProviders().get("shared"));
        // the reload rebuilt the instance from what the home now says, project override gone
        NaruModelConfig uncovered = session.findModel("shared").orNull();
        Assertions.assertNotNull(uncovered, "the personal layer must resolve on its own, catalog="
                + session.registry().modelsKeys(session) + " merged=" + session.registrations().get("shared"));
        Assertions.assertEquals(0.3f, uncovered.temperature(), 0.0);
    }

    @Test
    public void aProjectThatNamesAnotherProviderForAPersonalIdReplacesItEntirely() {
        session.putRegistration(personal("fast"), NaruRegistrationScope.USER);
        session.putRegistration(NaruModelRegistration.of("fast", Map.of(
                "provider", NElement.ofString("sysenv"),
                "url", NElement.ofString("https://project.test/v1")
        )), NaruRegistrationScope.PROJECT);

        NaruModelProvider instance = session.registry().modelProviders().get("fast");
        Assertions.assertEquals("sysenv", instance.type());
        Assertions.assertEquals("https://project.test/v1", instance.rawParam("url").get());
        Assertions.assertFalse(instance.rawParam("temperature").isPresent(),
                "the personal endpoint's parameters survived into a different registration");

        NaruRegistrationOrigin o = session.registrationOrigin("fast").get();
        Assertions.assertEquals(NaruRegistrationScope.PROJECT, o.scope());
        Assertions.assertTrue(o.fieldsOf(NaruRegistrationScope.USER).isEmpty());
    }
}
