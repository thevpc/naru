package net.thevpc.naru.ext.models.kimi;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.ext.models.NaruModelCapabilitiesImpl;
import net.thevpc.naru.ext.models.openai.AbstractOpenAICompatProvider;

import java.util.Set;

/**
 * Kimi provider — OpenAI-compatible format for Moonshot AI's Kimi models.
 * <p>
 * Endpoint: POST {baseUrl}/chat/completions
 * <p>Default baseUrl: https://api.moonshot.cn/v1
 * <p>API key lookup: {@code MOONSHOT_API_KEY}
 */
public class NaruKimiProvider extends AbstractOpenAICompatProvider {
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of(
            "openai"
    );

    // Fallback models list — live /models listing is authoritative when API key is set.
    private static final java.util.List<String> FALLBACK_MODELS = java.util.List.of(
            "kimi-k2-0905-preview",
            "kimi-k2-instruct",
            "kimi-k2-0711-preview",
            "kimi-k2-instruct-0905"
    );

    public NaruKimiProvider() {
        super("kimi", new String[]{"MOONSHOT_API_KEY"});
    }

    @Override
    protected String baseUrl(NaruSession session) {
        return "https://api.moonshot.cn/v1";
    }

    @Override
    public Set<String> supportedProtocols() {
        return SUPPORTED_PROTOCOLS;
    }

    @Override
    protected NaruModelCapabilities resolveCapabilities(String modelName, NaruSession session) {
        // Kimi models: vision? (not currently), tools, thinking (Kimi K2 has thinking), embedding (not currently)
        boolean vision = false; // Kimi models do not support vision as of now
        boolean tools = true;
        boolean thinking = modelName.contains("kimi-k2"); // Kimi K2 series has thinking capabilities
        boolean embedding = false;
        long contextLength = 32768L; // 32K default
        if (modelName.contains("kimi-k2")) {
            contextLength = 262144L; // 256K for Kimi K2 (as seen in Groq provider)
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
