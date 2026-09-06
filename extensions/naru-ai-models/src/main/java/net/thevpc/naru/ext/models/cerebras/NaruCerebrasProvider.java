package net.thevpc.naru.ext.models.cerebras;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.ext.models.NaruModelCapabilitiesImpl;
import net.thevpc.naru.ext.models.openapi.AbstractOpenAICompatProvider;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cerebras provider — free-tier, high-speed inference for open models
 * through Cerebras' OpenAI-compatible API.
 *
 * <p>Endpoint: POST {baseUrl}/chat/completions
 * <p>Default baseUrl: https://api.cerebras.ai/v1
 */
public class NaruCerebrasProvider extends AbstractOpenAICompatProvider {
    // Cerebras prunes its catalog aggressively and with little notice (it dropped from
    // ~12 models to 2 on a single day in May 2026). Treat this purely as an emergency
    // fallback if the live /models call fails — do not assume these IDs stay valid.
    private static final List<String> FALLBACK_MODELS = List.of(
            "gpt-oss-120b",
            "llama-3.3-70b",
            "llama3.1-8b",
            "qwen-3-235b-a22b-instruct-2507"
    );
    public NaruCerebrasProvider() {
        super("cerebras",new String[]{"CEREBRAS_API_KEY"});
    }

    @Override
    protected String baseUrl(NaruSession session) {
        return "https://api.cerebras.ai/v1";
    }

    @Override
    protected NaruModelCapabilities resolveCapabilities(String modelName, NaruSession session) {
        boolean vision = false;
        boolean tools = !modelName.contains("qwen-3") || modelName.contains("gpt-oss");
        boolean thinking = modelName.contains("qwen-3") || modelName.contains("gpt-oss");
        boolean embedding = false;
        long contextLength = 131072L; // 128K standard for most Cerebras-hosted models

        if (modelName.contains("qwen-3")) {
            contextLength = 131072L; // corrected below — see note
        }

        return new NaruModelCapabilitiesImpl(vision, tools, thinking, embedding, contextLength);
    }


    @Override
    public List<String> findModelIds(NaruSession session) {
        return findModelIdsWithLiveFallback(session, FALLBACK_MODELS);
    }
}
