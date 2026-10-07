package net.thevpc.naru.ext.models.anthropic;

import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.ext.models.NaruModelProtocolBase;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.net.NHttpRequest;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.util.Map;

/**
 * Anthropic Messages API wire protocol ({@code POST {base}/v1/messages}).
 *
 * <p>Unlike the OpenAI-compatible protocol this one:
 * <ul>
 *   <li>sends {@code x-api-key} + {@code anthropic-version} headers</li>
 *   <li>uses the Messages shape (top-level {@code system}, required {@code max_tokens},
 *       {@code tool_use}/{@code tool_result} content blocks)</li>
 * </ul>
 */
public class NaruModelProtocolAnthropicCompat extends NaruModelProtocolBase {

    /** The {@code --protocol=<wire>} id of this wire shape (design doc §8). */
    public static final String PROTOCOL_ID = "anthropic";

    /**
     * Fallback base url used when config key {@code <configPrefix>.url} is not set.
     */
    protected final String defaultBaseUrl;

    public NaruModelProtocolAnthropicCompat(NaruModelProvider provider, NaruModelConfig model, String configPrefix, String chatPath, NaruModelCapabilities capabilities) {
        this(provider, model, configPrefix, chatPath, capabilities, null);
    }

    public NaruModelProtocolAnthropicCompat(NaruModelProvider provider, NaruModelConfig model, String configPrefix, String chatPath, NaruModelCapabilities capabilities, String defaultBaseUrl) {
        super(provider, model, configPrefix, chatPath, capabilities,
                new NaruAnthropicRequestSerializer(),
                new NaruAnthropicResponseParser()
        );
        this.defaultBaseUrl = defaultBaseUrl;
    }

    @Override
    public String url(NaruTask task, Map<String, NElement> env) {
        NOptional<String> own = configValue(task, "url");
        if (own.isPresent()) {
            return own.get().replaceAll("/+$", "");
        }
        if (defaultBaseUrl != null) {
            return defaultBaseUrl;
        }
        return super.url(task, env);
    }

    // ── Live-model listing facts (wire-level GETs, session-scoped) ─────────────
    // The listing response is OpenAI-shaped ({@code data[].id}); only path and
    // auth differ from the openai wire. Kept on the owning class so the provider
    // base reads wire facts from the wire class, not from a central registry.

    /** Relative path of this wire's model-listing endpoint. */
    public static String modelsPath() {
        return "v1/models";
    }

    /** Header name carrying the api key on wire-level GETs. */
    public static String authHeaderName() {
        return "x-api-key";
    }

    /** Header value for a given api key (bare, unlike openai's Bearer). */
    public static String authHeaderValue(String apiKey) {
        return apiKey == null ? "" : apiKey;
    }

    @Override
    protected void prepareRequest(NHttpRequest request, NElement body, NaruTask task) {
        String apiKey = apiKey(task);
        request.header("anthropic-version", "2023-06-01");
        if (!NBlankable.isBlank(apiKey)) {
            request.header("x-api-key", apiKey);
        }
    }
}