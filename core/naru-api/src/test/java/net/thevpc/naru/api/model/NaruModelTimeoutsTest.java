package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The rule that decides a default read timeout, and the scoped lookup that lets a
 * user override it.
 *
 * <p>Both halves are pure functions here, deliberately: a locality default (120s
 * remote, 300s local+GPU, 600s local+CPU) and a family-then-global lookup through
 * the task/session/agent/config/system-property chain. Everything else in the
 * timeout feature is wiring around these two, so pinning them pins the feature.
 */
public class NaruModelTimeoutsTest {

    @BeforeAll
    public static void setUp() {
        // NOptional.ofEmpty() resolves through the Nuts workspace
        Nuts.require();
    }

    // ── the default rule ─────────────────────────────────────────────────────

    @Test
    public void remoteIsAlways120s() {
        Assertions.assertEquals(120, NaruModelTimeouts.defaultReadTimeout(false, false).toSeconds());
        Assertions.assertEquals(120, NaruModelTimeouts.defaultReadTimeout(false, true).toSeconds(),
                "a remote endpoint never gets the local allowance, GPU or not");
    }

    @Test
    public void localGets300sWithAGpuAnd600sWithout() {
        Assertions.assertEquals(300, NaruModelTimeouts.defaultReadTimeout(true, true).toSeconds());
        Assertions.assertEquals(600, NaruModelTimeouts.defaultReadTimeout(true, false).toSeconds(),
                "CPU prefill is the slowest case and gets the longest allowance");
    }

    // ── locality ─────────────────────────────────────────────────────────────

    @Test
    public void localUrlsAreRecognised() {
        for (String url : new String[]{
                "http://localhost:11434",
                "http://localhost",
                "localhost:11434",
                "http://127.0.0.1:8080/v1",
                "https://127.0.0.1",
                "127.0.0.1",
                "http://[::1]:11434",
                "[::1]:11434",
                "http://user:pass@localhost:11434/",
                "http://0.0.0.0:8080"}) {
            Assertions.assertTrue(NaruModelTimeouts.isLocalUrl(url), url);
        }
    }

    @Test
    public void remoteUrlsAreNotLocal() {
        for (String url : new String[]{
                "https://api.openai.com/v1",
                "https://generativelanguage.googleapis.com",
                "http://192.168.1.10:11434",
                "http://my-ollama.example.com",
                null,
                "",
                "   "}) {
            Assertions.assertFalse(NaruModelTimeouts.isLocalUrl(url), String.valueOf(url));
        }
    }

    @Test
    public void modelFamilyIsTheLeadingLetterRun() {
        Assertions.assertEquals("qwen", NaruModelTimeouts.modelFamily("qwen3:32b"));
        Assertions.assertEquals("gpt", NaruModelTimeouts.modelFamily("gpt-4o"));
        Assertions.assertEquals("gemini", NaruModelTimeouts.modelFamily("gemini-2.5-pro"));
        Assertions.assertEquals("llama", NaruModelTimeouts.modelFamily("Llama3.1:8b"));
        Assertions.assertNull(NaruModelTimeouts.modelFamily("1234"),
                "an id that starts with a digit has no family to index on");
        Assertions.assertNull(NaruModelTimeouts.modelFamily(null));
    }

    // ── scoped resolution ────────────────────────────────────────────────────

    @Test
    public void taskEnvResolves() {
        Scopes s = new Scopes();
        s.task.put("model.readTimeout", "45s");
        Assertions.assertEquals(45, find(s, "qwen3:32b", "readTimeout"));
    }

    @Test
    public void sessionEnvIsConsultedWhenTaskEnvIsEmpty() {
        Scopes s = new Scopes();
        s.session.put("model.readTimeout", "90s");
        Assertions.assertEquals(90, find(s, "qwen3:32b", "readTimeout"));
    }

    @Test
    public void agentEnvIsConsultedWhenSessionEnvIsEmpty() {
        Scopes s = new Scopes();
        s.agent.put("model.readTimeout", NElement.ofString("120s"));
        Assertions.assertEquals(120, find(s, "qwen3:32b", "readTimeout"));
    }

    @Test
    public void privateConfigWinsOverPublic() {
        Scopes s = new Scopes();
        s.publicConfig.put("model.readTimeout", NElement.ofString("30s"));
        s.privateConfig.put("model.readTimeout", NElement.ofString("60s"));
        Assertions.assertEquals(60, find(s, "qwen3:32b", "readTimeout"));
    }

    @Test
    public void aNarrowerScopeBeatsAWiderOne() {
        Scopes s = new Scopes();
        s.task.put("model.readTimeout", "45s");
        s.session.put("model.readTimeout", "90s");
        s.privateConfig.put("model.readTimeout", NElement.ofString("30s"));
        Assertions.assertEquals(45, find(s, "qwen3:32b", "readTimeout"));
    }

    @Test
    public void systemPropertyIsTheLastResort() {
        System.setProperty("model.readTimeout", "77s");
        try {
            Assertions.assertEquals(77, find(new Scopes(), "qwen3:32b", "readTimeout"));
        } finally {
            System.clearProperty("model.readTimeout");
        }
    }

