package net.thevpc.naru.ext.models.ollama;

import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruStreamHandler;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.ext.models.NaruModelProtocolBase;
import net.thevpc.nuts.elem.NElement;

import java.util.Map;

public class NaruModelProtocolOllamaNative extends NaruModelProtocolBase {

    /**
     * Request env key carrying the {@code think} flag sent to Ollama.
     *
     * <p>Reading the request's {@code think} field is the only way to get reasoning
     * out of a reasoning model: Ollama returns no {@code message.thinking} at all
     * unless the request asks for it. Sending it unasked, on the other hand, is
     * worse than useless -- Ollama rejects a model that does not support thinking,
     * so a default of {@code true} would break every non-reasoning model.
     */
    public static final String THINK_ENV = "ollama.think";

    public NaruModelProtocolOllamaNative(NaruModelProvider provider, NaruModelConfig model, String configPrefix, NaruModelCapabilities capabilities) {
        super(provider,model, configPrefix,
                "api/chat",
                capabilities,
                new NaruOllamaNativeRequestSerializer(),
                new NaruOllamaNativeResponseParser());
    }

    /**
     * Defaults {@code think} from the model's declared capability, and lets an
     * explicit request or {@code ollama.think} setting win.
     *
     * <p>The capability is the answer, not a guess: Ollama reports a reasoning model
     * in {@code /api/show}, and that is the only thing that distinguishes a model
     * which can be asked to think from one that will refuse the request.
     */
    @Override
    protected NaruModelRequest preprocessRequest(NaruModelRequest mrequest, NaruTask task) {
        NaruModelRequest request = super.preprocessRequest(mrequest, task);
        if (request.env() != null && request.env().containsKey(THINK_ENV)) {
            return request;
        }
        Map<String, NElement> env = new java.util.HashMap<>(
                request.env() == null ? java.util.Collections.emptyMap() : request.env());
        env.put(THINK_ENV, NElement.ofBoolean(capabilities.isThinking()));
        return request.withMessages(request.messages()).withEnv(env);
    }

    @Override
    public NaruResponse chatStream(NaruModelRequest request, NaruTask task, NaruStreamHandler handler) {
        if (!capabilities.isStreaming()) {
            return super.chatStream(request, task, handler);
        }
        return streamChat(request, task,
                new NaruOllamaNativeStreamParser(providerName(), handler, model));
    }
}
