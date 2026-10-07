package net.thevpc.naru.ext.tools.llm;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.model.AbstractNaruModelProvider;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelInfo;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruModelRegistration;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruRegistry;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.routine.NaruStmtResultType;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.cmdline.NArgCompleteCandidate;
import net.thevpc.nuts.cmdline.NArgCompletePosition;
import net.thevpc.nuts.cmdline.NArgCompleteResult;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code /model add|update|remove|registered} against an in-memory registry: the
 * registrations live in a plain map, are materialized into fake provider
 * instances the way the session does it, and no file or network is touched.
 */
public class NaruModelDirectiveRegistrationTest {

    /** provider type → enumerable models; absent = wire-like (declared only) */
    private static final Map<String, List<String>> UNIVERSE = new HashMap<>();
    /** provider type → wire shapes it speaks; absent = fixed wire shape */
    private static final Map<String, Set<String>> PROTOCOLS = new HashMap<>();

    private final Map<String, NaruModelProvider> builtins = new LinkedHashMap<>();
    private final Map<String, NaruModelProvider> instances = new LinkedHashMap<>();
    private final Map<String, NaruModelRegistration> registrations = new LinkedHashMap<>();
    private final List<NaruModelInfo> catalog = new ArrayList<>();
    private final List<NMsg> logs = new ArrayList<>();
    private final List<NaruModelKey> listed = new ArrayList<>();
    private final String[] argument = {""};
    private NaruSession session;
    private final NaruDirectiveCallContext context = buildContext();
    private final NaruModelDirective directive = new NaruModelDirective();

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
    public void reset() {
        UNIVERSE.clear();
        PROTOCOLS.clear();
        UNIVERSE.put("gemini", List.of("gem-1", "gem-2"));
        UNIVERSE.put("stub", List.of("m1", "m2"));
        UNIVERSE.put("openai", List.of("gpt-4o"));
        PROTOCOLS.put("custom", Set.of("openai", "anthropic", "gemini"));
        PROTOCOLS.put("gemini", Set.of("openai", "gemini"));
        builtins.clear();
        instances.clear();
        registrations.clear();
        catalog.clear();
        listed.clear();
        logs.clear();
        argument[0] = "";
        builtins.put("gemini", new FakeProvider("gemini", new String[]{"GEMINI_API_KEY"}));
        builtins.put("stub", new FakeProvider("stub", new String[0]));
        builtins.put("openai", new FakeProvider("openai", new String[]{"OPENAI_API_KEY"}));
        builtins.put("custom", new FakeProvider("custom", new String[0]));
    }

    // ── harness ───────────────────────────────────────────────────────────────

    private NaruDirectiveCallContext buildContext() {
        NaruEnv env = new NaruEnv() {
            @Override
            public NOptional<NElement> get(String key) {
                return NOptional.ofEmpty();
            }

            @Override
            public NOptional<NElement> get(String key, NaruVisibility visibility) {
                return NOptional.ofEmpty();
            }

            @Override
            public void put(String key, NElement value, NaruVisibility visibility) {
            }
        };
        NaruAgent agent = (NaruAgent) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{NaruAgent.class},
                (proxy, method, args) -> "env".equals(method.getName()) ? env : null);

