package net.thevpc.naru.ext.models.mistral;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.ext.models.NaruModelCapabilitiesImpl;
import net.thevpc.naru.ext.models.openapi.AbstractOpenAICompatProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NaruMistralProvider extends AbstractOpenAICompatProvider {
    // Fallback only — verify these are real callable IDs (not marketing names) via
    // GET https://api.mistral.ai/v1/models before relying on this list.
    // Mistral tends to expose dated snapshots + "-latest" aliases, e.g. mistral-large-latest.
    private static final List<String> FALLBACK_MODELS = List.of(
            "mistral-large-latest",
            "mistral-medium-latest",
            "mistral-small-latest",
            "codestral-latest"
    );


    public NaruMistralProvider() {
        super("mistral",new String[]{"MISTRAL_API_KEY"});
    }

    @Override
    protected String baseUrl(NaruSession session) {
        return "https://api.mistral.ai/v1";
    }

    @Override
    protected NaruModelProtocol createProtocol(NaruModelConfig model, NaruModelCapabilities capabilities, NaruSession session) {
        return new NaruModelProtocolMistral(this, model, name(), capabilities);
    }

    /**
     * Statically maps model limits since cloud-hosted capabilities cannot be polled natively.
     */
    @Override
    protected NaruModelCapabilities resolveCapabilities(String modelName, NaruSession session) {
        boolean vision = modelName.contains("small") || modelName.contains("medium") || modelName.contains("large");
        // vision is broadly folded into Small 4 / Medium 3.5 / Large 3 per Mistral's 2026 lineup;
        // narrow this back down if any current line is confirmed text-only
        boolean tools = true;
        boolean thinking = modelName.contains("medium") || modelName.contains("magistral");
        boolean embedding = modelName.contains("embed");

        long contextLength = 131072L; // conservative default; several 2026 lines report 256K+
        if (modelName.contains("codestral")) {
            contextLength = 262144L; // 256K, confirmed for Codestral
        } else if (modelName.contains("medium") || modelName.contains("large")) {
            contextLength = 262144L; // 256K class per current Medium/Large generation
        }
        if (modelName.contains("embed")) {
            contextLength = 8192L; // mistral-embed: 8K context
        }

        return new NaruModelCapabilitiesImpl(vision, tools, thinking, embedding, contextLength);
    }

    @Override
    public List<String> findModelIds(NaruSession session) {
        return findModelIdsWithLiveFallback(session, FALLBACK_MODELS);
    }
}
