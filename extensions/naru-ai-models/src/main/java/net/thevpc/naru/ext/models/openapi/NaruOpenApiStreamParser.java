package net.thevpc.naru.ext.models.openapi;

import net.thevpc.naru.api.model.*;
import net.thevpc.naru.ext.models.stream.NaruSseReader;
import net.thevpc.naru.ext.models.stream.NaruSseResponseParser;
import net.thevpc.nuts.elem.NArrayElement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.util.NBlankable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an OpenAI-compatible {@code text/event-stream} into chunks and, at the
 * end, into the same {@link NaruResponse} the batch parser produces.
 *
 * <p>Three shapes arrive in the same {@code choices[0].delta} object and have to
 * be told apart, because conflating them is how reasoning ends up printed to the
 * user as the answer:
 *
 * <ul>
 *   <li>{@code content} -- the answer;</li>
 *   <li>{@code reasoning_content} (DeepSeek, OpenAI o-series) or
 *       {@code reasoning} -- a dedicated reasoning field, which some providers
 *       interleave with the answer token by token;</li>
 *   <li>{@code tool_calls} -- a structured call, whose arguments arrive as JSON
 *       string fragments that are only parseable once the call is complete.</li>
 * </ul>
 *
 * <p>Only {@code content} is scanned for thinking tags. A reasoning field is
 * taken as reasoning on the provider's word, and putting it through a tag parser
 * would let a model that quotes {@code <think>} inside its reasoning get its own
 * reasoning truncated. Conversely the answer is scanned, because a model
 * configured for tag-delimited reasoning puts the tags inside {@code content} --
 * skipping that would replay, as the answer, exactly the text the batch parser
 * strips out. This is what keeps a streamed turn equal to a batched one.
 *
 * <p>Stateless with respect to the transport: feed it events in order, then call
 * {@link #finish()}. It does not read the network itself, so the whole protocol
 * is testable against a captured body.
 */
public class NaruOpenApiStreamParser extends NaruSseResponseParser {

    /**
     * Per-stream call state. A model may emit more than one tool call, and the
     * fragments of each are interleaved, so they are keyed by the index the
     * provider gives rather than appended in arrival order.
     */
    private static final class ToolCallBuilder {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }

    private final String provider;
    private final NaruStreamHandler handler;
    private final Map<Integer, ToolCallBuilder> toolCalls = new LinkedHashMap<>();

    /**
     * True when the model delimits reasoning inside {@code content} rather than
     * using a dedicated field, which is the only case the tag parser applies to.
     * Resolved per frame because providers that emit a reasoning field often send
     * untagged reasoning in content for the same model.
     */
    private final NaruStreamPipeline pipeline;

    private String stopReason;
    private boolean done;
    private boolean deliveredContent;
    private int totalTokens = -1;
    private int promptTokens = -1;
    private int evalTokens = -1;
    private int cacheReadTokens = -1;
    private int cacheWriteTokens = -1;
    private boolean sawToolCalls;

    public NaruOpenApiStreamParser(String provider, NaruStreamHandler handler, NaruModelConfig model) {
        this.provider = provider;
        this.handler = handler;
        NaruThinkingTags tags = model == null ? null : model.thinkingTags();
        this.pipeline = new NaruStreamPipeline(provider, handler,
                tags == null ? new NaruThinkingTagParser().tags() : tags);
    }

    /**
     * Consumes one SSE frame.
     *
     * @param data the frame payload
     * @return {@code false} once the stream has signalled completion, so the
     *         caller can stop reading without waiting for the server to hang up
     */
    @Override
    public boolean onEvent(String name, String data) {
        if (done || data == null) {
            return !done;
        }
        if (NaruSseReader.DONE.equals(data.trim())) {
            done = true;
            return false;
        }
        if (NBlankable.isBlank(data)) {
            return true;
        }
        NElement element;
        try {
            element = NElementReader.ofJson().read(data);
        } catch (Exception e) {
            // A frame that is not JSON is a frame this protocol does not
            // understand -- some providers interleave non-data events on the
            // same stream. Dropping it is right: failing here would turn a
            // cosmetic extra frame into a lost answer.
            return true;
        }
        if (!element.isAnyObject()) {
            return true;
        }
        return accept(element.asObject().get());
    }

    private boolean accept(NObjectElement root) {
        acceptUsage(root.getObject("usage").orNull());

        NArrayElement choices = root.getArray("choices").orNull();
        if (choices == null || choices.isEmpty()) {
            // A usage-only frame: providers send these last, with no choices.
            return !done;
        }
        NObjectElement choice = choices.get(0).get().asObject().get();
        String finishReason = choice.getStringValue("finish_reason").orNull();
        if (!NBlankable.isBlank(finishReason)) {
            stopReason = finishReason;
        }

        NObjectElement delta = choice.getObject("delta").orNull();
        if (delta != null) {
            acceptDelta(delta);
        }
        return !done;
    }

    private void acceptDelta(NObjectElement delta) {
        // Reasoning first, and in its own channel: a frame may carry both, and
        // the order the provider used is the order the user should see.
        String reasoning = delta.getStringValue("reasoning_content").orNull();
        if (reasoning == null) {
            reasoning = delta.getStringValue("reasoning").orNull();
        }
        if (reasoning != null && !reasoning.isEmpty()) {
            deliveredContent = true;
            pipeline.feedNativeThinking(reasoning);
        }

        String content = delta.getStringValue("content").orNull();
        if (content != null && !content.isEmpty()) {
            deliveredContent = true;
            pipeline.feed(content);
        }

        NArrayElement calls = delta.getArray("tool_calls").orNull();
        if (calls != null) {
            sawToolCalls = true;
            deliveredContent = true;
            for (NElement element : calls) {
                if (element.isAnyObject()) {
                    acceptToolCallDelta(element.asObject().get());
                }
            }
        }
    }

    private void acceptToolCallDelta(NObjectElement call) {
        // The index is what ties fragments to a call; without it a provider that
        // sends two calls in one frame would interleave their arguments into one
        // unparseable string.
        int index = call.getIntValue("index").orElse(0);
        ToolCallBuilder builder = toolCalls.computeIfAbsent(index, k -> new ToolCallBuilder());
        String id = call.getStringValue("id").orNull();
        if (!NBlankable.isBlank(id)) {
            builder.id = id;
        }
        NObjectElement fn = call.getObject("function").orNull();
        if (fn != null) {
            String name = fn.getStringValue("name").orNull();
            if (!NBlankable.isBlank(name)) {
                builder.name = name;
            }
            // Arguments are a JSON document arriving in pieces. Only ever
            // appended, never parsed until the call is complete.
            NElement args = fn.get("arguments").orNull();
            if (args != null && !args.isNull()) {
                builder.arguments.append(args.asStringValue().orElse(""));
            }
        }
    }

    private void acceptUsage(NObjectElement usage) {
        if (usage == null) {
            return;
        }
        promptTokens = usage.getIntValue("prompt_tokens").orElse(promptTokens);
        evalTokens = usage.getIntValue("completion_tokens").orElse(evalTokens);
        totalTokens = usage.getIntValue("total_tokens").orElse(totalTokens);
        // Same split as the batch parser: a cached token is already inside
        // prompt_tokens, so this decomposes the input total rather than adding
        // to it.
        NObjectElement details = usage.getObject("prompt_tokens_details").orNull();
        if (details != null) {
            int cached = details.getIntValue("cached_tokens").orElse(-1);
            if (cached >= 0) {
                cacheReadTokens = cached;
                cacheWriteTokens = Math.max(0, promptTokens - cached);
            }
        }
    }

    @Override
    public boolean hasDeliveredContent() {
        return deliveredContent;
    }

    @Override
    public boolean isCancelled() {
        return handler != null && handler.isCancelled();
    }

    /**
     * Closes the stream and assembles the response.
     *
     * @param interrupted {@code true} when the read stopped early -- a cancel, a
     *                    dropped connection -- so a truncated reasoning segment
     *                    is recorded as incomplete rather than silently treated
     *                    as a finished thought
     */
    @Override
    public NaruResponse finish(boolean interrupted) {
        NaruMessage message = pipeline.finish(interrupted);
        if (sawToolCalls && !toolCalls.isEmpty()) {
            List<NaruToolCall> calls = new ArrayList<>();
            for (ToolCallBuilder builder : toolCalls.values()) {
                if (NBlankable.isBlank(builder.name)) {
                    continue;
                }
                Map<String, Object> arguments = NaruOpenApiResponseParser.parseArguments(
                        toJsonElement(builder.arguments.toString()));
                calls.add(new NaruToolCall(
                        builder.id != null ? builder.id : "call_" + java.util.UUID.randomUUID(),
                        builder.name,
                        arguments));
            }
            if (!calls.isEmpty()) {
                // Same shape the batch parser produces for a tool call, so the
                // actor loop cannot tell a streamed turn from a batched one.
                message = NaruMessage.assistantWithToolCalls(
                                message.getContent() == null ? "" : message.getContent(), calls)
                        .setThinkingSegments(message.getThinkingSegments());
            }
        }
        NaruResponse response = new NaruResponse();
        response.setMessage(message);
        response.setStopReason(stopReason);
        // "done" means the turn terminated on its own terms, matching the batch
        // parser: an interrupted read is not a completed generation even though
        // it may carry a finish reason.
        response.setDone(!interrupted
                && ("stop".equals(stopReason) || "tool_calls".equals(stopReason) || done));
        response.setTotalTokens(totalTokens);
        response.setPromptTokens(promptTokens);
        response.setEvalTokens(evalTokens);
        response.setCacheReadTokens(cacheReadTokens);
        response.setCacheWriteTokens(cacheWriteTokens);
        return response;
    }

    /**
     * The collected argument fragments as an element, or {@code null} when there
     * are none -- which {@link NaruOpenApiResponseParser#parseArguments} already
     * treats as "no arguments", and which is the correct reading of a call whose
     * arguments the stream never delivered.
     */
    private static NElement toJsonElement(String arguments) {
        String text = arguments == null ? "" : arguments.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return NElementReader.ofJson().read(text);
        } catch (Exception e) {
            // A stream cut mid-arguments is the normal case here, and the
            // fragments collected so far are still worth keeping.
            return NElement.ofString(arguments);
        }
    }
}
