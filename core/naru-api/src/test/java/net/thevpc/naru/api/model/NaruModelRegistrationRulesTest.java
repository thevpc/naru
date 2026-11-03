package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruRegistry;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The registration rules, tested at the level a host actually consumes them: the
 * {@link NaruModelRegistrationRules.Outcome} rather than printed text.
 *
 * <p>The {@code /model} directive's own test drives a command line and reads the message,
 * which is the right shape for a CLI but cannot be shared with the desktop's settings form.
 * Both hosts call this class, so the syntax verdicts, the identity merge rules and the
 * protocol normalization are pinned here — including the normalization, which is not
 * decoration: a form that stored {@code protocol: "gemini"} on a gemini registration would
 * write a file the CLI considers a delta it must drop again.
 */
public class NaruModelRegistrationRulesTest {

    private final Map<String, NaruModelProvider> builtins = new LinkedHashMap<>();
    private final Map<String, NaruModelRegistration> registrations = new LinkedHashMap<>();
    private final Map<String, NaruRegistrationOrigin> origins = new LinkedHashMap<>();
    private NaruSession session;

    @BeforeAll
    public static void requireNuts() {
        // the proxies below build NOptional values, and those factories resolve through the
        // nuts context
        Nuts.require();
    }

    @BeforeEach
    public void setUp() {
        builtins.clear();
        registrations.clear();
        origins.clear();
        builtins.put("gemini", new FakeProvider("gemini", "gemini", Set.of("openai", "gemini")));
        builtins.put("openai", new FakeProvider("openai", "openai", Set.of("openai", "anthropic")));
        // a type whose wire shape is fixed and unnameable: --protocol has nothing to name
        builtins.put("stub", new FakeProvider("stub", null, Set.of()));
        builtins.put("custom", new FakeProvider("custom", "openai", Set.of("openai", "anthropic", "gemini")));
        session = fakeSession(builtins, registrations, origins);
    }

    // ── identity ───────────────────────────────────────────────────────────────

    @Test
    public void blankIdNamesTheUsage() {
        NaruModelRegistrationRules.Outcome out = NaruModelRegistrationRules.apply(session, "  ", Map.of("provider", NElement.ofString("gemini")), false);
        Assertions.assertTrue(out.problem().contains("missing registration id"), out.problem());
        Assertions.assertTrue(out.problem().contains("--provider=<type>"), out.problem());
    }

    @Test
    public void slashInIdIsRefusedBecauseItReadsAsAModelKey() {
        NaruModelRegistrationRules.Outcome out = NaruModelRegistrationRules.apply(session, "a/b", Map.of("provider", NElement.ofString("gemini")), false);
        Assertions.assertTrue(out.problem().contains("'/' separates"), out.problem());
    }

    @Test
    public void neitherProviderNorProtocolIsNotARegistration() {
        NaruModelRegistrationRules.Outcome out = NaruModelRegistrationRules.apply(session, "x", Map.of("url", NElement.ofString("http://h")), false);
        Assertions.assertTrue(out.problem().contains("missing identity"), out.problem());
    }

    @Test
    public void providerValuesThatAreWireIdsAreRefusedWithAMigrationHint() {
        for (String typed : List.of("custom", "wire", "openapi", "anthropic")) {
            NaruModelRegistrationRules.Outcome out = NaruModelRegistrationRules.apply(session, "r", Map.of("provider", NElement.ofString(typed)), false);
            Assertions.assertNotNull(out.problem(), typed + " must be refused: " + out.registration());
            Assertions.assertTrue(out.problem().contains("--protocol") || out.problem().contains("--provider=openai"),
                    typed + " must say what to write instead, got: " + out.problem());
        }
    }

    @Test
    public void unknownProviderTypeListsTheKnownOnes() {
        NaruModelRegistrationRules.Outcome out = NaruModelRegistrationRules.apply(session, "r", Map.of("provider", NElement.ofString("nope")), false);
        Assertions.assertTrue(out.problem().contains("unknown provider type 'nope'"), out.problem());
        Assertions.assertTrue(out.problem().contains("gemini") && out.problem().contains("stub"),
                "known types must be listed, got: " + out.problem());
    }

    // ── the generic endpoint ───────────────────────────────────────────────────

