package net.thevpc.naru.ext.models.gemini;

import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.ext.models.NaruModelProtocolBase;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementDeserializer;

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
        // provider does not: /v1beta/openai vs /v1beta
        String u = super.url(task, env);
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
}
