package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.ext.models.anthropic.NaruModelProtocolAnthropicCompat;
import net.thevpc.naru.ext.models.custom.NaruCustomProvider;
import net.thevpc.naru.ext.models.openapi.NaruModelProtocolOpenAICompat;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;

/**
 * Config-driven provider tests.
 *
 * <p>Endpoints are configured purely through agent env (proxy-based mock session).
 * Reachability probing is disabled for the mapping/list tests ({@code probe=false})
 * and exercised explicitly against a never-listening localhost port for the
 * availability/exclusion tests.
 */
public class NaruCustomProviderTest {

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
    public void testFindModelIdsWithProbeDisabled() {
        NaruCustomProvider provider = new NaruCustomProvider();
        Map<String, String> env = new HashMap<>();
        env.put("custom.endpoints", "endpointA");
        env.put("custom.endpoints.endpointA.url", "http://127.0.0.1:9");
        env.put("custom.endpoints.endpointA.models", "model-a,model-b");
        env.put("custom.endpoints.endpointA.probe", "false");
        NaruSession session = createMockSession(env);

        Assertions.assertEquals(List.of("endpointA/model-a", "endpointA/model-b"),
                provider.findModelIds(session));
    }

    @Test
    public void testIsAvailableWithProbeDisabled() {
        NaruCustomProvider provider = new NaruCustomProvider();
        Map<String, String> env = new HashMap<>();
        env.put("custom.endpoints", "endpointA");
        env.put("custom.endpoints.endpointA.url", "http://127.0.0.1:9");
        env.put("custom.endpoints.endpointA.probe", "false");
        Assertions.assertTrue(provider.isAvailable(createMockSession(env)));
    }

    @Test
    public void testIsAvailableTrueWhenNoEndpoints() {
        NaruCustomProvider provider = new NaruCustomProvider();
        Assertions.assertTrue(provider.isAvailable(createMockSession(Collections.emptyMap())));
    }

    @Test
    public void testGetProtocolTypeDefaultOpenApi() {
        NaruCustomProvider provider = new NaruCustomProvider();
        Map<String, String> env = new HashMap<>();
        env.put("custom.endpoints", "endpointA");
        env.put("custom.endpoints.endpointA.url", "http://127.0.0.1:9");
        env.put("custom.endpoints.endpointA.models", "model-a");
        env.put("custom.endpoints.endpointA.probe", "false");
        NaruSession session = createMockSession(env);

        NaruModelConfig config = new NaruModelConfig("custom", "endpointA/model-a");
        NOptional<NaruModelProtocol> proto = provider.getProtocol(config, session);
        Assertions.assertTrue(proto.isPresent());
        Assertions.assertInstanceOf(NaruModelProtocolOpenAICompat.class, proto.get());
    }

    @Test
    public void testGetProtocolTypeAnthropic() {
        NaruCustomProvider provider = new NaruCustomProvider();
        Map<String, String> env = new HashMap<>();
        env.put("custom.endpoints", "endpointA");
        env.put("custom.endpoints.endpointA.url", "http://127.0.0.1:9");
        env.put("custom.endpoints.endpointA.type", "anthropic");
        env.put("custom.endpoints.endpointA.models", "model-a");
        env.put("custom.endpoints.endpointA.probe", "false");
        NaruSession session = createMockSession(env);

        NaruModelConfig config = new NaruModelConfig("custom", "endpointA/model-a");
        NOptional<NaruModelProtocol> proto = provider.getProtocol(config, session);
        Assertions.assertTrue(proto.isPresent());
        Assertions.assertInstanceOf(NaruModelProtocolAnthropicCompat.class, proto.get());
    }

    @Test
    public void testUnknownTypeFallsBackToOpenApi() {
        NaruCustomProvider provider = new NaruCustomProvider();
        Map<String, String> env = new HashMap<>();
        env.put("custom.endpoints", "endpointA");
        env.put("custom.endpoints.endpointA.url", "http://127.0.0.1:9");
        env.put("custom.endpoints.endpointA.type", "bogus");
        env.put("custom.endpoints.endpointA.models", "model-a");
        env.put("custom.endpoints.endpointA.probe", "false");
        NaruSession session = createMockSession(env);

        NaruModelConfig config = new NaruModelConfig("custom", "endpointA/model-a");
        NOptional<NaruModelProtocol> proto = provider.getProtocol(config, session);
        Assertions.assertTrue(proto.isPresent());
        Assertions.assertInstanceOf(NaruModelProtocolOpenAICompat.class, proto.get());
    }