    @Test
    public void genericEndpointNeedsAUrlAndItsModels() {
        NaruModelRegistrationRules.Outcome noUrl = NaruModelRegistrationRules.apply(session, "e", Map.of("protocol", NElement.ofString("openai")), false);
        Assertions.assertTrue(noUrl.problem().contains("needs a base url"), noUrl.problem());

        Map<String, NElement> noModels = new LinkedHashMap<>();
        noModels.put("protocol", NElement.ofString("openai"));
        noModels.put("url", NElement.ofString("http://127.0.0.1:11434"));
        NaruModelRegistrationRules.Outcome out = NaruModelRegistrationRules.apply(session, "e", noModels, false);
        Assertions.assertTrue(out.problem().contains("--models=a,b"), out.problem());
    }

    @Test
    public void completeGenericEndpointIsCreatedAndKeepsItsProtocol() {
        Map<String, NElement> edits = new LinkedHashMap<>();
        edits.put("protocol", NElement.ofString("openai"));
        edits.put("url", NElement.ofString("http://127.0.0.1:8080/v1"));
        edits.put("models", NElement.ofString("a,b"));
        NaruModelRegistrationRules.Outcome out = NaruModelRegistrationRules.apply(session, "e", edits, false);
        Assertions.assertTrue(out.isOk(), out.problem());
        Assertions.assertTrue(out.generic(), "no provider param means a generic endpoint");
        Assertions.assertTrue(out.created());
        Assertions.assertEquals("openai", out.protocol());
        Assertions.assertEquals("custom", out.registration().provider());
        // the pin survives as written: no provider class exists to enumerate it
        Assertions.assertEquals(List.of("a", "b"), out.registration().stringList("models"));
    }

    // ── protocol normalization ─────────────────────────────────────────────────

    @Test
    public void namingTheTypesOwnDefaultWireStoresNothing() {
        NaruModelRegistrationRules.Outcome out = NaruModelRegistrationRules.apply(session, "p",
                Map.of("provider", NElement.ofString("gemini"), "protocol", NElement.ofString("gemini")), false);
        Assertions.assertTrue(out.isOk(), out.problem());
        Assertions.assertFalse(out.registration().param("protocol").isPresent(),
                "a registration must read as a delta over the type's defaults");
        Assertions.assertNull(out.protocol());
    }

    @Test
    public void aRealWireOverrideIsStoredCanonically() {
        NaruModelRegistrationRules.Outcome out = NaruModelRegistrationRules.apply(session, "p",
                Map.of("provider", NElement.ofString("gemini"), "protocol", NElement.ofString("OpenAI")), false);
        Assertions.assertTrue(out.isOk(), out.problem());
        Assertions.assertEquals("openai", out.protocol(), "the canonical id is stored, not the typed one");
        Assertions.assertEquals("openai", out.registration().protocol().get());
    }

    @Test
    public void unknownProtocolForATypeIsRefusedWithTheSupportedOnes() {
        NaruModelRegistrationRules.Outcome out = NaruModelRegistrationRules.apply(session, "p",
                Map.of("provider", NElement.ofString("gemini"), "protocol", NElement.ofString("soap")), false);
        Assertions.assertTrue(out.problem().contains("unknown protocol 'soap'"), out.problem());
        Assertions.assertTrue(out.problem().contains("openai") && out.problem().contains("gemini"),
                "supported shapes must be listed, got: " + out.problem());
    }

    @Test
    public void aFixedWireTypeRefusesProtocolAltogether() {
        NaruModelRegistrationRules.Outcome out = NaruModelRegistrationRules.apply(session, "p",
                Map.of("provider", NElement.ofString("stub"), "protocol", NElement.ofString("openai")), false);
        Assertions.assertTrue(out.problem().contains("does not support --protocol"), out.problem());
    }

    // ── ids and instances ──────────────────────────────────────────────────────

    @Test
    public void anIdOwnedByABuiltInIsRefused() {
        NaruModelRegistrationRules.Outcome out = NaruModelRegistrationRules.apply(session, "gemini",
                Map.of("provider", NElement.ofString("openai")), false);
        Assertions.assertTrue(out.problem().contains("already used by provider"), out.problem());
    }

