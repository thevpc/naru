package net.thevpc.naru.ext.models.ollama;

import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.ext.models.NaruModelCapabilitiesImpl;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code think} field on Ollama's {@code /api/chat}.
 *
 * <p>It is the difference between a reasoning model that reasons and one that is
 * merely asked to, and neither failure is visible in the response: without the field
 * Ollama simply returns no {@code message.thinking}, and with it on a model that
 * cannot think it rejects the request outright. So both directions are pinned here.
 */
public class NaruOllamaThinkRequestTest {

    @BeforeAll
    public static void setUp() {
        try {
            net.thevpc.nuts.core.NWorkspace ws =
                    net.thevpc.nuts.Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            try {
                net.thevpc.nuts.core.NWorkspace ws = net.thevpc.nuts.Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static NaruModelRequest request(Map<String, NElement> env) {
        return new NaruModelRequest(
                List.of(NaruMessage.user("hi")), Collections.emptyList(), env);
    }

    private static NElement body(Map<String, NElement> env, boolean stream) {
        return new NaruOllamaNativeRequestSerializer()
                .serialize(request(env), new NaruModelConfig("ollama", "qwen3"), null, stream);
    }

    @Test
    public void thinkIsSentWhenTheRequestAsksForIt() {
        NElement out = body(Map.of(NaruModelProtocolOllamaNative.THINK_ENV,
                NElement.ofBoolean(true)), true);
        Assertions.assertTrue(out.asObject().get().getBooleanValue("think").orElse(false),
                "a reasoning model produces no thinking at all unless this is set");
    }

    @Test
    public void thinkIsSentAsFalseForAModelThatCannotReason() {
        NElement out = body(Map.of(NaruModelProtocolOllamaNative.THINK_ENV,
                NElement.ofBoolean(false)), true);
        Assertions.assertTrue(out.asObject().get().get("think").isPresent(),
                "false must be sent explicitly: leaving the field out is not the same as declining");
        Assertions.assertFalse(out.asObject().get().getBooleanValue("think").orElse(true));
    }

    @Test
    public void thinkIsOmittedWhenNothingDecided() {
        NElement out = body(new HashMap<>(), true);
        Assertions.assertFalse(out.asObject().get().get("think").isPresent(),
                "an undecided request must not assert a preference the caller never expressed");
    }

    @Test
    public void aNonBooleanSettingIsIgnoredRatherThanGuessed() {
        NElement out = body(Map.of(NaruModelProtocolOllamaNative.THINK_ENV,
                NElement.ofString("maybe")), true);
        Assertions.assertFalse(out.asObject().get().get("think").isPresent());
    }

    @Test
    public void streamIsStillSentAlongsideThink() {
        NElement out = body(Map.of(NaruModelProtocolOllamaNative.THINK_ENV,
                NElement.ofBoolean(true)), true);
        Assertions.assertTrue(out.asObject().get().getBooleanValue("stream").orElse(false),
                "asking to reason must not quietly drop the request to stream");
    }

    // ── the capability decides, and an explicit request still wins ─────────────

    private static NaruModelProtocolOllamaNative protocol(boolean thinkingModel) {
        NaruModelCapabilities capabilities = new NaruModelCapabilitiesImpl(
                false, true, thinkingModel, false, 8192, NaruCachingMode.NONE);
        return new NaruModelProtocolOllamaNative(
                new StubProvider(), new NaruModelConfig("ollama", "qwen3"), "ollama", capabilities);
    }

    @Test
    public void aReasoningModelIsAskedToThink() {
        NaruModelRequest prepared = protocol(true)
                .preprocessRequest(request(new HashMap<>()), null);
        Assertions.assertTrue(prepared.env().get(NaruModelProtocolOllamaNative.THINK_ENV)
                .asBooleanValue().orElse(false),
                "the model's declared capability is the only reliable answer available");
    }

    @Test
    public void aPlainModelIsNotAskedToThink() {
        NaruModelRequest prepared = protocol(false)
                .preprocessRequest(request(new HashMap<>()), null);
        Assertions.assertFalse(prepared.env().get(NaruModelProtocolOllamaNative.THINK_ENV)
                .asBooleanValue().orElse(true),
                "Ollama rejects a request that insists on thinking from a model that cannot");
    }

    @Test
    public void anExplicitRequestOverridesTheCapability() {
        NaruModelRequest prepared = protocol(true).preprocessRequest(
                request(Map.of(NaruModelProtocolOllamaNative.THINK_ENV, NElement.ofBoolean(false))), null);
        Assertions.assertFalse(prepared.env().get(NaruModelProtocolOllamaNative.THINK_ENV)
                .asBooleanValue().orElse(true),
                "a caller that knows better than the capability probe must be able to win");
    }

    @Test
    public void defaultingDoesNotMutateTheCallersRequest() {
        NaruModelRequest original = request(new HashMap<>());
        protocol(true).preprocessRequest(original, null);
        Assertions.assertNull(original.env().get(NaruModelProtocolOllamaNative.THINK_ENV),
                "the caller's request is reused across retries and must not be modified in place");
    }

    private static class StubProvider implements NaruModelProvider {
        @Override
        public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
            return NOptional.ofEmpty();
        }

        @Override
        public String name() {
            return "ollama";
        }

        @Override
        public NOptional<String> apiKey(NaruSession session) {
            return NOptional.ofEmpty();
        }

        @Override
        public List<String> findModelIds(NaruSession session) {
            return List.of();
        }

        @Override
        public void setParam(String name, String value) {
        }

        @Override
        public NOptional<String> getParam(String name) {
            return NOptional.ofEmpty();
        }

        @Override
        public Set<String> getParamNames() {
            return Set.of();
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void setEnabled(boolean enabled) {
        }
    }
}
