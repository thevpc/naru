package net.thevpc.naru.ext.models.openapi;

import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.ext.models.NaruModelProtocolBase;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.net.NHttpRequest;
import net.thevpc.nuts.util.NBlankable;

import java.util.Map;

public class NaruModelProtocolOpenAICompat extends NaruModelProtocolBase {

    /**
     * Fallback base url used when config key {@code <configPrefix>.url} is not set.
     */
    protected final String defaultBaseUrl;

    public NaruModelProtocolOpenAICompat(NaruModelProvider provider, NaruModelConfig model, String configPrefix, String chatPath, NaruModelCapabilities capabilities) {
        this(provider, model, configPrefix, chatPath, capabilities, null);
    }

    public NaruModelProtocolOpenAICompat(NaruModelProvider provider, NaruModelConfig model, String configPrefix, String chatPath, NaruModelCapabilities capabilities, String defaultBaseUrl) {
        super(provider, model, configPrefix, chatPath, capabilities,
                new NaruOpenApiRequestSerializer(),
                new NaruOpenApiResponseParser()
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

    protected String apiKeyConfigKey() {
        return configPrefix + ".apiKey";
    }

    protected String apiKey(NaruTask task) {
        return task.session().agent().env().get(apiKeyConfigKey())
                .flatMap(x -> x.asStringValue()).orNull();
    }

    @Override
    protected void prepareRequest(NHttpRequest request, NElement body, NaruTask task) {
        String apiKey = apiKey(task);
        if (!NBlankable.isBlank(apiKey)) {
            request.header("Authorization", "Bearer " + apiKey);
        }
    }
}