    @Test
    public void anIdTypedInAnotherCaseUpdatesTheSameEntry() {
        registrations.put("Personal", NaruModelRegistration.of("Personal", "gemini"));
        NaruModelRegistrationRules.Outcome out = NaruModelRegistrationRules.apply(session, "personal",
                Map.of("temperature", NElement.of(0.5f)), true);
        Assertions.assertTrue(out.isOk(), out.problem());
        Assertions.assertEquals("Personal", out.registration().id(), "a different casing must never fork the entry");
        Assertions.assertFalse(out.created());
    }

    // ── merge semantics of an update ───────────────────────────────────────────

    @Test
    public void updateKeepsUntouchedParametersAndAClearValueDropsOne() {
        Map<String, NElement> seeded = new LinkedHashMap<>();
        seeded.put("provider", NElement.ofString("gemini"));
        seeded.put("temperature", NElement.of(0.2f));
        seeded.put("url", NElement.ofString("http://keep-me"));
        registrations.put("temp", NaruModelRegistration.of("temp", seeded));

        Map<String, NElement> edits = new LinkedHashMap<>();
        edits.put("temperature", null);
        edits.put("maxTokens", NElement.of(64));
        NaruModelRegistrationRules.Outcome out = NaruModelRegistrationRules.apply(session, "temp", edits, true);

        Assertions.assertTrue(out.isOk(), out.problem());
        Assertions.assertFalse(out.registration().param("temperature").isPresent(), "an empty value clears");
        Assertions.assertEquals("http://keep-me", out.registration().stringValue("url").get());
        Assertions.assertTrue(out.registration().param("maxTokens").isPresent());
    }

    @Test
    public void updateCannotStripTheIdentityIntoAShell() {
        registrations.put("personal", NaruModelRegistration.of("personal", "gemini"));
        Map<String, NElement> clearProvider = new LinkedHashMap<>();
        clearProvider.put("provider", null);
        NaruModelRegistrationRules.Outcome out = NaruModelRegistrationRules.apply(session, "personal", clearProvider, true);
        Assertions.assertTrue(out.problem().contains("cannot clear"), out.problem());
    }

    @Test
    public void updateOfAnUnknownIdPointsAtTheCreatingCommand() {
        NaruModelRegistrationRules.Outcome out = NaruModelRegistrationRules.apply(session, "ghost", Map.of("provider", NElement.ofString("gemini")), true);
        Assertions.assertTrue(out.problem().contains("no registration 'ghost'"), out.problem());
        Assertions.assertTrue(out.problem().contains("/model add"), out.problem());
    }

    // ── scopes ─────────────────────────────────────────────────────────────────

    @Test
    public void anUpdateOfTheUserScopeNeverCarriesTheProjectFields() {
        Map<String, NElement> merged = new LinkedHashMap<>();
        merged.put("provider", NElement.ofString("gemini"));
        merged.put("url", NElement.ofString("http://personal"));
        merged.put("apiKey", NElement.ofString("sk-from-project"));
        registrations.put("shared", NaruModelRegistration.of("shared", merged));
        origins.put("shared", new NaruRegistrationOrigin("shared", Map.of(
                "provider", NaruRegistrationScope.USER,
                "url", NaruRegistrationScope.USER,
                "apiKey", NaruRegistrationScope.PROJECT)));

        NaruModelRegistrationRules.Outcome out = NaruModelRegistrationRules.apply(session, "shared",
                Map.of("temperature", NElement.of(0.7f)), true, NaruRegistrationScope.USER);

        Assertions.assertTrue(out.isOk(), out.problem());
        Assertions.assertEquals("http://personal", out.registration().stringValue("url").get());
        // saving the personal registration must not adopt the project's credential
        Assertions.assertFalse(out.registration().param("apiKey").isPresent(),
                "a user-scope write copied the project's secret into the personal file");
    }

