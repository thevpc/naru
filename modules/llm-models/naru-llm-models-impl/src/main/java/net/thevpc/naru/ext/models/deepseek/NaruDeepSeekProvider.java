package net.thevpc.naru.ext.models.deepseek;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.ext.models.NaruModelCapabilitiesImpl;
import net.thevpc.naru.ext.models.openai.AbstractOpenAICompatProvider;

import java.util.Set;

/**
 * DeepSeek provider — supports both OpenAI-compatible format and DeepSeek native format.
 * <p>
 * OpenAI-compatible format: uses the OpenAI API shape at {@code https://api.deepseek.com/v1}
 * with the {@code DEEPSEEK_API_KEY} key lookup.
 * <p>
 * DeepSeek native format: currently treated as identical to OpenAI-compatible format,
 * as DeepSeek's native API is OpenAI-compatible. This provider supports both
 * {@code --protocol=openai} and {@code --protocol=deepseek} for convenience.
 *
 * <p>Endpoint: POST {baseUrl}/chat/completions
 * <p>Default baseUrl: https://api.deepseek.com/v1
 */
public class NaruDeepSeekProvider extends AbstractOpenAICompatProvider {
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of(
            "openai", "deepseek"
    );

    // Fallback models list — live /models listing is authoritative when API key is set.
    private static final java.util.List<String> FALLBACK_MODELS = java.util.List.of(
            "deepseek-chat",
            "deepseek-reasoner"
    );

    public NaruDeepSeekProvider() {
        super("deepseek", new String[]{"DEEPSEEK_API_KEY"});
    }

    @Override
    protected String baseUrl(NaruSession session) {
        return "https://api.deepseek.com/v1";
    }

    @Override
    public Set<String> supportedProtocols() {
        return SUPPORTED_PROTOCOLS;
    }

    @Override
    protected NaruModelCapabilities resolveCapabilities(String modelName, NaruSession session) {
        // DeepSeek models: vision? (not currently), tools, thinking (for reasoner), embedding (not currently)
        boolean vision = false; // DeepSeek chat models do not support vision as of now
        boolean tools = true;
        boolean thinking = modelName.contains("reasoner"); // deepseek-reasoner has thinking capabilities
        boolean embedding = false;
        long contextLength = 32768L; // 32K context for deepseek-chat and deepseek-reasoner
        // TODO: adjust context length per model if needed
        return new NaruModelCapabilitiesImpl(vision, tools, thinking, embedding, contextLength,
                NaruCachingMode.AUTOMATIC_PREFIX);
    }

    @Override
    public java.util.List<String> findModelIds(NaruSession session) {
        return findModelIdsWithLiveFallback(session, FALLBACK_MODELS);
    }
}