    @Test
    public void familyIndexedKeyBeatsTheGlobalOne() {
        Scopes s = new Scopes();
        s.task.put("model.readTimeout", "90s");
        Assertions.assertEquals(90, find(s, "qwen3:32b", "readTimeout"));
        s.task.put("model.qwen.readTimeout", "45s");
        Assertions.assertEquals(45, find(s, "qwen3:32b", "readTimeout"),
                "the family is the more specific instruction and wins over the global key");
    }

    @Test
    public void aFamilyIndexedValueBeatsAGlobalValueInANarrowerScope() {
        // global in the narrowest store (task), family in the widest (system property)
        System.setProperty("model.qwen.readTimeout", "45s");
        try {
            Scopes s = new Scopes();
            s.task.put("model.readTimeout", "90s");
            Assertions.assertEquals(45, find(s, "qwen3:32b", "readTimeout"));
        } finally {
            System.clearProperty("model.qwen.readTimeout");
        }
    }

    @Test
    public void aFamilyIndexedValueOnlyAppliesToThatFamily() {
        Scopes s = new Scopes();
        s.task.put("model.qwen.readTimeout", "45s");
        Assertions.assertEquals(45, find(s, "qwen3:32b", "readTimeout"));
        Assertions.assertFalse(NaruModelTimeouts
                        .findScoped(task(s), new NaruModelConfig("endpointA", "gpt-4o"), "readTimeout")
                        .isPresent(),
                "another family must not inherit qwen's override");
    }

    @Test
    public void aNullModelOnlySeesTheGlobalKey() {
        Scopes s = new Scopes();
        s.task.put("model.readTimeout", "45s");
        Assertions.assertEquals(45, NaruModelTimeouts
                .findScoped(task(s), null, "readTimeout").get().toSeconds());
    }

    @Test
    public void nothingSetResolvesEmpty() {
        Assertions.assertFalse(NaruModelTimeouts
                .findScoped(task(new Scopes()), new NaruModelConfig("endpointA", "qwen3:32b"), "readTimeout")
                .isPresent());
    }

    // ── mocks ────────────────────────────────────────────────────────────────

    private static long find(Scopes s, String model, String key) {
        return NaruModelTimeouts.findScoped(task(s), new NaruModelConfig("endpointA", model), key)
                .get().toSeconds();
    }

    private static final class Scopes {
        final Map<String, Object> task = new HashMap<>();
        final Map<String, Object> session = new HashMap<>();
        final Map<String, NElement> agent = new HashMap<>();
        final Map<String, NElement> privateConfig = new HashMap<>();
        final Map<String, NElement> publicConfig = new HashMap<>();
    }

    private static NaruTask task(Scopes s) {
        NaruSession session = session(s);
        return (NaruTask) Proxy.newProxyInstance(
                NaruModelTimeoutsTest.class.getClassLoader(),
                new Class<?>[]{NaruTask.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getTaskEnv":
                            if (args != null && args.length == 2) {
                                Object v = s.task.get((String) args[0]);
                                return v == null ? NOptional.ofEmpty() : NOptional.of(v);
                            }
                            return s.task;
                        case "session":
                            return session;
                        case "findToolTagNames":
                            return Set.of();
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static NaruSession session(Scopes s) {
        NaruAgent agent = agent(s.agent);
        return (NaruSession) Proxy.newProxyInstance(
                NaruModelTimeoutsTest.class.getClassLoader(),
                new Class<?>[]{NaruSession.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getSessionEnv": {
                            Object v = s.session.get((String) args[0]);
                            return v == null ? NOptional.ofEmpty() : NOptional.of(v);
                        }
                        case "agent":
                            return agent;
                        case "getProjectEnv": {
                            String key = (String) args[0];
                            if (args.length > 1) {
                                NaruVisibility vis = (NaruVisibility) args[1];
                                return element((vis == NaruVisibility.PRIVATE ? s.privateConfig : s.publicConfig).get(key));
                            }
                            NElement v = s.privateConfig.get(key);
                            return element(v != null ? v : s.publicConfig.get(key));
                        }
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static NaruAgent agent(Map<String, NElement> agentEnv) {
        NaruEnv env = (NaruEnv) Proxy.newProxyInstance(
                NaruModelTimeoutsTest.class.getClassLoader(),
                new Class<?>[]{NaruEnv.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "get":
                            return element(agentEnv.get((String) args[0]));
                        case "put":
                            return null;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
        return (NaruAgent) Proxy.newProxyInstance(
                NaruModelTimeoutsTest.class.getClassLoader(),
                new Class<?>[]{NaruAgent.class},
                (proxy, method, args) -> "agentEnv".equals(method.getName())
                        ? env : defaultValue(method.getReturnType()));
    }

    private static NOptional<NElement> element(NElement v) {
        return v == null ? NOptional.ofEmpty() : NOptional.of(v);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
