package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.ext.models.colibri.NaruColibriProvider;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NaruColibriProviderTest {

    @BeforeAll
    public static void setUp() {
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

    @Test
    public void testDefaultModelIdsWithoutApiKey() {
        // local server: discovery never gates on the API key
        NaruColibriProvider provider = new NaruColibriProvider();
        NaruSession session = createMockSession(Collections.emptyMap());
        Assertions.assertEquals(List.of("glm-5.2-colibri"), provider.findModelIds(session));
    }

    @Test
    public void testConfiguredModelIds() {
        NaruColibriProvider provider = new NaruColibriProvider();
        Map<String, String> env = new HashMap<>();
        env.put("colibri.models", "glm-5.2-colibri, glm-4.5-colibri");
        NaruSession session = createMockSession(env);
        Assertions.assertEquals(List.of("glm-5.2-colibri", "glm-4.5-colibri"), provider.findModelIds(session));
    }

    @Test
    public void testCapabilitiesDefaults() {
        NaruColibriProvider provider = new NaruColibriProvider();
        NaruSession session = createMockSession(Collections.emptyMap());

        NaruModelConfig config = new NaruModelConfig("colibri", "glm-5.2-colibri");
        NaruModelProtocol proto = provider.getProtocol(config, session).get();
        NaruModelCapabilities caps = proto.getCapabilities();
        Assertions.assertTrue(caps.isTools());
        Assertions.assertTrue(caps.isThinking());
        Assertions.assertEquals(65536L, caps.contextLength());
    }

    @Test
    public void testGetProtocol() {
        NaruColibriProvider provider = new NaruColibriProvider();
        NaruSession session = createMockSession(Collections.emptyMap());

        NaruModelConfig config = new NaruModelConfig("colibri", "glm-5.2-colibri");
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
            public void put(String key, NElement value, net.thevpc.naru.api.agent.NAruVisibility visibility) {
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