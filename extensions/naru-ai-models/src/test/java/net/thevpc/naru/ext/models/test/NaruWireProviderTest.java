package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.ext.models.anthropic.NaruModelProtocolAnthropicCompat;
import net.thevpc.naru.ext.models.openapi.NaruModelProtocolOpenAICompat;
import net.thevpc.naru.ext.models.wire.NaruWireProvider;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Wire provider tests: the registration-backed endpoint type behind
 * {@code /model add <id> --provider=wire} (and its openapi/anthropic
 * shorthands). Configuration lives in the instance's params (the registration),
 * with the {@code <instance id>.<key>} agent env as the §6 fallback.
 *
 * <p>Reachability probing is disabled for the mapping tests ({@code probe=false})
 * and exercised explicitly against a never-listening localhost port for the
 * availability tests.
 */
public class NaruWireProviderTest {

    @BeforeAll
    public static void setUp() {
        Nuts.require();
    }

    /** A fresh wire instance {@code endpointA} carrying the given params. */
    private NaruWireProvider wire(Map<String, String> params) {
        NaruWireProvider p = (NaruWireProvider) new NaruWireProvider().newInstance("endpointA");
        for (Map.Entry<String, String> e : params.entrySet()) {
            p.setParam(e.getKey(), e.getValue());
        }
        return p;
    }

    private NaruModelConfig config(String model) {
        return new NaruModelConfig("endpointA", model);
    }

    @Test
    public void testFindModelIdsUsesDeclaredModels() {
        NaruWireProvider provider = wire(Map.of(
                "url", "http://127.0.0.1:9",
                "models", "model-a,model-b",
                "probe", "false"));
        Assertions.assertEquals(java.util.List.of("model-a", "model-b"),
                provider.findModelIds(createMockSession(Collections.emptyMap())));
    }

    @Test
    public void testFindModelIdsEmptyWithoutUrl() {
        // no url: the registration declares an endpoint nothing can talk to
        NaruWireProvider provider = wire(Map.of("models", "model-a"));
        Assertions.assertEquals(Collections.emptyList(),
                provider.findModelIds(createMockSession(Collections.emptyMap())));
    }

    @Test
    public void testFindModelIdsFromAgentEnvWhenParamAbsent() {
        // §6 fallback: <instance id>.<key> agent env feeds unpacked registrations
        NaruWireProvider provider = (NaruWireProvider) new NaruWireProvider().newInstance("endpointA");
        Map<String, String> env = new HashMap<>();
        env.put("endpointA.url", "http://127.0.0.1:9");
        env.put("endpointA.models", "model-a,model-b");
        Assertions.assertEquals(java.util.List.of("model-a", "model-b"),
                provider.findModelIds(createMockSession(env)));
    }

    @Test
    public void testIsAvailableWithProbeDisabled() {
        NaruWireProvider provider = wire(Map.of(
                "url", "http://127.0.0.1:9",
                "probe", "false"));
        Assertions.assertTrue(provider.isAvailable(createMockSession(Collections.emptyMap())));
    }

    @Test
    public void testIsAvailableFalseWithoutUrl() {
        NaruWireProvider provider = wire(Map.of("probe", "false"));
        Assertions.assertFalse(provider.isAvailable(createMockSession(Collections.emptyMap())));
    }

    @Test
    public void testIsAvailableFalseWhenProbeFails() {
        // probe=true against a port where nothing listens: connection refused
        NaruWireProvider provider = wire(Map.of(
                "url", "http://127.0.0.1:9",
                "probe", "true"));
        Assertions.assertFalse(provider.isAvailable(createMockSession(Collections.emptyMap())));
    }

    @Test
    public void testGetProtocolDefaultOpenApi() {
        NaruWireProvider provider = wire(Map.of(
                "url", "http://127.0.0.1:9",
                "models", "model-a",
                "probe", "false"));
        NOptional<NaruModelProtocol> proto = provider.getProtocol(config("model-a"),
                createMockSession(Collections.emptyMap()));
        Assertions.assertTrue(proto.isPresent());
        Assertions.assertInstanceOf(NaruModelProtocolOpenAICompat.class, proto.get());
    }

    @Test
    public void testGetProtocolAnthropic() {
        NaruWireProvider provider = wire(Map.of(
                "url", "http://127.0.0.1:9",
                "models", "model-a",
                "protocol", "anthropic",
                "probe", "false"));
        NOptional<NaruModelProtocol> proto = provider.getProtocol(config("model-a"),
                createMockSession(Collections.emptyMap()));
        Assertions.assertTrue(proto.isPresent());
        Assertions.assertInstanceOf(NaruModelProtocolAnthropicCompat.class, proto.get());
    }

    @Test
    public void testUnknownProtocolIsWarnedAndIgnored() {
        // design §8: an unknown protocol id is never guessed at and never fatal —
        // /model add rejects it at the directive level, but a hand-edited (or
        // env-configured) registration makes createProtocol warn and fall back
        // to the provider's default wire, so the endpoint keeps working
        NaruWireProvider provider = wire(Map.of(
                "url", "http://127.0.0.1:9",
                "models", "model-a",
                "protocol", "bogus",
                "probe", "false"));
        NOptional<NaruModelProtocol> proto = provider.getProtocol(config("model-a"),
                createMockSession(Collections.emptyMap()));
        Assertions.assertTrue(proto.isPresent());
        Assertions.assertInstanceOf(NaruModelProtocolOpenAICompat.class, proto.get(),
                "unsupported protocol is ignored, the default wire is used");
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
                (proxy, method, args) -> "env".equals(method.getName()) ? env : null
        );

        return (NaruSession) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{NaruSession.class},
                (proxy, method, args) -> "agent".equals(method.getName()) ? agent : null
        );
    }
}