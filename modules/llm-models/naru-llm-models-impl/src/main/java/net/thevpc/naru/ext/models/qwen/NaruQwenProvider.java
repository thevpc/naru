package net.thevpc.naru.ext.models.qwen;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.ext.models.NaruModelCapabilitiesImpl;
import net.thevpc.naru.ext.models.openai.AbstractOpenAICompatProvider;

import java.util.Set;

/**
 * Qwen provider — OpenAI-compatible format for Alibaba Cloud's Qwen models.
 * <p>
 * Endpoint: POST {baseUrl}/chat/completions
 * <p>Default baseUrl: https://dashscope.aliyuncs.com/compatible-mode/v1
 * <p>API key lookup: {@code DASHSCOPE_API_KEY}
 */
public class NaruQwenProvider extends AbstractOpenAICompatProvider {
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of(
            "openai"
    );

    // Fallback models list — live /models listing is authoritative when API key is set.
    private static final java.util.List<String> FALLBACK_MODELS = java.util.List.of(
            "qwen-turbo",
            "qwen-plus",
            "qwen-max",
            "qwen-max-longcontext",
            "qwen-vl-plus",
            "qwen-vl-max"
    );

    public NaruQwenProvider() {
        super("qwen", new String[]{"DASHSCOPE_API_KEY"});
    }

    @Override
    protected String baseUrl(NaruSession session) {
        return "https://dashscope.aliyuncs.com/compatible-mode/v1";
    }

    @Override
    public Set<String> supportedProtocols() {
        return SUPPORTED_PROTOCOLS;
    }

    @Override
    protected NaruModelCapabilities resolveCapabilities(String modelName, NaruSession session) {
        // Qwen models: vision for vl variants, tools, thinking (some models?), embedding (not currently)
        boolean vision = modelName.contains("vl"); // Qwen-VL series support vision
        boolean tools = true;
        boolean thinking = modelName.contains("plus") || modelName.contains("max") || modelName.contains("turbo"); // heuristic
        boolean embedding = false;
        long contextLength = 32768L; // 32K default
        if (modelName.contains("longcontext")) {
            contextLength = 131072L; // 128K for longcontext variants
        }
        // TODO: adjust per model as needed
        return new NaruModelCapabilitiesImpl(vision, tools, thinking, embedding, contextLength,
                NaruCachingMode.AUTOMATIC_PREFIX);
    }

    @Override
    public java.util.List<String> findModelIds(NaruSession session) {
        return findModelIdsWithLiveFallback(session, FALLBACK_MODELS);
    }
}
