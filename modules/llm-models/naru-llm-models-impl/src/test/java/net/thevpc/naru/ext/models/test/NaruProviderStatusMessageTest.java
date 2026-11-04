package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.ext.models.ollama.NaruOllamaProvider;
import net.thevpc.naru.ext.models.openai.NaruOpenAiProvider;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * Why a provider contributes nothing to the catalog.
 *
 * <p>The catalog omits a provider whose listing is empty, which is correct but silent: a user
 * whose remote models are gone cannot tell "no key" from "not registered" from "endpoint
 * unreachable". {@link NaruModelProvider#statusMessage(NaruSession)} is the seam the desktop
 * model picker reads to name that reason, so it is asserted here on the real providers rather
 * than only through the picker's HTML.
 */
public class NaruProviderStatusMessageTest {

    @BeforeAll
    public static void setUp() {
        Nuts.require();
    }

    @Test
    public void aProviderWithNothingToReportIsSilent() {
        // the default is null: a provider that lists models (or has no explanation to give) adds
        // no noise to the summary
        NaruOllamaProvider p = new NaruOllamaProvider();
        Assertions.assertNull(p.statusMessage(session(Map.of())));
    }

    @Test
    public void aCloudProviderWithoutItsKeySaysSo() {
        // openai's own key is not in this session's env, so the instance the picker would show
        // as "missing" explains itself instead
        NaruOpenAiProvider p = new NaruOpenAiProvider();
        String why = p.statusMessage(session(Map.of()));
        Assertions.assertNotNull(why, "a keyless cloud provider must explain its absence");
        Assertions.assertTrue(why.toLowerCase().contains("key"), why);
    }

    @Test
    public void theInstanceKeyWinsOverTheDefaultEnvKey() {
        // §6: the instance's own <name>.apiKey beats OPENAI_API_KEY, so a keyed instance reports
        // no reason even when the process-wide env has none
        NaruOpenAiProvider p = (NaruOpenAiProvider) new NaruOpenAiProvider().newInstance("personal");
        String why = p.statusMessage(session(Map.of("personal.apiKey", "sk-test")));
        Assertions.assertNull(why, "a keyed instance has nothing to explain");
    }

    /** A session whose env reads exactly the given map; no registry, no agent. */
    private static NaruSession session(Map<String, String> envMap) {
        NaruSession[] holder = new NaruSession[1];
        NaruWorkspace agent = (NaruWorkspace) Proxy.newProxyInstance(
                NaruProviderStatusMessageTest.class.getClassLoader(),
                new Class<?>[]{NaruWorkspace.class},
                (proxy, method, args) -> {
                    if ("env".equals(method.getName())) {
                        return (net.thevpc.naru.api.agent.NaruEnv) new net.thevpc.naru.api.agent.NaruEnv() {
                            @Override
                            public NOptional<NElement> get(String key) {
                                String v = envMap.get(key);
                                return v == null ? NOptional.ofEmpty() : NOptional.of(NElement.of(v));
                            }

                            @Override
                            public NOptional<NElement> get(String key, net.thevpc.naru.api.agent.NaruVisibility visibility) {
                                return get(key);
                            }

                            @Override
                            public void put(String key, NElement value, net.thevpc.naru.api.agent.NaruVisibility visibility) {
                            }
                        };
                    }
                    return null;
                });
        holder[0] = (NaruSession) Proxy.newProxyInstance(
                NaruProviderStatusMessageTest.class.getClassLoader(),
                new Class<?>[]{NaruSession.class},
                (proxy, method, args) -> {
                    if ("agent".equals(method.getName())) {
                        return agent;
                    }
                    if ("getSessionEnv".equals(method.getName())) {
                        return NOptional.ofEmpty();
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    return defaultValue(method.getReturnType());
                });
        return holder[0];
    }

    /** A proxy must answer a primitive-returning method with a primitive, not null. */
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) return false;
        if (type == char.class) return (char) 0;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }
}