    @Test
    public void testMissingUrlReturnsEmptyProtocol() {
        NaruCustomProvider provider = new NaruCustomProvider();
        Map<String, String> env = new HashMap<>();
        env.put("custom.endpoints", "endpointA");
        env.put("custom.endpoints.endpointA.models", "model-a");
        env.put("custom.endpoints.endpointA.probe", "false");
        NaruSession session = createMockSession(env);

        NaruModelConfig config = new NaruModelConfig("custom", "endpointA/model-a");
        Assertions.assertFalse(provider.getProtocol(config, session).isPresent());
    }

    @Test
    public void testDefaultEndpointAddressing() {
        NaruCustomProvider provider = new NaruCustomProvider();
        Map<String, String> env = new HashMap<>();
        env.put("custom.endpoints", "default");
        env.put("custom.endpoints.default.url", "http://127.0.0.1:9");
        env.put("custom.endpoints.default.models", "model-a");
        env.put("custom.endpoints.default.probe", "false");
        NaruSession session = createMockSession(env);

        Assertions.assertEquals(List.of("model-a"), provider.findModelIds(session));

        NaruModelConfig config = new NaruModelConfig("custom", "model-a");
        NOptional<NaruModelProtocol> proto = provider.getProtocol(config, session);
        Assertions.assertTrue(proto.isPresent());

        NaruModelConfig config2 = new NaruModelConfig("custom", "model-b");
        Assertions.assertTrue(provider.getProtocol(config2, session).isPresent());
    }

    // ── probe = true against an unreachable localhost port ──────────────────

    @Test
    public void testUnreachableEndpointExcludedFromListing() {
        NaruCustomProvider provider = new NaruCustomProvider();
        Map<String, String> env = new HashMap<>();
        env.put("custom.endpoints", "endpointA,endpointB");
        env.put("custom.endpoints.endpointA.url", "http://127.0.0.1:9"); // connection refused
        env.put("custom.endpoints.endpointA.models", "model-a");
        env.put("custom.endpoints.endpointA.probe", "true");
        env.put("custom.endpoints.endpointB.url", "http://127.0.0.1:9");
        env.put("custom.endpoints.endpointB.models", "model-b");
        env.put("custom.endpoints.endpointB.probe", "true");
        NaruSession session = createMockSession(env);

        Assertions.assertFalse(provider.isAvailable(session));
        Assertions.assertEquals(Collections.emptyList(), provider.findModelIds(session));
    }

    @Test
    public void testMixedAvailabilityOnlyListsReachableGroups() {
        NaruCustomProvider provider = new NaruCustomProvider();
        Map<String, String> env = new HashMap<>();
        env.put("custom.endpoints", "endpointA,endpointB");
        // endpointB opts out of probing, so the provider stays "available"
        env.put("custom.endpoints.endpointA.url", "http://127.0.0.1:9");
        env.put("custom.endpoints.endpointA.models", "model-a");
        env.put("custom.endpoints.endpointA.probe", "true");
        env.put("custom.endpoints.endpointB.url", "http://127.0.0.1:9");
        env.put("custom.endpoints.endpointB.models", "model-b");
        env.put("custom.endpoints.endpointB.probe", "false");
        NaruSession session = createMockSession(env);

        Assertions.assertTrue(provider.isAvailable(session));
        // endpointA (probe=true, unreachable) is hidden; endpointB (probe=false) is listed
        Assertions.assertEquals(List.of("endpointB/model-b"), provider.findModelIds(session));
    }

    private NaruSession createMockSession(Map<String, String> envMap) {
        NaruEnv env = new NaruEnv() {
            @Override
            public NOptional<NElement> get(String key) {
                String v = envMap.get(key);
                if (v == null) {
                    return NOptional.ofEmpty();
                }
                String lower = v.trim().toLowerCase();
                if ("true".equals(lower) || "false".equals(lower)) {
                    return NOptional.of(NElement.of(Boolean.parseBoolean(lower)));
                }
                try {
                    return NOptional.of(NElement.of(Long.parseLong(v.trim())));
                } catch (NumberFormatException ignored) {
                    return NOptional.of(NElement.of(v));
                }
            }

            @Override
            public void put(String key, NElement value, net.thevpc.naru.api.agent.NAruVisibility visibility) {
            }
        };

        NaruAgent agent = (NaruAgent) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{NaruAgent.class},
                (proxy, method, args) -> "env".equals(method.getName()) ? env : null
        );

        return (NaruSession) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{NaruSession.class},
                (proxy, method, args) -> "agent".equals(method.getName()) ? agent : null
        );
    }
}