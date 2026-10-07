package net.thevpc.naru.ext.models.openai;

import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.ext.models.NaruModelProtocolBase;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.net.NHttpRequest;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The OpenAI-compatible wire protocol ({@code POST {base}/chat/completions},
 * {@code Authorization: Bearer}), the default {@code --protocol} shape.
 *
 * <p>Besides the chat wire, this class owns the wire's live-model listing facts
 * (the static {@code modelsPath}/{@code authHeader*}/{@code parseModelIds}
 * members): the provider base reads them when enumerating the models a
 * registration of this wire can address.
 */
public class NaruModelProtocolOpenAICompat extends NaruModelProtocolBase {

    /** The {@code --protocol=<wire>} id of this wire shape (design doc §8). */
    public static final String PROTOCOL_ID = "openai";

    /**
     * Fallback base url used when config key {@code <configPrefix>.url} is not set.
     */
    protected final String defaultBaseUrl;

    public NaruModelProtocolOpenAICompat(NaruModelProvider provider, NaruModelConfig model, String configPrefix, String chatPath, NaruModelCapabilities capabilities) {
        this(provider, model, configPrefix, chatPath, capabilities, null);
    }

    public NaruModelProtocolOpenAICompat(NaruModelProvider provider, NaruModelConfig model, String configPrefix, String chatPath, NaruModelCapabilities capabilities, String defaultBaseUrl) {
        super(provider, model, configPrefix, chatPath, capabilities,
                new NaruOpenAiRequestSerializer(),
                new NaruOpenAiResponseParser()
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

    /** Relative path of this wire's model-listing endpoint. */
    public static String modelsPath() {
        return "models";
    }

    /** Header name carrying the api key on wire-level GETs. */
    public static String authHeaderName() {
        return "Authorization";
    }

    /** Header value for a given api key. */
    public static String authHeaderValue(String apiKey) {
        return "Bearer " + (apiKey == null ? "" : apiKey);
    }

    /**
     * Parses a listing response ({@code data[].id}) into the model ids it
     * registers.
     */
    public static List<String> parseModelIds(NElement root) {
        NOptional<NElement> dataOpt = root == null ? NOptional.ofEmpty()
                : root.asObject().flatMap(o -> o.get("data"));
        if (!dataOpt.isPresent() || !dataOpt.get().isArray()) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        for (NElement item : dataOpt.get().asArray().get()) {
            item.asObject().flatMap(o -> o.get("id")).flatMap(NElement::asStringValue)
                    .ifPresent(ids::add);
        }
        return ids;
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
        return streamChat(request, task, new NaruOpenAiStreamParser(providerName(), handler, model));
    }

    @Override
    protected void prepareRequest(NHttpRequest request, NElement body, NaruTask task) {
        String apiKey = apiKey(task);
        if (!NBlankable.isBlank(apiKey)) {
            request.header("Authorization", "Bearer " + apiKey);
        }
    }
}
