package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.AbstractNaruModelProvider;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelFilter;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelRegistration;
import net.thevpc.naru.api.registry.NaruConfigKey;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The policy axis of model visibility: the {@code model.filter.*} config keys narrow
 * what every session of the project may list and call, are AND-combined with whatever
 * a host set in code, and say nothing at all when the project sets none of them.
 *
 * <p>Asserted at the registry, not through the {@code /model} directive: a denied
 * model disappears from {@code modelsKeys()} and fails {@code isModelAllowed()}, which
 * is the same predicate a task call hits before any transport is built.
 */
public class NaruModelPolicyTest {

    private NaruWorkspace workspace;
    private NaruSession session;
    private final List<NaruSession> extraSessions = new ArrayList<>();
    private final List<String> logged = new ArrayList<>();

    @BeforeAll
    public static void setUpNuts() {
        Nuts.require();
    }

    @AfterEach
    public void tearDown() {
        for (NaruSession s : extraSessions) {
            try {
                s.stop();
            } catch (Exception ignored) {
            }
        }
        if (session != null) {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    /**
     * A workspace with the given policy keys in its public project env, and a session on
     * it that has two stub providers, {@code stub} and {@code ollama}.
     *
     * <p>The env is written before the session is built because that is when the policy
     * resolves — a session keeps the filter it was born with.
     */
    private void start(Map<String, Object> policyEnv) {
        NaruWorkspaceImpl w = new NaruWorkspaceImpl();
        w.logger(m -> logged.add(m.toString()));
        w.projectDirectory(NPath.ofTempFolder("naru-model-policy-" + System.nanoTime()));
        for (Map.Entry<String, Object> e : policyEnv.entrySet()) {
            // PUBLIC: the shape a checked-in .naru/config/env.tson has
            w.env().put(e.getKey(), element(e.getValue()), NaruVisibility.PUBLIC);
        }
        workspace = w;
        // configureDefaults=false: no SPI discovery, no network, no real models
        session = w.newSession()
                .sessionDir(w.projectDirectory())
                .interaction(null)
                .configureDefaults(false)
                .build();
        session.registry().registerModelProvider(new RegistrationWiringTest.StubProvider());
        session.registry().registerModelProvider(new LocalStubProvider());
    }

    private static NElement element(Object v) {
        if (v instanceof Boolean b) {
            return NElement.of(b);
        }
        return NElement.ofString(String.valueOf(v));
    }

    /** Another session on the same workspace, with the two providers and an optional host filter. */
    private NaruSession newSession(NaruModelFilter hostFilter) {
        NaruSession s = workspace.newSession()
                .sessionDir(workspace.projectDirectory())
                .interaction(null)
                .configureDefaults(false)
                .modelFilter(hostFilter)
                .build();
        extraSessions.add(s);
        s.registry().registerModelProvider(new RegistrationWiringTest.StubProvider());
        s.registry().registerModelProvider(new LocalStubProvider());
        return s;
    }

    private static List<String> keys(NaruSession s) {
        return s.registry().modelsKeys(s).stream()
                .map(NaruModelKey::toString)
                .sorted()
                .collect(Collectors.toList());
    }

    private List<String> listed() {
        // sorted, because the registry's own order is provider-registration order, which is
        // not what is being asserted here
        return session.registry().modelsKeys(session).stream()
                .map(NaruModelKey::toString)
                .sorted()
                .collect(Collectors.toList());
    }

    private boolean allowed(String key) {
        NaruModelKey k = NaruModelKey.parse(key).get();
        return session.registry().isModelAllowed(k, null);
    }

    /**
     * Only the policy's own warnings: the workspace logger is process-wide and also sees
     * the extension loader saying what it loaded.
     */
    private List<String> warnings() {
        return logged.stream().filter(s -> s.contains("model policy")).collect(Collectors.toList());
    }

    /** A provider named the way a local one is named, so the no-task listing can see it. */
    public static class LocalStubProvider extends AbstractNaruModelProvider {
        public LocalStubProvider() {
            super("ollama", new String[0]);
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

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    public void noPolicyKeysMeanNoFilterAtAll() {
        start(Map.of());
        Assertions.assertNull(workspace.modelFilter(),
                "a project that names none of the keys must not acquire a filter");
        Assertions.assertEquals(List.of("ollama/other-model", "ollama/s-model",
                "stub/other-model", "stub/s-model"), listed());
    }

    @Test
    public void anAllowListKeepsOnlyThoseProvidersInTheListingAndInTheCall() {
        start(Map.of(NaruModelPolicy.ALLOW_PROVIDERS, "ollama"));
        Assertions.assertEquals(List.of("ollama/other-model", "ollama/s-model"), listed());
        Assertions.assertFalse(allowed("stub/s-model"));
        Assertions.assertTrue(allowed("ollama/s-model"));
    }

    @Test
    public void providerIdsAreNotCaseSensitiveAndSpacesAreTolerated() {
        start(Map.of(NaruModelPolicy.ALLOW_PROVIDERS, " Stub , stub "));
        Assertions.assertEquals(List.of("stub/other-model", "stub/s-model"), listed());
    }

    @Test
    public void aDenyListRemovesProvidersTheAllowListWouldKeep() {
        // both at once: the intersection is what survives, which is the only reading that
        // lets a team allow a broad set and a project carve one provider out of it
        start(Map.of(NaruModelPolicy.ALLOW_PROVIDERS, "stub,ollama",
                NaruModelPolicy.DENY_PROVIDERS, "ollama"));
        Assertions.assertEquals(List.of("stub/other-model", "stub/s-model"), listed());
    }

    @Test
    public void anEmptyAllowListIsNoAllowListRatherThanANothingAllowed() {
        // the footgun the naive reading invites: providers() with zero ids denies everything,
        // so an empty value has to mean "I wrote the key but do not restrict providers"
        start(Map.of(NaruModelPolicy.ALLOW_PROVIDERS, " , "));
        Assertions.assertEquals(4, listed().size());
        Assertions.assertTrue(allowed("stub/s-model"));
    }

    @Test
    public void aSingleModelCanBeHiddenWithoutItsProvider() {
        start(Map.of(NaruModelPolicy.DENY_MODELS, "stub/s-model"));
        Assertions.assertEquals(List.of("ollama/other-model", "ollama/s-model", "stub/other-model"),
                listed());
        Assertions.assertTrue(allowed("stub/other-model"));
    }

    @Test
    public void aModelDenyWithoutAProviderIsRefusedRatherThanGuessed() {
        // NaruModelKey.parse() defaults a bare name to ollama/..., which would silently hide
        // a model on a server the config never mentioned
        start(Map.of(NaruModelPolicy.DENY_MODELS, "s-model"));
        Assertions.assertEquals(4, listed().size(), "the bad entry must not deny anything");
        Assertions.assertEquals(1, warnings().size(), warnings().toString());
        Assertions.assertTrue(warnings().get(0).contains("not a provider/model key"), warnings().get(0));
    }

    @Test
    public void localOnlyKeepsTheLocalEndpointAndDropsTheCloudOne() {
        start(Map.of(NaruModelPolicy.LOCAL_ONLY, "true"));
        Assertions.assertEquals(List.of("ollama/other-model", "ollama/s-model"), listed());
        Assertions.assertFalse(allowed("stub/s-model"));
    }

    @Test
    public void localOnlyFalseRestrictsNothing() {
        start(Map.of(NaruModelPolicy.LOCAL_ONLY, "false"));
        Assertions.assertEquals(4, listed().size());
    }

    @Test
    public void anUnparseablePolicyValueIsIgnoredAndReported() {
        // a workspace that will not start over a typo is worse than one that says what it
        // ignored — but it must say, or the restriction reads as having taken effect
        start(Map.of(NaruModelPolicy.LOCAL_ONLY, "maybe"));
        Assertions.assertEquals(4, listed().size());
        Assertions.assertEquals(1, warnings().size(), warnings().toString());
        // the warning names the subsystem and the key, so a transcript that scrolls past it
        // still says what was ignored and by what
        Assertions.assertTrue(warnings().get(0).contains(NaruModelPolicy.LOCAL_ONLY), warnings().get(0));
        Assertions.assertTrue(warnings().get(0).contains("not true or false"), warnings().get(0));
    }

    @Test
    public void aBooleanElementWorksAsWellAsAQuotedString() {
        // TSON lets you write localOnly: true without quotes; dropping it silently would
        // look exactly like the policy being ignored
        start(Map.of(NaruModelPolicy.LOCAL_ONLY, true));
        Assertions.assertEquals(List.of("ollama/other-model", "ollama/s-model"), listed());
        Assertions.assertTrue(warnings().isEmpty(), warnings().toString());
    }

    @Test
    public void theAgentEnvWinsOverTheProjectEnvLikeEveryOtherEnvKey() {
        start(Map.of(NaruModelPolicy.LOCAL_ONLY, "true"));
        workspace.agentEnv().put(NaruModelPolicy.LOCAL_ONLY, NElement.ofString("false"),
                NaruVisibility.PRIVATE);
        // resolved at the next build, not retroactively: the running session keeps its filter
        NaruSession second = newSession(null);
        Assertions.assertEquals(4, keys(second).size(),
                "the agent layer should override the project's localOnly");
        Assertions.assertEquals(2, listed().size(), "the first session keeps the policy it was built with");
    }

    @Test
    public void aHostFilterAndThePolicyFilterBothApply() {
        start(Map.of(NaruModelPolicy.ALLOW_PROVIDERS, "stub,ollama"));
        // the combination the builder does: agent.modelFilter() AND the caller's filter
        NaruSession second = newSession(NaruModelFilter.denyProviders("ollama"));
        Assertions.assertEquals(List.of("stub/other-model", "stub/s-model"), keys(second));
    }

    @Test
    public void anInstanceIsPolicedUnderItsOwnId() {
        start(Map.of(NaruModelPolicy.ALLOW_PROVIDERS, "team"));
        session.putRegistration(NaruModelRegistration.builder("team").put("provider", NElement.ofString("stub")).build());
        // instance ids, not type ids, are what a key names — the allow-list has to speak the
        // same vocabulary the listing prints, or every personal registration is invisible
        Assertions.assertTrue(allowed("team/s-model"), listed().toString());
        Assertions.assertFalse(allowed("stub/s-model"));
    }

    @Test
    public void thePolicyIsListedAsSettingsVocabulary() {
        start(Map.of());
        // the workspace catalog, not the session registry: the registry only holds providers
        // the extensions registered, and this session deliberately registers none
        List<String> declared = workspace.configKeys().stream()
                .map(NaruConfigKey::key)
                .collect(Collectors.toList());
        for (String k : NaruModelPolicy.keys()) {
            Assertions.assertTrue(declared.contains(k), k + " must be a declared key, got " + declared);
        }
    }

    @Test
    public void anAbsentWorkspaceHasNoPolicy() {
        Assertions.assertNull(NaruModelPolicy.filterFor(null));
        Assertions.assertNull(NaruModelPolicy.filterFor(new NaruWorkspaceImpl()));
    }

    @Test
    public void aLaunchFlagAppliesWhenNoEnvLayerNamesTheKey() {
        withProperty(NaruModelPolicy.DENY_PROVIDERS, "ollama", () -> {
            start(Map.of());
            Assertions.assertEquals(List.of("stub/other-model", "stub/s-model"), listed());
        });
    }

    @Test
    public void aLaunchFlagCannotLoosenACheckedInPolicy() {
        // the weakest layer of the env chain, like every other key: a launch flag that could
        // switch a team's restriction off would not be much of a restriction
        withProperty(NaruModelPolicy.LOCAL_ONLY, "false", () -> {
            start(Map.of(NaruModelPolicy.LOCAL_ONLY, "true"));
            Assertions.assertEquals(List.of("ollama/other-model", "ollama/s-model"), listed());
        });
    }

    private static void withProperty(String key, String value, Runnable body) {
        String had = System.getProperty(key);
        System.setProperty(key, value);
        try {
            body.run();
        } finally {
            if (had == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, had);
            }
        }
    }
}
