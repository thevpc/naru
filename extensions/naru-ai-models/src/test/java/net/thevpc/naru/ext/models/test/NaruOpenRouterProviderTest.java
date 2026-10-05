package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.ext.models.openrouter.NaruOpenRouterProvider;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
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
        NaruOpenRouterProvider provider = new NaruOpenRouterProvider();

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

        NaruAgent agent = (NaruAgent) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{NaruAgent.class},
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
