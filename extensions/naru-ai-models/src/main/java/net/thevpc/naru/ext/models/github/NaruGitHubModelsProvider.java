package net.thevpc.naru.ext.models.github;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * GitHub Models provider — free inference for a curated catalog of models
 * using any GitHub Personal Access Token (PAT).
 *
 * <p>Endpoint: POST {baseUrl}/chat/completions
 * <p>Default baseUrl: https://models.github.ai/inference
 * <p>Model ids are publisher-qualified, e.g. {@code openai/gpt-4o-mini}.
 */
public class NaruGitHubModelsProvider extends AbstractOpenAICompatProvider {
    private static final String CATALOG_BASE_URL = "https://models.github.ai";

    // Fallback only — GitHub's catalog is refreshed frequently ("Models are regularly
    // updated and added frequently" per their own docs). Prefer the live catalog call.
    private static final List<String> FALLBACK_MODELS = List.of(
            "openai/gpt-4o-mini",
            "openai/gpt-4.1-mini",
            "meta/Llama-3.3-70B-Instruct",
            "meta/Llama-4-Scout-17B-16E-Instruct",
            "mistral-ai/mistral-small-2503",
            "deepseek/DeepSeek-R1",
            "microsoft/Phi-4"
    );
    public NaruGitHubModelsProvider() {
        super("github",new String[]{"GITHUB_TOKEN"});
    }

    @Override
    protected String baseUrl(NaruSession session) {
        return CATALOG_BASE_URL + "/inference";
    }

    @Override
    protected NaruModelCapabilities resolveCapabilities(String modelName, NaruSession session) {
        boolean vision = modelName.contains("gpt-4o") || modelName.contains("gpt-4.1") || modelName.contains("Scout");
        boolean tools = !modelName.contains("DeepSeek-R1");
        boolean thinking = modelName.contains("DeepSeek-R1") || modelName.contains("o1") || modelName.contains("o3");
        boolean embedding = false;
        long contextLength = 131072L; // 128K standard across the GitHub Models catalog

        if (modelName.contains("Phi-4")) {
            contextLength = 16384L; // 16K for Phi-4
        }

        return new NaruModelCapabilitiesImpl(vision, tools, thinking, embedding, contextLength);
    }

    @Override
    public List<String> findModelIds(NaruSession session) {
        String apiKey = apiKey(session).orNull();
        if (NBlankable.isBlank(apiKey)) {
            return Collections.emptyList();
        }
        try {
            NHttpClient http = NHttpClient.of()
                    .connectTimeout(connectTimeout(session))
                    .baseUri(CATALOG_BASE_URL);

            NHttpRequest request = http.GET("catalog/models")
                    .timeout(readTimeout(session))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/vnd.github+json");
            // NOTE: confirm whether X-GitHub-Api-Version is also required here —
            // GitHub's docs show it on the chat/completions example; unclear if
            // /catalog/models enforces it too.

            NHttpResponse response = request.run();
            if (response.isError()) {
                NLog.of(getClass()).log(NMsg.ofC(
                        "Failed to fetch live models from %s: HTTP %s %s",
                        name(), response.statusCode(), response.statusMessage()
                ).asWarning());
                return new ArrayList<>(FALLBACK_MODELS);
            }

            NElement root = NElementReader.ofJson().read(response.contentAsString());
            // UNVERIFIED SHAPE: this assumes a top-level array of {"id": "..."} objects.
            // GitHub's docs snippet for /catalog/models wasn't fully visible — confirm the
            // actual field name (could be "id", "name", or a vendor-prefixed field) via a
            // manual curl before trusting this in production:
            //   curl -H "Authorization: Bearer $GITHUB_TOKEN" \
            //        -H "Accept: application/vnd.github+json" \
            //        https://models.github.ai/catalog/models
            if (!root.isArray()) {
                return new ArrayList<>(FALLBACK_MODELS);
            }
            List<String> ids = new ArrayList<>();
            for (NElement item : root.asArray().get()) {
                item.asObject()
                        .flatMap(o -> o.get("id"))
                        .flatMap(NElement::asStringValue)
                        .ifPresent(ids::add);
            }
            return ids.isEmpty() ? new ArrayList<>(FALLBACK_MODELS) : ids;
        } catch (Exception e) {
            NLog.of(getClass()).log(NMsg.ofC(
                    "Error fetching live models from %s: %s", name(), e.getMessage()
            ).asWarning());
            return new ArrayList<>(FALLBACK_MODELS);
        }
    }
}