        NaruRegistry registry = (NaruRegistry) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{NaruRegistry.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "provider": {
                            String id = args[0] == null ? null : String.valueOf(args[0]).trim().toLowerCase();
                            NaruModelProvider p = id == null ? null : instances.get(id);
                            if (p == null && id != null) {
                                p = builtins.get(id);
                            }
                            return NOptional.ofNullable(p);
                        }
                        case "modelProviders": {
                            Map<String, NaruModelProvider> all = new LinkedHashMap<>(builtins);
                            all.putAll(instances);
                            return all;
                        }
                        case "modelsKeys":
                            return computeKeys();
                        case "modelsInfos":
                            return new ArrayList<>(catalog);
                        default:
                            return null;
                    }
                });

        NaruSession session = (NaruSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{NaruSession.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "agent":
                            return agent;
                        case "registry":
                            return registry;
                        case "registrations":
                            return registrations;
                        case "putRegistration": {
                            NaruModelRegistration reg = (NaruModelRegistration) args[0];
                            registrations.put(reg.id(), reg);
                            materialize(reg);
                            return null;
                        }
                        case "removeRegistration": {
                            String id = String.valueOf(args[0]);
                            boolean was = registrations.remove(id) != null;
                            instances.remove(id.toLowerCase());
                            return was;
                        }
                        case "findModel":
                            return NOptional.ofEmpty();
                        case "setListedModels": {
                            listed.clear();
                            if (args[0] != null) {
                                listed.addAll((List<NaruModelKey>) args[0]);
                            }
                            return null;
                        }
                        case "unsetSessionEnv":
                            return null;
                        case "listedModels":
                            return new ArrayList<>(listed);
                        case "getSessionEnv":
                            return NOptional.ofEmpty();
                        default:
                            return null;
                    }
                });
        this.session = session;

        NaruTask task = (NaruTask) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{NaruTask.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "session":
                            return session;
                        case "log":
                            for (Object a : args) {
                                if (a instanceof NMsg) {
                                    logs.add((NMsg) a);
                                }
                            }
                            return null;
                        default:
                            return null;
                    }
                });

        return (NaruDirectiveCallContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{NaruDirectiveCallContext.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "task":
                            return task;
                        case "name":
                            return "model";
                        case "argument":
                            return argument[0];
                        default:
                            return null;
                    }
                });
    }

    /**
     * Materialize a registration the way {@code NaruSessionImpl.reloadRegistrations}
     * does: a fresh instance of the type, every parameter applied as text
     * (arrays comma-joined).
     */
    private void materialize(NaruModelRegistration reg) {
        NaruModelProvider base = builtins.get(reg.provider() == null ? "" : reg.provider().toLowerCase());
        if (base == null) {
            return;
        }
        NaruModelProvider instance = base.newInstance(reg.id());
        for (Map.Entry<String, NElement> e : reg.params().entrySet()) {
            String v = paramValue(e.getValue());
            if (v != null) {
                instance.setParam(e.getKey(), v);
            }
        }
        instances.put(reg.id().toLowerCase(), instance);
    }

    /** Scalars as text, arrays comma-joined — the provider param layer's shape. */
    private static String paramValue(NElement e) {
        if (e == null || e.isNull()) {
            return null;
        }
        if (e.isArray()) {
            List<String> parts = new ArrayList<>();
            for (NElement c : e.asArray().map(x -> x.children()).orElse(List.of())) {
                String s = paramValue(c);
                if (!NBlankable.isBlank(s)) {
                    parts.add(s);
                }
            }
            return String.join(",", parts);
        }
        if (e.isAnyStringOrName()) {
            return e.asStringValue().orNull();
        }
        if (e.isBoolean()) {
            return e.asBooleanValue().map(String::valueOf).orElse(null);
        }
        NOptional<Float> f = e.asFloatValue();
        if (f.isPresent()) {
            float v = f.get();
            return v == Math.rint(v) && Math.abs(v) < 1.0E15
                    ? String.valueOf((long) v) : String.valueOf(v);
        }
        return e.toString();
    }

    /**
     * Available model keys the way the registry computes them: a type enumerates
     * its universe, a registration intersects that with its model/models
     * declaration (an unpinned registration therefore lists the whole universe,
     * a pinned one only what actually exists), disabled instances are skipped.
     */
    private List<NaruModelKey> computeKeys() {
        List<NaruModelKey> keys = new ArrayList<>();
        for (Map.Entry<String, NaruModelProvider> e : builtins.entrySet()) {
            for (String m : e.getValue().findModelIds(null)) {
                keys.add(new NaruModelKey(e.getKey(), m));
            }
        }
        for (Map.Entry<String, NaruModelRegistration> e : registrations.entrySet()) {
            String id = e.getKey();
            NaruModelProvider inst = instances.get(id.toLowerCase());
            if (inst == null) {
                continue;
            }
            if (inst instanceof AbstractNaruModelProvider && !((AbstractNaruModelProvider) inst).isEnabled()) {
                continue;
            }
            List<String> declared = declaredModels(e.getValue());
            List<String> enumerated = inst.findModelIds(null);
            for (String m : enumerated) {
                if (declared == null || declared.contains(m)) {
                    keys.add(new NaruModelKey(id, m));
                }
            }
        }
        return keys;
    }

    private static List<String> declaredModels(NaruModelRegistration reg) {
        NElement m = reg.param("model").orNull();
        if (m != null && !m.isNull()) {
            return List.of(m.asStringValue().orElse("?"));
        }
        NElement ms = reg.param("models").orNull();
        if (ms == null || ms.isNull()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (ms.isArray()) {
            for (NElement c : ms.asArray().map(x -> x.children()).orElse(List.of())) {
                String s = c.asStringValue().orNull();
                if (s != null) {
                    out.add(s);
                }
            }
        } else {
            String s = ms.asStringValue().orNull();
            if (s != null) {
                for (String p : s.split(",")) {
                    if (!p.isBlank()) {
                        out.add(p.trim());
                    }
                }
            }
        }
        return out;
    }

    private NaruStmtResult add(String args) {
        return directive.executeRegistration(context, NCmdLine.of(args), "add");
    }

    private NaruStmtResult update(String args) {
        return directive.executeRegistration(context, NCmdLine.of(args), "update");
    }

    private String logged() {
        StringBuilder sb = new StringBuilder();
        for (NMsg m : logs) {
            sb.append(m.toString()).append('\n');
        }
        return sb.toString();
    }

    private static int countOccurrences(String text, String token) {
        int n = 0;
        int i = text.indexOf(token);
        while (i >= 0) {
            n++;
            i = text.indexOf(token, i + token.length());
        }
        return n;
    }

    private static NaruModelInfo info(String provider, String model) {
        return new NaruModelInfo(provider, model, new NaruModelCapabilities() {
            @Override
            public long contextLength() {
                return 32768;
            }

            @Override
            public boolean isVision() {
                return false;
            }

            @Override
            public boolean isTools() {
                return true;
            }

            @Override
            public boolean isThinking() {
                return false;
            }

            @Override
            public boolean isEmbedding() {
                return false;
            }

            @Override
            public boolean isTextOnly() {
                return true;
            }

            @Override
            public NaruCachingMode cachingMode() {
                return NaruCachingMode.NONE;
            }

            @Override
            public Set<String> keys() {
                return java.util.Collections.emptySet();
            }

            @Override
            public NElement toElement() {
                return NElement.ofObjectBuilder().build();
            }
        });
    }

    /**
     * A provider type under test: enumeration and wire protocols are properties
     * of the type (shared by every instance), as in the real providers.
     */
    public static class FakeProvider extends AbstractNaruModelProvider {
        /** no-arg constructor so {@link #newInstance(String)} can build instances */
        public FakeProvider() {
            super("stub", new String[0]);
        }

        FakeProvider(String type, String[] defaultEnvKeys) {
            super(type, defaultEnvKeys);
        }

        @Override
        public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
            return NOptional.ofNamedEmpty(NMsg.ofC("no protocol in test"));
        }

        @Override
        public List<String> findModelIds(NaruSession session) {
            List<String> u = UNIVERSE.get(type());
            if (u != null) {
                return u;
            }
            // wire-like: only what the registration itself declares
            String m = getParam("model").orNull();
            if (!NBlankable.isBlank(m)) {
                return List.of(m);
            }
            String ms = getParam("models").orNull();
            List<String> out = new ArrayList<>();
            if (!NBlankable.isBlank(ms)) {
                for (String s : ms.split(",")) {
                    if (!s.isBlank()) {
                        out.add(s.trim());
                    }
                }
            }
            return out;
        }

        @Override
        public Set<String> supportedProtocols() {
            Set<String> p = PROTOCOLS.get(type());
            return p == null ? Set.of() : p;
        }
    }

    // ── /model add ────────────────────────────────────────────────────────────

    @Test
    public void addGenericEndpointWithProtocolOnly() {
        NaruStmtResult res = add("ex --protocol=openai --url=http://x --models=a,b");
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, res.type(), () -> String.valueOf(res));

        NaruModelRegistration reg = registrations.get("ex");
        Assertions.assertNotNull(reg);
        Assertions.assertEquals("custom", reg.provider(), "no provider param defaults to the internal custom type");
        Assertions.assertTrue(reg.param("provider").isPresent() == false,
                "a generic endpoint never stores a provider param");
        Assertions.assertEquals("openai", reg.protocol().get());
        Assertions.assertEquals("custom", instances.get("ex").type(), "the instance must be typed by its provider type");
        Assertions.assertTrue(logged().contains("created (protocol=openai, generic endpoint)"),
                () -> "expected the generic created message, got: " + logged());
    }

    @Test
    public void addRequiresIdentityProviderOrProtocol() {
        NaruStmtResult res = add("plain");
        Assertions.assertEquals(NaruStmtResultType.ERROR, res.type());
        String out = logged();
        Assertions.assertTrue(out.contains("--provider=<type>"),
                "must point at the built-in form, got: " + out);
        Assertions.assertTrue(out.contains("--protocol=<wire>"),
                "must point at the generic form, got: " + out);
        Assertions.assertTrue(registrations.isEmpty());
    }

    @Test
    public void addRejectsProviderIdsThatAreNotTypes() {
        // openai is a provider type now: accepted as a named provider, and its
        // url can be re-pointed exactly like any built-in type
        NaruStmtResult ok = add("cpt --provider=openai --url=http://proxy:4000/v1");
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, ok.type(), () -> logged());
        Assertions.assertEquals("openai", registrations.get("cpt").provider());
        Assertions.assertTrue(logged().contains("created (provider=openai"),
                () -> "the created message must name the type, got: " + logged());
        Assertions.assertEquals("openai", instances.get("cpt").type());

        // the internal custom type and wire protocol ids without a provider type are rejected
        for (String forbidden : new String[]{"custom", "anthropic"}) {
            registrations.clear();
            logs.clear();
            NaruStmtResult res = add("x --provider=" + forbidden + " --url=http://x");
            String out = logged();
            Assertions.assertEquals(NaruStmtResultType.ERROR, res.type(),
                    () -> "--provider=" + forbidden + " must be rejected, got: " + out);
            Assertions.assertTrue(out.contains("--protocol=") && out.contains("--models=a,b"),
                    () -> "the rejected --provider=" + forbidden + " must point at the generic form, got: " + out);
            Assertions.assertTrue(registrations.isEmpty());
        }

        // the openapi spelling carries the rename hint (openai is now a type too)
        NaruStmtResult res = add("x --provider=openapi --url=http://x");
        String out = logged();
        Assertions.assertEquals(NaruStmtResultType.ERROR, res.type(), () -> out);
        Assertions.assertTrue(out.contains("openai") && out.contains("built-in"),
                "the hint must name the new type, got: " + out);
        Assertions.assertTrue(registrations.isEmpty());

        // 'wire' was the pre-rename spelling of the internal custom type: rejected with a hint
        registrations.clear();
        logs.clear();
        NaruStmtResult wireRes = add("x --provider=wire --url=http://x");
        String wireOut = logged();
        Assertions.assertEquals(NaruStmtResultType.ERROR, wireRes.type(), () -> wireOut);
        Assertions.assertTrue(wireOut.contains("custom"),
                "the hint must name the new internal type, got: " + wireOut);
        Assertions.assertTrue(registrations.isEmpty());
    }

    @Test
    public void addRejectsUnknownProviderTypeAndListsKnownOnes() {
        NaruStmtResult res = add("x --provider=nope");
        Assertions.assertEquals(NaruStmtResultType.ERROR, res.type());
        String out = logged();
        Assertions.assertTrue(out.contains("unknown provider type 'nope'"), () -> out);
        Assertions.assertTrue(out.contains("gemini") && out.contains("stub")
                        && out.contains("custom") && out.contains("openai"),
                "known types must be listed, got: " + out);
        Assertions.assertTrue(registrations.isEmpty());
    }

    @Test
    public void addRejectsUnknownProtocolAndListsSupportedOnes() {
        NaruStmtResult res = add("x --protocol=soap");
        Assertions.assertEquals(NaruStmtResultType.ERROR, res.type());
        String out = logged();
        Assertions.assertTrue(out.contains("unknown protocol 'soap'"), () -> out);
        Assertions.assertTrue(out.contains("openai"), "supported protocols must be listed, got: " + out);
        Assertions.assertTrue(registrations.isEmpty());
    }

    @Test
    public void addAcceptsDefaultProtocolIdempotently() {
        // naming a type's own default wire id is a no-op, never an error
        NaruStmtResult res = add("stubby --provider=stub --protocol=openai");
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, res.type(), () -> String.valueOf(res));
        NaruModelRegistration reg = registrations.get("stubby");
        Assertions.assertFalse(reg.protocol().isPresent(),
                "naming the default wire stores nothing, got: " + reg.params());

        // a shape it does not speak is rejected even though it shares the default
        NaruStmtResult other = add("stubby2 --provider=stub --protocol=gemini");
        Assertions.assertEquals(NaruStmtResultType.ERROR, other.type(), () -> logged());
        Assertions.assertTrue(logged().contains("does not support --protocol"), () -> logged());
        Assertions.assertFalse(registrations.containsKey("stubby2"));
    }

    @Test
    public void addRejectsProtocolWhenTypeHasFixedWireShape() {
        NaruStmtResult res = add("x --provider=stub --protocol=openapi");
        Assertions.assertEquals(NaruStmtResultType.ERROR, res.type());
        Assertions.assertTrue(logged().contains("does not support --protocol"), () -> logged());
        Assertions.assertTrue(registrations.isEmpty());
    }

    @Test
    public void addGenericEndpointRequiresUrlAndModels() {
        // no url — the endpoint has nothing to call
        NaruStmtResult noUrl = add("ex --protocol=anthropic");
        Assertions.assertEquals(NaruStmtResultType.ERROR, noUrl.type(), () -> logged());
        Assertions.assertTrue(logged().contains("needs a base url"), () -> logged());
        Assertions.assertTrue(registrations.isEmpty());

        // url but no model declaration — no provider class can enumerate models
        NaruStmtResult noModels = add("ex --protocol=anthropic --url=http://x");
        Assertions.assertEquals(NaruStmtResultType.ERROR, noModels.type(), () -> logged());
        Assertions.assertTrue(logged().contains("--model=<id>"), () -> logged());
        Assertions.assertTrue(logged().contains("--models=a,b"), () -> logged());
        Assertions.assertTrue(registrations.isEmpty());

        // a single pinned model satisfies the requirement
        NaruStmtResult single = add("ex --protocol=anthropic --url=http://x --model=claude-2");
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, single.type(), () -> logged());
        Assertions.assertTrue(logged().contains("generic endpoint"), () -> logged());
    }

    @Test
    public void addProviderOverrideMentionsBothKnobs() {
        NaruStmtResult res = add("hybrid --provider=gemini --protocol=gemini");
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, res.type(), () -> String.valueOf(res));
        String out = logged();
        Assertions.assertTrue(out.contains("(provider=gemini, protocol=gemini)"),
                "the created message must show both knobs, got: " + out);
    }

    @Test
    public void addRejectsIdOwnedByBuiltInProvider() {
        NaruStmtResult res = add("gemini --provider=gemini");
        Assertions.assertEquals(NaruStmtResultType.ERROR, res.type());
        String out = logged();
        Assertions.assertTrue(out.contains("already used by provider"), () -> out);
        Assertions.assertTrue(registrations.isEmpty());
    }

    @Test
    public void addRejectsIdContainingSlash() {
        NaruStmtResult res = add("a/b --provider=stub");
        Assertions.assertEquals(NaruStmtResultType.ERROR, res.type());
        Assertions.assertTrue(logged().contains("'/' separates"), () -> logged());
        Assertions.assertTrue(registrations.isEmpty());
    }

    // ── /model add on an existing id, /model update ───────────────────────────

    @Test
    public void addMergesIntoExistingRegistration() {
        Assertions.assertEquals(NaruStmtResultType.SUCCESS,
                add("personal --provider=stub --temperature=0.2 --apiKey=sk-secret1234").type());
        Assertions.assertEquals(NaruStmtResultType.SUCCESS,
                add("personal --contextLength=2000").type());

        NaruModelRegistration reg = registrations.get("personal");
        Assertions.assertEquals("stub", reg.provider(), "the merge must keep the stored provider");
        Assertions.assertEquals(0.2f, reg.floatValue("temperature").get());
        Assertions.assertEquals(2000L, reg.longValue("contextLength").get());
        Assertions.assertEquals("sk-secret1234", reg.stringValue("apiKey").get());
        Assertions.assertEquals("stub", instances.get("personal").type());
    }

    @Test
    public void updateRequiresExistingRegistration() {
        NaruStmtResult res = update("ghost --temperature=0.1");
        Assertions.assertEquals(NaruStmtResultType.ERROR, res.type());
        String out = logged();
        Assertions.assertTrue(out.contains("no registration 'ghost'"), () -> out);
        Assertions.assertTrue(out.contains("/model add"), "the error must point at the creating command, got: " + out);
    }

    @Test
    public void emptyFlagValueClearsParameter() {
        Assertions.assertEquals(NaruStmtResultType.SUCCESS,
                add("temp --provider=stub --temperature=0.2").type());
        Assertions.assertTrue(registrations.get("temp").param("temperature").isPresent());

        NaruStmtResult res = add("temp --temperature=");
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, res.type(), () -> String.valueOf(res));
        NaruModelRegistration reg = registrations.get("temp");
        Assertions.assertFalse(reg.param("temperature").isPresent(), "an empty value must clear the parameter");
        Assertions.assertEquals("stub", reg.provider(), "clearing one parameter must not drop the others");
    }

    @Test
    public void updateCannotStripTheIdentity() {
        // a provider-based registration cannot be turned generic by clearing --provider
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, add("personal --provider=gemini").type());
        NaruStmtResult stripProvider = update("personal --provider=");
        Assertions.assertEquals(NaruStmtResultType.ERROR, stripProvider.type(), () -> logged());
        Assertions.assertTrue(logged().contains("cannot clear"), () -> logged());
        Assertions.assertEquals("gemini", registrations.get("personal").provider(),
                "a rejected update must leave the registration untouched");

        // ... and a generic endpoint cannot be stripped of its only identity
        Assertions.assertEquals(NaruStmtResultType.SUCCESS,
                add("ex --protocol=openai --url=http://x --models=a,b").type());
        NaruStmtResult stripProtocol = update("ex --protocol=");
        Assertions.assertEquals(NaruStmtResultType.ERROR, stripProtocol.type(), () -> logged());
        Assertions.assertTrue(logged().contains("--protocol=<wire>"), () -> logged());
        Assertions.assertEquals("openai", registrations.get("ex").protocol().get());

        // dropping only the override of a provider-based registration is fine
        Assertions.assertEquals(NaruStmtResultType.SUCCESS,
                add("native --provider=gemini --protocol=gemini").type());
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, update("native --protocol=").type(), () -> logged());
        Assertions.assertFalse(registrations.get("native").protocol().isPresent(),
                "clearing the override falls back to the type's default wire");
    }

    // ── /model remove ─────────────────────────────────────────────────────────

    @Test
    public void removeRoundTripAndUnknownId() {
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, add("tmp --provider=stub").type());
        Assertions.assertTrue(instances.containsKey("tmp"));

        NaruStmtResult res = directive.executeRemoveRegistration(context, NCmdLine.of("tmp"));
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, res.type(), () -> String.valueOf(res));
        Assertions.assertTrue(registrations.isEmpty());
        Assertions.assertFalse(instances.containsKey("tmp"));
        Assertions.assertTrue(logged().contains("registration 'tmp' removed"), () -> logged());

        NaruStmtResult again = directive.executeRemoveRegistration(context, NCmdLine.of("tmp"));
        Assertions.assertEquals(NaruStmtResultType.ERROR, again.type());
        Assertions.assertTrue(logged().contains("no registration 'tmp'"), () -> logged());
    }

    // ── /model registered ─────────────────────────────────────────────────────

    @Test
    public void registeredMasksLiteralSecrets() {
        Assertions.assertEquals(NaruStmtResultType.SUCCESS,
                add("secret --provider=stub --apiKey=sk-secret1234").type());

        // stored verbatim ...
        Assertions.assertEquals("sk-secret1234",
                registrations.get("secret").stringValue("apiKey").get());

        NaruStmtResult res = directive.executeRegisteredList(context, NCmdLine.of(""));
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, res.type(), () -> String.valueOf(res));
        String out = logged();
        Assertions.assertTrue(out.contains("sk-***1234"), () -> "expected the masked key, got: " + out);
        Assertions.assertFalse(out.contains("sk-secret1234"), () -> "the literal must never be printed: " + out);
        Assertions.assertTrue(out.contains("models=auto"), () -> out);
    }

    @Test
    public void registeredTypeColumnShowsProtocolForGenericEntries() {
        Assertions.assertEquals(NaruStmtResultType.SUCCESS,
                add("ex --protocol=openai --url=http://x --models=a,b").type());
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, add("personal --provider=gemini").type());

        NaruStmtResult res = directive.executeRegisteredList(context, NCmdLine.of(""));
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, res.type(), () -> String.valueOf(res));
        String out = logged();
        Assertions.assertTrue(java.util.regex.Pattern.compile("ex[ ]+openai").matcher(out).find(),
                "a generic entry's type column must name its wire protocol, got: " + out);
        Assertions.assertTrue(java.util.regex.Pattern.compile("personal[ ]+gemini").matcher(out).find(),
                "a provider entry's type column must name its type, got: " + out);
    }

    // ── /model use with a registration id ─────────────────────────────────────

    @Test
    public void useUnpinnedRegistrationListsAvailableModels() {
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, add("two --provider=stub").type());

        argument[0] = "use two";
        NaruStmtResult res = directive.execute(context);
        Assertions.assertEquals(NaruStmtResultType.ERROR, res.type(), () -> String.valueOf(res));
        String out = logged();
        Assertions.assertTrue(out.contains("does not pin a single model"), () -> out);
        Assertions.assertTrue(out.contains("two/m1") && out.contains("two/m2"),
                "the available models must be listed, got: " + out);
    }

    @Test
    public void useRegistrationWithoutAvailableModelSaysWhy() {
        // pinned to a model the provider does not have: nothing is available
        Assertions.assertEquals(NaruStmtResultType.SUCCESS,
                add("ghost --provider=stub --model=ghost").type());

        argument[0] = "use ghost";
        NaruStmtResult res = directive.execute(context);
        Assertions.assertEquals(NaruStmtResultType.ERROR, res.type(), () -> String.valueOf(res));
        String out = logged();
        Assertions.assertTrue(out.contains("has no available model"), () -> out);
        Assertions.assertTrue(out.contains("api key") && out.contains("probe"),
                "the error must point at configuration, got: " + out);
    }

    // ── /model list ───────────────────────────────────────────────────────────

    @Test
    public void listAnnotatesRegistrationRowsAndFiltersByType() {
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, add("personal --provider=gemini").type());
        catalog.add(info("personal", "gem-1"));
        catalog.add(info("gemini", "gem-2"));
        catalog.add(info("ollama", "qwen"));

        NaruStmtResult res = directive.executeList(context, NCmdLine.of("--provider=gemini"));
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, res.type(), () -> String.valueOf(res));

        // rows render with NText style markers (##{p9:personal}##…), so match on the
        // fragments: the registration row must be the annotated one
        String out = logged();
        Assertions.assertTrue(out.contains("gem-1"), () -> "registration row missing, got: " + out);
        Assertions.assertTrue(out.contains("gem-2"), () -> "base row missing, got: " + out);
        Assertions.assertTrue(java.util.regex.Pattern.compile("gem-1[^\\n]*\\(gemini\\)").matcher(out).find(),
                () -> "the registration row must be annotated with its type, got: " + out);
        Assertions.assertEquals(1, countOccurrences(out, "(gemini)"),
                () -> "only the registration row gets a type annotation, got: " + out);
        Assertions.assertFalse(out.contains("qwen"),
                "--provider must match by type and exclude other types, got: " + out);
    }

    // ── autocomplete ─────────────────────────────────────────────────────────

    private List<String> complete(String... words) {
        NCmdLine cmdLine = NCmdLine.of(words);
        int last = words.length - 1;
        NArgCompletePosition pos = NArgCompletePosition.of(last, words[last].length(), 0);
        NArgCompleteResult result = directive.resolveCandidates(cmdLine.completePosition(pos), pos, session);
        List<String> out = new ArrayList<>();
        for (NArgCompleteCandidate c : result.candidates()) {
            out.add(c.value());
        }
        return out;
    }

    @Test
    public void subcommandsAndBareReferencesCompleteAtPositionOne() {
        List<String> c = complete("/model", "");
        Assertions.assertTrue(c.contains("add"), () -> "subcommand missing, got: " + c);
        Assertions.assertTrue(c.contains("use"), () -> "subcommand missing, got: " + c);
        Assertions.assertTrue(c.contains("registered"), () -> "subcommand missing, got: " + c);
        Assertions.assertTrue(c.contains("gemini/gem-1"),
                "the bare form must also accept a model reference, got: " + c);
    }

    @Test
    public void partialSubcommandIsNarrowed() {
        List<String> c = complete("/model", "reg");
        Assertions.assertTrue(c.contains("registered"), () -> "got: " + c);
        for (String v : c) {
            Assertions.assertTrue(v.startsWith("reg"),
                    "candidate " + v + " does not extend 'reg': " + c);
        }
    }

    @Test
    public void providerValueCompletesAfterEquals() {
        List<String> c = complete("/model", "add", "x", "--provider=");
        Assertions.assertTrue(c.contains("--provider=gemini"), () -> "got: " + c);
        Assertions.assertTrue(c.contains("--provider=stub"), () -> "got: " + c);
        Assertions.assertTrue(c.contains("--provider=openai"), () -> "got: " + c);
        Assertions.assertFalse(c.contains("--provider=custom"),
                "the internal custom type must not be offered, got: " + c);
        Assertions.assertFalse(c.contains("--provider=openapi"),
                "the gone wire shorthands must not be offered, got: " + c);

        List<String> partial = complete("/model", "add", "x", "--provider=ge");
        Assertions.assertEquals(List.of("--provider=gemini"), partial,
                "a partial value must narrow the candidates");
    }

    @Test
    public void protocolValueCompletesFromTheChosenProviderShapes() {
        List<String> c = complete("/model", "add", "x", "--provider=custom", "--protocol=");
        Assertions.assertTrue(c.contains("--protocol=openai"), () -> "got: " + c);
        Assertions.assertTrue(c.contains("--protocol=anthropic"), () -> "got: " + c);
        Assertions.assertTrue(c.contains("--protocol=gemini"), () -> "got: " + c);
        Assertions.assertFalse(c.contains("--protocol=openapi"),
                "the old spelling must be gone, got: " + c);

        // a gemini registration can also pick its native wire next to the default
        List<String> gem = complete("/model", "add", "x", "--provider=gemini", "--protocol=");
        Assertions.assertTrue(gem.contains("--protocol=openai"), () -> "got: " + gem);
        Assertions.assertTrue(gem.contains("--protocol=gemini"), () -> "got: " + gem);

        // a type with a fixed wire shape has no protocols to offer
        List<String> none = complete("/model", "add", "x", "--provider=stub", "--protocol=");
        Assertions.assertTrue(none.isEmpty(),
                "a fixed-shape type must offer no protocols, got: " + none);
    }

    @Test
    public void modelsValueCompletesForTheChosenProvider() {
        List<String> c = complete("/model", "add", "x", "--provider=gemini", "--models=");
        Assertions.assertTrue(c.contains("--models=gem-1"), () -> "got: " + c);
        Assertions.assertTrue(c.contains("--models=gem-2"), () -> "got: " + c);
        Assertions.assertFalse(c.contains("--models=m1"),
                "gemini must not offer stub's models, got: " + c);

        // before a provider is chosen every catalog model is fair game
        List<String> all = complete("/model", "add", "x", "--models=");
        Assertions.assertTrue(all.contains("--models=m1"), () -> "got: " + all);
    }

    @Test
    public void optionNamesComplete() {
        List<String> c = complete("/model", "add", "x", "--pro");
        Assertions.assertTrue(c.contains("--provider="), () -> "got: " + c);
        Assertions.assertTrue(c.contains("--protocol="), () -> "got: " + c);
        Assertions.assertTrue(c.contains("--probe="), () -> "got: " + c);
    }

    @Test
    public void booleanFlagsCompleteTrueAndFalse() {
        List<String> c = complete("/model", "add", "x", "--probe=");
        Assertions.assertTrue(c.contains("--probe=true"), () -> "got: " + c);
        Assertions.assertTrue(c.contains("--probe=false"), () -> "got: " + c);
    }

    @Test
    public void addUpdateRemoveCompleteExistingIds() {
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, add("personal --provider=gemini").type());

        Assertions.assertTrue(complete("/model", "update", "").contains("personal"),
                "update must offer registration ids");
        Assertions.assertTrue(complete("/model", "remove", "pers").contains("personal"),
                "remove must offer matching registration ids");
        Assertions.assertTrue(complete("/model", "add", "").contains("personal"),
                "add merges into an existing id, so it must be offered too");
    }

    @Test
    public void useCompletesRegistrationsAndKeys() {
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, add("personal --provider=gemini").type());

        List<String> c = complete("/model", "use", "");
        Assertions.assertTrue(c.contains("personal"), () -> "registration id missing, got: " + c);
        Assertions.assertTrue(c.contains("gemini/gem-1"), () -> "key missing, got: " + c);
        Assertions.assertTrue(c.contains("gemini/gem-2"), () -> "key missing, got: " + c);
    }

    @Test
    public void listCompletesTheProviderFilterAndKeywords() {
        List<String> c = complete("/model", "list", "--provider=");
        Assertions.assertTrue(c.contains("--provider=gemini"), () -> "got: " + c);
        Assertions.assertTrue(c.contains("--provider=stub"), () -> "got: " + c);

        List<String> free = complete("/model", "list", "gem");
        Assertions.assertTrue(free.contains("gemini"), () -> "a provider word must complete, got: " + free);
        Assertions.assertTrue(free.contains("gemini/gem-1"), () -> "a model key must complete, got: " + free);
    }

    @Test
    public void bareIndexCompletesAfterAListing() {
        catalog.add(info("gemini", "gem-2"));
        NaruStmtResult res = directive.executeList(context, NCmdLine.of(""));
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, res.type(), () -> String.valueOf(res));

        Assertions.assertTrue(complete("/model", "").contains("1"),
                "the index the listing printed must complete the bare form");
    }

    @Test
    public void unknownSubcommandAndDeepUnrelatedArgsOfferNothing() {
        Assertions.assertTrue(complete("/model", "bogus", "").isEmpty());
    }
}