    @Test
    public void aProjectOverrideOfAPersonalRegistrationCarriesOnlyItsIdentity() {
        Map<String, NElement> merged = new LinkedHashMap<>();
        merged.put("provider", NElement.ofString("gemini"));
        merged.put("url", NElement.ofString("http://personal"));
        registrations.put("personal", NaruModelRegistration.of("personal", merged));
        origins.put("personal", NaruRegistrationOrigin.of("personal", NaruRegistrationScope.USER,
                merged.keySet()));

        NaruModelRegistrationRules.Outcome out = NaruModelRegistrationRules.apply(session, "personal",
                Map.of("apiKey", NElement.ofString("sk-team")), true, NaruRegistrationScope.PROJECT);

        Assertions.assertTrue(out.isOk(), out.problem());
        Assertions.assertEquals("gemini", out.registration().provider());
        // the identity travels so the two files describe one endpoint; the personal url stays put
        Assertions.assertFalse(out.registration().param("url").isPresent(),
                "a project override copied the personal endpoint into the project file");
        Assertions.assertEquals("sk-team", out.registration().stringValue("apiKey").get());
    }

    @Test
    public void aStoreThatReportsNoOriginsMergesAsOneLayer() {
        registrations.put("solo", NaruModelRegistration.of("solo", Map.of(
                "provider", NElement.ofString("gemini"), "url", NElement.ofString("http://one"))));
        NaruModelRegistrationRules.Outcome out = NaruModelRegistrationRules.apply(session, "solo",
                Map.of("temperature", NElement.of(0.4f)), true, NaruRegistrationScope.PROJECT);
        Assertions.assertTrue(out.isOk(), out.problem());
        Assertions.assertEquals("http://one", out.registration().stringValue("url").get());
    }

    @Test
    public void providerMenuForAFormListsTypesNotInstances() {
        registrations.put("personal", NaruModelRegistration.of("personal", "gemini"));
        List<String> types = NaruModelRegistrationRules.providerTypes(session);
        Assertions.assertEquals(List.of("custom", "gemini", "openai", "stub"), types);
        Assertions.assertTrue(NaruModelRegistrationRules.supportedProtocols(session, "gemini")
                .containsAll(Set.of("openai", "gemini")));
        Assertions.assertTrue(NaruModelRegistrationRules.supportedProtocols(session, "nope").isEmpty());
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    /**
     * A provider with nothing behind it but its identity, its default wire and the wire
     * shapes it honours — the three things the rules read.
     */
    private static final class FakeProvider implements NaruModelProvider {
        private final String type;
        private final String defaultProtocol;
        private final Set<String> supported;
        private final Map<String, String> params = new LinkedHashMap<>();

        FakeProvider(String type, String defaultProtocol, Set<String> supported) {
            this.type = type;
            this.defaultProtocol = defaultProtocol;
            this.supported = supported;
        }

        @Override public String name() { return type; }
        @Override public String type() { return type; }
        @Override public String defaultProtocol() { return defaultProtocol; }
        @Override public Set<String> supportedProtocols() { return supported; }
        @Override public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig m, NaruSession s) { return NOptional.ofEmpty(); }
        @Override public NOptional<String> apiKey(NaruSession s) { return NOptional.ofEmpty(); }
        @Override public List<String> findModelIds(NaruSession s) { return List.of(); }
        @Override public void setParam(String n, String v) { params.put(n, v); }
        @Override public NOptional<String> getParam(String n) { return NOptional.ofNullable(params.get(n)); }
        @Override public Set<String> getParamNames() { return params.keySet(); }
        @Override public boolean isEnabled() { return true; }
        @Override public void setEnabled(boolean e) { }
    }

    private static NaruSession fakeSession(Map<String, NaruModelProvider> builtins,
                                           Map<String, NaruModelRegistration> registrations,
                                           Map<String, NaruRegistrationOrigin> origins) {
        NaruRegistry registry = (NaruRegistry) Proxy.newProxyInstance(
                NaruSession.class.getClassLoader(), new Class<?>[]{NaruRegistry.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "provider": {
                            String id = args[0] == null ? null : String.valueOf(args[0]).trim().toLowerCase();
                            return NOptional.ofNullable(id == null ? null : builtins.get(id));
                        }
                        case "modelProviders":
                            return new LinkedHashMap<>(builtins);
                        default:
                            return null;
                    }
                });
        return (NaruSession) Proxy.newProxyInstance(
                NaruSession.class.getClassLoader(), new Class<?>[]{NaruSession.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "registry":
                            return registry;
                        case "registrations":
                            return new LinkedHashMap<>(registrations);
                        case "registrationOrigin":
                            return NOptional.ofNullable(origins.get(String.valueOf(args[0])));
                        default:
                            return null;
                    }
                });
    }
}
