package net.thevpc.naru.ext.models.ollama;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruStreamHandler;
import net.thevpc.naru.api.model.NaruStreamPipeline;
import net.thevpc.naru.api.model.NaruThinkingTagParser;
import net.thevpc.naru.api.model.NaruThinkingTags;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.ext.models.stream.NaruStreamResponseParser;
import net.thevpc.nuts.elem.NArrayElement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.net.NHttpResponse;
import net.thevpc.nuts.util.NBlankable;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads Ollama's {@code /api/chat} stream.
 *
 * <p>Not SSE, and the difference matters: Ollama writes one bare JSON document
 * per line with no {@code data:} prefix, no frame separator and no
 * {@code [DONE]} sentinel. Termination is a {@code done:true} field on the last
 * document. Reusing the SSE reader here would silently produce zero events
 * rather than an error, which is the worst possible failure mode -- it looks
 * like a model that has nothing to say.
 *
 * <p>Reasoning arrives in {@code message.thinking} for models that emit it, in
 * its own field and interleaved with the answer, so it is fed as native
 * thinking and kept out of the answer. Content is still scanned for thinking
 * tags, because a model configured for inline reasoning puts the tags there
 * instead -- the same rule the OpenAI-compatible path uses, and the reason a
 * streamed Ollama turn equals a batched one.
 *
 * <p>Tool calls arrive whole rather than in fragments, so unlike the
 * OpenAI-compatible protocol there is nothing to reassemble here.
 */
public class NaruOllamaNativeStreamParser implements NaruStreamResponseParser {

    private final NaruStreamHandler handler;
    private final NaruStreamPipeline pipeline;
    private final List<NaruToolCall> toolCalls = new ArrayList<>();

    private boolean deliveredContent;
    private boolean done;
    private String doneReason;
    private int promptTokens = -1;
    private int evalTokens = -1;
    private int totalTokens = -1;

    public NaruOllamaNativeStreamParser(String provider, NaruStreamHandler handler, NaruModelConfig model) {
        this.handler = handler;
        NaruThinkingTags tags = model == null ? null : model.thinkingTags();
        this.pipeline = new NaruStreamPipeline(provider, handler,
                tags == null ? new NaruThinkingTagParser().tags() : tags);
    }

    @Override
    public NaruResponse read(NHttpResponse response) throws IOException {
        boolean interrupted = false;
        try (BufferedReader reader = response.content().asBufferedReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isCancelled()) {
                    interrupted = true;
                    break;
                }
                if (!onLine(line)) {
                    break;
                }
            }
        }
        return finish(interrupted);
    }

    /**
     * Consumes one NDJSON document.
     *
     * @return {@code false} once the stream has ended
     */
    public boolean onLine(String line) {
        if (done || NBlankable.isBlank(line)) {
            return !done;
        }
        NElement element;
        try {
            element = NElementReader.ofJson().read(line);
        } catch (Exception e) {
            // Ollama can interleave a non-JSON diagnostic line; skipping it is
            // better than losing the whole answer.
            return true;
        }
        if (!element.isAnyObject()) {
            return true;
        }
        NObjectElement root = element.asObject().get();
        acceptUsage(root);

        NObjectElement message = root.getObject("message").orNull();
        if (message != null) {
            acceptMessage(message);
        }
        if (root.getBooleanValue("done").orElse(false)) {
            done = true;
            doneReason = root.getStringValue("done_reason").orNull();
            return false;
        }
        return true;
    }

    private void acceptMessage(NObjectElement message) {
        // Reasoning first and in its own channel: a document may carry both, and
        // the order the model produced them is the order the user should see.
        String thinking = message.getStringValue("thinking").orNull();
        if (thinking != null && !thinking.isEmpty()) {
            deliveredContent = true;
            pipeline.feedNativeThinking(thinking);
        }

        String content = message.getStringValue("content").orNull();
        if (content != null && !content.isEmpty()) {
            deliveredContent = true;
            pipeline.feed(content);
        }

        NArrayElement calls = message.getArray("tool_calls").orNull();
        if (calls != null) {
            deliveredContent = true;
            for (NElement element : calls) {
                if (element.isAnyObject()) {
                    acceptToolCall(element.asObject().get());
                }
            }
        }
    }

    private void acceptToolCall(NObjectElement call) {
        String id = call.getStringValue("id").orNull();
        NObjectElement fn = call.getObject("function").orElse(call);
        String name = fn.getStringValue("name").orNull();
        if (NBlankable.isBlank(name)) {
            return;
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        NElement args = fn.get("arguments").orNull();
        if (args != null && args.isAnyObject()) {
            for (net.thevpc.nuts.elem.NPairElement entry : args.asObject().get().namedPairs()) {
                String key = entry.key().asStringValue().orNull();
                if (!NBlankable.isBlank(key)) {
                    arguments.put(key, plainValue(entry.value()));
                }
            }
        }
        toolCalls.add(new NaruToolCall(
                id != null ? id : UUID.randomUUID().toString(), name, arguments));
    }

    /**
     * A primitive element's {@code toString} is TSON, not the plain value, which
     * would corrupt a path handed to a tool.
     */
    private static Object plainValue(NElement value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isPrimitive()) {
            return value.asPrimitive().orNull() == null
                    ? value.asStringValue().orNull()
                    : value.asPrimitive().get().value();
        }
        return NElement.simpleOf(value);
    }

    private void acceptUsage(NObjectElement root) {
        if (root.get("prompt_eval_count").isPresent() || root.get("eval_count").isPresent()) {
            promptTokens = root.getIntValue("prompt_eval_count").orElse(promptTokens);
            evalTokens = root.getIntValue("eval_count").orElse(evalTokens);
            totalTokens = promptTokens + evalTokens;
        }
    }

    public NaruResponse finish(boolean interrupted) {
        NaruMessage message = pipeline.finish(interrupted);
        if (!toolCalls.isEmpty()) {
            message = NaruMessage.assistantWithToolCalls(
                            message.getContent() == null ? "" : message.getContent(), toolCalls)
                    .setThinkingSegments(message.getThinkingSegments());
        }
        NaruResponse response = new NaruResponse();
        response.setMessage(message);
        response.setStopReason(doneReason);
        response.setDone(!interrupted && done);
        response.setPromptTokens(promptTokens);
        response.setEvalTokens(evalTokens);
        response.setTotalTokens(totalTokens);
        // Ollama reports no cache breakdown on this endpoint, so it stays
        // unreported rather than being reported as a free cache hit.
        return response;
    }

    @Override
    public boolean hasDeliveredContent() {
        return deliveredContent;
    }

    /**
     * Polled between documents, which is where a cancel lands.
     */
    public boolean isCancelled() {
        return handler != null && handler.isCancelled();
    }
}
