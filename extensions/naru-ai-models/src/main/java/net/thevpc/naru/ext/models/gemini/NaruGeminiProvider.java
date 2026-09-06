package net.thevpc.naru.ext.models.gemini;

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
 * Gemini provider — talks to Google AI Studio through the OpenAI-compatible router.
 *
 * <p>Endpoint: POST {baseUrl}/chat/completions
 * <p>Default baseUrl: https://generativelence.googleapis.com/v1beta/openai
 */
public class NaruGeminiProvider extends AbstractOpenAICompatProvider {
    private static final List<String> FALLBACK_MODELS = List.of(
            "gemini-3.8-flash",
            "gemini-3.7-flash",
            "gemini-3.5-flash",
            "gemini-3.1-pro"
            // gemini-1.5-pro removed: shut down, all requests now 404
            // gemini-2.5-pro / gemini-2.5-flash removed: scheduled shutdown Oct 16, 2026 —
            //   re-add only with a removal date reminder if you need a transition window
    );
    private volatile List<String> cachedLiveModels;
    private volatile long cachedLiveModelsAt = 0L;
    private static final long LIVE_MODELS_TTL_MS = 5 * 60 * 1000L;

    public NaruGeminiProvider() {
        super("gemini",new String[]{"GEMINI_API_KEY"});

    }

    @Override
    protected String baseUrl(NaruSession session) {
        return "https://generativelanguage.googleapis.com/v1beta/openai";
    }


    /**
     * Statically maps model limits since cloud-hosted capabilities cannot be polled natively.
     */
    @Override
    protected NaruModelCapabilities resolveCapabilities(String modelName, NaruSession session) {
        boolean vision = true;
        boolean tools = true;
        boolean thinking = modelName.contains("pro");
        boolean embedding = false;
        long contextLength = 1048576L; // 1M tokens standard fallback for Flash lines

        if (modelName.contains("1.5-pro") || modelName.contains("2.5-pro")) {
            contextLength = 2097152L; // 2M tokens context window for Pro tiers
        }

        return new NaruModelCapabilitiesImpl(vision, tools, thinking, embedding, contextLength);
    }

    @Override
    public List<String> findModelIds(NaruSession session) {
        return findModelIdsWithLiveFallback(session, FALLBACK_MODELS);
    }

}
