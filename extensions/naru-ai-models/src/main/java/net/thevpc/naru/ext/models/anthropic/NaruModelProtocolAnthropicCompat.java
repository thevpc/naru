package net.thevpc.naru.ext.models.anthropic;

import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.ext.models.NaruModelProtocolBase;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.net.NHttpRequest;
import net.thevpc.nuts.util.NBlankable;

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
        if (defaultBaseUrl != null) {
            return task.session().agent().env().get(configPrefix + ".url")
                    .flatMap(x -> x.asStringValue())
                    .map(x -> x.replaceAll("/$", ""))
                    .orElse(defaultBaseUrl);
        }
        return super.url(task, env);
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