package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.ext.models.openrouter.NaruOpenRouterProvider;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;

public class NaruOpenRouterProviderTest {

    @BeforeAll
    public static void setUp() {
        Nuts.require();
    }

    @Test
    public void testFindModelIdsWithoutApiKeyReturnsEmpty() {
        NaruOpenRouterProvider provider = new KeylessOpenRouterProvider();

        NaruSession session = createMockSession(Collections.emptyMap());

        List<String> models = provider.findModelIds(session);
        Assertions.assertNotNull(models);
        Assertions.assertTrue(models.isEmpty(), "OpenRouter should return empty list when no API key is provided");
    }

    @Test
    public void testGetProtocol() {
        NaruOpenRouterProvider provider = new NaruOpenRouterProvider();
        NaruSession session = createMockSession(Collections.emptyMap());

        NaruModelConfig config = new NaruModelConfig("openrouter", "qwen/qwen-2.5-coder-32b-instruct:free");
        NOptional<NaruModelProtocol> proto = provider.getProtocol(config, session);
        Assertions.assertTrue(proto.isPresent());
        Assertions.assertNotNull(proto.get().getCapabilities());
    }

    @Test
    public void testGetProtocolToolsCapabilityCanBeOverriddenDown() {
        // OpenRouter claims tools=true for everything (the catalogue says the
        // endpoint accepts tool parameters, not that the model honours them).
        // The registered <id>.tools=false must override that so the model is
        // routed through tool-call emulation instead of native calls.
        NaruOpenRouterProvider provider = new NaruOpenRouterProvider();
        Map<String, String> env = new HashMap<>();
        env.put("openrouter.tools", "false");
        NaruSession session = createMockSession(env);

        NaruModelConfig config = new NaruModelConfig("openrouter", "qwen/qwen-2.5-coder-32b-instruct:free");
        NaruModelCapabilities capabilities = provider.getProtocol(config, session).get().getCapabilities();
        Assertions.assertFalse(capabilities.isTools(),
                "an explicit tools=false registration must beat the catalogue default");
    }

    @Test
    public void testGetProtocolToolsCapabilityDefaultRemainsTrue() {
        NaruOpenRouterProvider provider = new NaruOpenRouterProvider();
        NaruSession session = createMockSession(Collections.emptyMap());

        NaruModelConfig config = new NaruModelConfig("openrouter", "qwen/qwen-2.5-coder-32b-instruct:free");
        NaruModelCapabilities capabilities = provider.getProtocol(config, session).get().getCapabilities();
        Assertions.assertTrue(capabilities.isTools(), "without an override the catalogue default applies");
    }

    /**
     * An OpenRouter provider that resolves no key at all, so the "no api key"
     * branch is tested independently of any ambient {@code OPENROUTER_API_KEY}.
     */
    private static class KeylessOpenRouterProvider extends NaruOpenRouterProvider {
        @Override
        public NOptional<String> apiKey(NaruSession session) {
            return NOptional.ofEmpty();
        }
    }

    private NaruSession createMockSession(Map<String, String> envMap) {
        NaruEnv env = new NaruEnv() {
            @Override
            public NOptional<NElement> get(String key) {
                String v = envMap.get(key);
                if (v == null) {
                    return NOptional.ofEmpty();
                }
                return NOptional.of(NElement.of(v));
            }

            @Override
            public NOptional<NElement> get(String key, NaruVisibility visibility) {
                return get(key);
            }

            @Override
            public void put(String key, NElement value, NaruVisibility visibility) {
            }
        };

        NaruWorkspace agent = (NaruWorkspace) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{NaruWorkspace.class},
                (proxy, method, args) -> {
                    if ("env".equals(method.getName())) {
                        return env;
                    }
                    return null;
                }
        );

        return (NaruSession) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{NaruSession.class},
                (proxy, method, args) -> {
                    if ("agent".equals(method.getName())) {
                        return agent;
                    }
                    return null;
                }
        );
    }
}
