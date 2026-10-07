package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.model.AbstractNaruModelProvider;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelRegistration;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Registrations become provider instances (design doc §2/§6/§11): materialized at
 * session start and after every write, addressed by their id, pinned to the models
 * they declare, merged into every model selected under them, and never able to
 * steal a built-in provider's id.
 */
public class RegistrationWiringTest {

    private NaruSession session;

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
        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-reg-wiring-" + System.nanoTime()));
        // configureDefaults=false: no SPI provider discovery, no network, no real models
        session = new NaruSessionImpl(agent, agent.projectDirectory(), null, false,
                new NaruSessionListener() {
                    @Override
                    public void onEventAppended(NaruEvent newEvent) {
                    }

                    @Override
                    public void sessionStarted(NaruSession session) {
                    }

                    @Override
                    public void sessionStopped(NaruSession session) {
                    }

                    @Override
                    public void onSessionReloaded(NaruSession naruSession) {
                    }
                }, null, null, null);
    }

    @AfterEach
    public void tearDown() {
        if (session != null) {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    public void registrationMaterializesAsAPinnedInstanceAndMergesParams() {
        session.registry().registerModelProvider(new StubProvider());
        session.putRegistration(NaruModelRegistration.of("fast", Map.of(
                "provider", NElement.ofString("stub"),
                "model", NElement.ofString("s-model"),
                "temperature", NElement.of(0.2f),
                "url", NElement.ofString("https://fast.test/v1")
        )));

        // instance in the catalog under its own id, with the registration's params
        NaruModelProvider instance = session.registry().modelProviders().get("fast");
        Assertions.assertNotNull(instance, "registration did not materialize a provider instance");
        Assertions.assertEquals("fast", instance.name());
        Assertions.assertEquals("stub", instance.type());
        Assertions.assertEquals("https://fast.test/v1", instance.rawParam("url").get());

        // --model pins the catalogue: the base type's other models are not selectable
        // under this instance id, and the bare base provider lists its own
        Assertions.assertEquals(List.of(
                "fast/s-model",
                "stub/other-model",
                "stub/s-model"
        ), session.registry().modelsKeys(session).stream()
                .map(NaruModelKey::toString)
                .collect(Collectors.toList()));

        // bare id resolves: exactly one model is pinned
        NaruModelConfig bare = session.findModel("fast").get();
        Assertions.assertEquals("fast", bare.provider());
        Assertions.assertEquals("s-model", bare.model());
        Assertions.assertEquals(0.2f, bare.temperature(), "registration temperature not merged");

        // full key works, unpinned models stay out
        Assertions.assertEquals("fast/s-model",
                session.findModel("fast/s-model").get().key().toString());
        Assertions.assertFalse(session.findModel("fast/other-model").isPresent());
    }

    @Test
    public void unpinnedRegistrationIsNeverAnImplicitChoice() {
        session.registry().registerModelProvider(new StubProvider());
        session.putRegistration(NaruModelRegistration.of("cluster", Map.of(
                "provider", NElement.ofString("stub")
        )));

        // no pin: the instance lists its type's models, but the bare id selects nothing
        // (the directive reports the available models instead of guessing)
        Assertions.assertEquals(List.of(
                "cluster/other-model",
                "cluster/s-model",
                "stub/other-model",
                "stub/s-model"
        ), session.registry().modelsKeys(session).stream()
                .map(NaruModelKey::toString)
                .collect(Collectors.toList()));
        Assertions.assertFalse(session.findModel("cluster").isPresent());
        Assertions.assertTrue(session.findModel("cluster/s-model").isPresent());
    }

    @Test
    public void disabledRegistrationHidesFromEveryListing() {
        session.registry().registerModelProvider(new StubProvider());
        session.putRegistration(NaruModelRegistration.of("hidden", Map.of(
                "provider", NElement.ofString("stub"),
                "enabled", NElement.ofString("false")
        )));

        // the instance exists (it can be re-enabled) but contributes no models
        Assertions.assertNotNull(session.registry().modelProviders().get("hidden"));
        Assertions.assertTrue(session.registry().modelsKeys(session).stream()
                .noneMatch(k -> k.provider().equals("hidden")));
        Assertions.assertFalse(session.findModel("hidden/s-model").isPresent());
    }

    @Test
    public void removingARegistrationRemovesItsInstance() {
        session.registry().registerModelProvider(new StubProvider());
        session.putRegistration(NaruModelRegistration.of("fast", Map.of(
                "provider", NElement.ofString("stub"),
                "model", NElement.ofString("s-model")
        )));
        Assertions.assertNotNull(session.registry().modelProviders().get("fast"));

        Assertions.assertTrue(session.removeRegistration("fast"));
        Assertions.assertNull(session.registry().modelProviders().get("fast"),
                "removed registration still present as a provider instance");
        Assertions.assertFalse(session.findModel("fast/s-model").isPresent());
        Assertions.assertFalse(session.registrations().containsKey("fast"));
    }

    @Test
    public void builtinProviderIdIsNeverStolen() {
        StubProvider stub = new StubProvider();
        session.registry().registerModelProvider(stub);

        // hand-edited entry (or a typo) whose id collides with the built-in type
        session.putRegistration(NaruModelRegistration.of("stub", Map.of(
                "provider", NElement.ofString("stub"),
                "url", NElement.ofString("https://evil.test/v1")
        )));

        Assertions.assertSame(stub, session.registry().modelProviders().get("stub"));
        Assertions.assertFalse(stub.rawParam("url").isPresent(),
                "registration overwrote the built-in provider instance");

        // removing the registration must not unregister the built-in either
        session.removeRegistration("stub");
        Assertions.assertSame(stub, session.registry().modelProviders().get("stub"));
    }

    /**
     * The API key is picked up from the layered env (design §6): session env
     * first, then agent env, then the system environment — for the instance-scoped
     * name, for {@code $NAME} references inside a registration value, and for the
     * type's default env keys alike.
     */
    @Test
    public void apiKeyIsPickedUpFromSessionEnvThenAgentEnvThenSystemEnv() {
        session.registry().registerModelProvider(new StubProvider());
        session.registry().registerModelProvider(new SystemEnvStubProvider());
        session.putRegistration(NaruModelRegistration.of("team", Map.of(
                "provider", NElement.ofString("stub")
        )));
        session.putRegistration(NaruModelRegistration.of("rotated", Map.of(
                "provider", NElement.ofString("stub"),
                "apiKey", NElement.ofString("$ONLY_IN_SESSION")
        )));
        session.putRegistration(NaruModelRegistration.of("sys-team", Map.of(
                "provider", NElement.ofString("sysenv")
        )));
        NaruModelProvider team = session.registry().modelProviders().get("team");
        NaruModelProvider rotated = session.registry().modelProviders().get("rotated");
        NaruModelProvider sysTeam = session.registry().modelProviders().get("sys-team");

        // $NAME references resolve through the layered env: a value that exists only
        // in the session env is found without any export
        session.setSessionEnv("ONLY_IN_SESSION", "from-session-var");
        Assertions.assertEquals("from-session-var", rotated.apiKey(session).orElse(null));

        // <id>.apiKey: session env first, agent (project) env second
        session.setSessionEnv("team.apiKey", "from-session");
        session.setProjectEnv("team.apiKey", NElement.ofString("from-agent"), NaruVisibility.PRIVATE);
        Assertions.assertEquals("from-session", team.apiKey(session).orElse(null));
        session.unsetSessionEnv("team.apiKey");
        Assertions.assertEquals("from-agent", team.apiKey(session).orElse(null));

        // the type's default env keys read the same layers, system env last:
        // PATH is always exported, and a session value for the same name wins
        session.setSessionEnv("PATH", "from-session-path");
        Assertions.assertEquals("from-session-path", sysTeam.apiKey(session).orElse(null));
        session.unsetSessionEnv("PATH");
        Assertions.assertEquals(System.getenv("PATH"), sysTeam.apiKey(session).orElse(null));
    }

    /**
     * A class-less provider type: no-arg constructor so registrations can
     * instantiate it under any id, two models so pins and filters are observable.
     */
    public static class StubProvider extends AbstractNaruModelProvider {
        public StubProvider() {
            super("stub", new String[0]);
        }

        @Override
        public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
            return NOptional.ofNamedEmpty(NMsg.ofC("no protocol in test"));
        }

        @Override
        public List<String> findModelIds(NaruSession session) {
            return List.of("other-model", "s-model");
        }
    }

    /**
     * A type whose only default env key is the process' {@code PATH}: always
     * present in any environment, so the system layer of the key chain can be
     * asserted without exporting anything.
     */
    public static class SystemEnvStubProvider extends AbstractNaruModelProvider {
        public SystemEnvStubProvider() {
            super("sysenv", new String[]{"PATH"});
        }

        @Override
        public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
            return NOptional.ofNamedEmpty(NMsg.ofC("no protocol in test"));
        }

        @Override
        public List<String> findModelIds(NaruSession session) {
            return List.of();
        }
    }
}
