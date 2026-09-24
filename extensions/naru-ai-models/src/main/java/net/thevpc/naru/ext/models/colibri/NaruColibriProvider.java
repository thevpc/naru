package net.thevpc.naru.ext.models.colibri;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.ext.models.NaruModelCapabilitiesImpl;
import net.thevpc.naru.ext.models.openapi.AbstractOpenAICompatProvider;
import net.thevpc.naru.ext.models.openapi.NaruModelProtocolOpenAICompat;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.List;

/**
 * Colibri provider — talks to a local Colibri OpenAI-compatible server
 * (e.g. {@code coli serve --host 127.0.0.1 --port 11435 --model-id glm-5.2-colibri}).
 *
 * <p>Endpoint: POST {baseUrl}/v1/chat/completions (OpenAI-compatible wire).
 *
 * <p>Configuration:
 * <pre>
 * colibri.url=http://localhost:11435      (default: http://localhost:11435)
 * colibri.chatPath=v1/chat/completions     (optional, default: v1/chat/completions)
 * colibri.apiKey=...                       (optional; falls back to env COLI_API_KEY)
 * colibri.models=glm-5.2-colibri,...       (optional static model list)
 * colibri.contextLength=65536              (optional, default: 65536)
 * </pre>
 *
 * <p>Models are addressed as {@code colibri/&lt;model&gt;}, e.g.
 * {@code colibri/glm-5.2-colibri}.
 */
public class NaruColibriProvider extends AbstractOpenAICompatProvider {

    private static final String DEFAULT_BASE_URL = "http://localhost:11435";
    private static final String DEFAULT_CHAT_PATH = "v1/chat/completions";
    private static final String DEFAULT_MODELS_PATH = "v1/models";
    private static final long DEFAULT_CONTEXT_LENGTH = 65536L;

    // Fallback only — prefer the live /v1/models call when the server responds.
    private static final List<String> FALLBACK_MODELS = List.of("glm-5.2-colibri");

    public NaruColibriProvider() {
        super("colibri", new String[]{"COLI_API_KEY"});
    }

    @Override
    protected String baseUrl(NaruSession session) {
        String url = session.agent().env().get(name() + ".url")
                .flatMap(NElement::asStringValue)
                .orElse(DEFAULT_BASE_URL);
        return url.replaceAll("/$", "");
    }

    @Override
    protected String chatPath() {
        return DEFAULT_CHAT_PATH;
    }

    @Override
    protected String modelsPath() {
        return DEFAULT_MODELS_PATH;
    }

    @Override
    protected NaruModelProtocol createProtocol(NaruModelConfig model, NaruModelCapabilities capabilities, NaruSession session) {
        String chatPath = session.agent().env().get(name() + ".chatPath")
                .flatMap(NElement::asStringValue)
                .map(p -> {
                    while (p.startsWith("/")) {
                        p = p.substring(1);
                    }
                    while (p.endsWith("/")) {
                        p = p.substring(0, p.length() - 1);
                    }
                    return p;
                })
                .orElse(DEFAULT_CHAT_PATH);
        return new NaruModelProtocolOpenAICompat(this, model, name(), chatPath, capabilities, baseUrl(session));
    }

    @Override
    protected NaruModelCapabilities resolveCapabilities(String modelName, NaruSession session) {
        long contextLength = session.agent().env().get(name() + ".contextLength")
                .flatMap(NElement::asLongValue)
                .orElse(DEFAULT_CONTEXT_LENGTH);
        // GLM 5.2 (colibri build): tool calling + thinking supported, no vision/embedding.
        return new NaruModelCapabilitiesImpl(false, true, true, false, contextLength);
    }

    @Override
    public List<String> findModelIds(NaruSession session) {
        String models = session.agent().env().get(name() + ".models")
                .flatMap(NElement::asStringValue).orNull();
        if (!NBlankable.isBlank(models)) {
            List<String> ids = new ArrayList<>();
            for (String s : models.split("[,\\s]+")) {
                if (!s.isBlank()) {
                    ids.add(s.trim());
                }
            }
            return ids;
        }
        // Local server: never gate model discovery on the API key (cloud behaviour).
        if (isApiKeySet(session)) {
            List<String> live = fetchLiveModelIds(session);
            if (!live.isEmpty()) {
                return live;
            }
        }
        return FALLBACK_MODELS;
    }
}