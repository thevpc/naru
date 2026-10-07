package net.thevpc.naru.ext.models.openapi;

import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.ext.models.NaruModelProtocolBase;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.net.NHttpRequest;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

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
        NOptional<String> own = configValue(task, "url");
        if (own.isPresent()) {
            return own.get().replaceAll("/+$", "");
        }
        if (defaultBaseUrl != null) {
            return defaultBaseUrl;
        }
        return super.url(task, env);
    }



    @Override
    public NaruResponse chatStream(NaruModelRequest request, NaruTask task, NaruStreamHandler handler) {
        if (!capabilities.isStreaming()) {
            // Declared as unable to stream, so the body must not ask for it: some
            // providers answer a streaming request with a single JSON body, and
            // the reader would then see a document where it expects frames.
            // Delivering it as a batch of chunks is still a valid stream shape.
            return super.chatStream(request, task, handler);
        }
        return streamChat(request, task, new NaruOpenApiStreamParser(providerName(), handler, model));
    }

    @Override
    protected void prepareRequest(NHttpRequest request, NElement body, NaruTask task) {
        String apiKey = apiKey(task);
        if (!NBlankable.isBlank(apiKey)) {
            request.header("Authorization", "Bearer " + apiKey);
        }
    }
}
