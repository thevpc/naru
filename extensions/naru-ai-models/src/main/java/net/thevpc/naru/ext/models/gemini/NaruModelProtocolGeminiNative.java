package net.thevpc.naru.ext.models.gemini;

import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.ext.models.NaruModelProtocolBase;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementDeserializer;
import net.thevpc.nuts.net.NHttpRequest;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Native Gemini {@code generateContent} protocol.
 *
 * <p>Distinct from {@code NaruGeminiProvider}, which speaks Google's
 * OpenAI-compatible route. This one uses the first-class API, which is a
 * prerequisite for {@code EXPLICIT_RESOURCE} caching: the OpenAI-compatible
 * layer exposes no way to create or reference a {@code CachedContent} resource,
 * so it can only ever do automatic prefix caching.
 */
public class NaruModelProtocolGeminiNative extends NaruModelProtocolBase {

    /** The {@code --protocol=<wire>} id of this wire shape (design doc §8). */
    public static final String PROTOCOL_ID = "gemini";

    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
    public static final String API_VERSION = "v1beta";

    public NaruModelProtocolGeminiNative(NaruModelProvider provider, NaruModelConfig model, String configPrefix,
                                         String chatPath, NaruModelCapabilities capabilities, String defaultBaseUrl) {
        super(provider, model, configPrefix,
                chatPath != null ? chatPath : "models",
                capabilities,
                new NaruGeminiNativeRequestSerializer(),
                new NaruGeminiNativeResponseParser());
    }

    /**
     * Gemini puts the model in the path, and uses a colon to select the action:
     * {@code models/gemini-2.5-pro:generateContent}.
     */
    @Override
    protected String chatPath(NaruTask task, Map<String, NElement> env) {
        String prefix = chatPath == null ? "models" : chatPath;
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + "/" + model.model() + ":generateContent";
    }

    @Override
    protected String url(NaruTask task, Map<String, NElement> env) {
        // the base path includes the api version, which the OpenAI-compat
        // provider does not: /v1beta/openai vs /v1beta. The instance's own url
        // wins (§6); with none configured this protocol talks to Google itself —
        // never the ollama-ish localhost default of the base class.
        String u = configValue(task, "url").orElse(DEFAULT_BASE_URL);
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.endsWith("/" + API_VERSION)) {
            return u;
        }
        if (u.endsWith("/" + API_VERSION + "/openai")) {
            return u.substring(0, u.length() - "/openai".length());
        }
        return u + "/" + API_VERSION;
    }

    /**
     * Google's native API authenticates with the bare key in an
     * {@code x-goog-api-key} header (the OpenAI-compatible route uses Bearer).
     */
    @Override
    protected void prepareRequest(NHttpRequest request, NElement body, NaruTask task) {
        String apiKey = apiKey(task);
        if (!NBlankable.isBlank(apiKey)) {
            request.header("x-goog-api-key", apiKey);
        }
    }

    // ── Live-model listing facts (wire-level GETs, session-scoped) ─────────────

    /** Relative path of this wire's model-listing endpoint. */
    public static String modelsPath() {
        return API_VERSION + "/models";
    }

    /** Header name carrying the api key on wire-level GETs. */
    public static String authHeaderName() {
        return "x-goog-api-key";
    }

    /** Header value for a given api key (the bare key, unlike openai's Bearer). */
    public static String authHeaderValue(String apiKey) {
        return apiKey == null ? "" : apiKey;
    }

    /**
     * Parses a listing response into the model ids it registers: the native API
     * reports fully-qualified resource names ({@code models[].name} like
     * {@code models/gemini-2.5-pro}); the {@code models/} prefix is dropped.
     */
    public static List<String> parseModelIds(NElement root) {
        NOptional<NElement> modelsOpt = root == null ? NOptional.ofEmpty()
                : root.asObject().flatMap(o -> o.get("models"));
        if (!modelsOpt.isPresent() || !modelsOpt.get().isArray()) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        for (NElement item : modelsOpt.get().asArray().get()) {
            NOptional<String> name = item.asObject().flatMap(o -> o.get("name")).flatMap(NElement::asStringValue);
            if (!name.isPresent()) {
                continue;
            }
            String n = name.get();
            if (n.startsWith("models/")) {
                n = n.substring("models/".length());
            }
            if (!NBlankable.isBlank(n)) {
                ids.add(n);
            }
        }
        return ids;
    }
}
