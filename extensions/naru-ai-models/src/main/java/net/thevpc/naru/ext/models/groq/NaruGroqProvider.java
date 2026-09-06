package net.thevpc.naru.ext.models.groq;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.ext.models.NaruModelCapabilitiesImpl;
import net.thevpc.naru.ext.models.openapi.AbstractOpenAICompatProvider;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.log.NLog;
import net.thevpc.nuts.net.NHttpClient;
import net.thevpc.nuts.net.NHttpRequest;
import net.thevpc.nuts.net.NHttpResponse;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Groq provider — free-tier, ultra-fast inference for open models
 * through Groq's OpenAI-compatible API.
 *
 * <p>Endpoint: POST {baseUrl}/chat/completions
 * <p>Default baseUrl: https://api.groq.com/openai/v1
 */
public class NaruGroqProvider extends AbstractOpenAICompatProvider {
    // Fallback only — Groq deprecates model IDs with little warning
    // (deepseek-r1-distill-llama-70b was pulled Sep 2025). Prefer the live /models call.
    private static final List<String> FALLBACK_MODELS = List.of(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            "qwen/qwen3-32b",
            "moonshotai/kimi-k2-instruct"
    );

    public NaruGroqProvider() {
        super("groq", new String[]{"GROQ_API_KEY"});
    }

    @Override
    protected String baseUrl(NaruSession session) {
        return "https://api.groq.com/openai/v1";
    }

    @Override
    protected NaruModelCapabilities resolveCapabilities(String modelName, NaruSession session) {
        boolean vision = modelName.contains("maverick") || modelName.contains("scout");
        boolean tools = true; // deepseek-r1-distill exception removed — model is decommissioned
        boolean thinking = modelName.contains("qwen3") || modelName.contains("kimi-k2");
        boolean embedding = false;
        long contextLength = 131072L; // 128K standard for most Groq-hosted models

        if (modelName.contains("kimi-k2")) {
            contextLength = 262144L; // 256K for Kimi K2
        }

        return new NaruModelCapabilitiesImpl(vision, tools, thinking, embedding, contextLength);
    }

    @Override
    public List<String> findModelIds(NaruSession session) {
        return findModelIdsWithLiveFallback(session, FALLBACK_MODELS);
    }
}
