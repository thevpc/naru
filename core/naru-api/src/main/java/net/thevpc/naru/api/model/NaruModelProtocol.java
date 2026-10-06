package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.List;

public interface NaruModelProtocol {
    /**
     * Send a chat request with optional tool definitions.
     *
     * @return the model's response
     */
    NaruResponse chat(NaruModelRequest request, NaruTask task);

    NaruModelCapabilities getCapabilities();

    /**
     * Send a chat request, delivering output as it is produced.
     *
     * <p>The contract is deliberately the same for every protocol: the handler
     * receives {@link NaruStreamChunk}s and the call still returns one
     * assembled {@link NaruResponse}. A protocol with no incremental transport
     * therefore does not need to implement this at all -- the default replays a
     * complete response as a short chunk sequence, so nothing downstream has to
     * know whether the bytes actually arrived progressively.
     *
     * <p>That is what lets a renderer, the transcript and the budget meter be
     * written once against the streamed shape and work for a provider that
     * cannot stream. The alternative, a separate code path per capability, is
     * what this avoids.
     *
     * @param handler receives chunks as they arrive; may be {@code null} to
     *                stream purely for the assembled response
     * @return the same response a non-streaming call would have produced
     */
    default NaruResponse chatStream(NaruModelRequest request, NaruTask task, NaruStreamHandler handler) {
        NaruResponse response = chat(request, task);
        if (handler != null) {
            replayAsChunks(providerName(), response, handler);
        }
        return response;
    }

    /**
     * Whether this protocol can accept a request that ends here, and why not if it
     * cannot.
     *
     * <p>The tail is the whole of a request's failure surface: anything earlier in the
     * history a provider dislikes is a provider problem with a provider answer, but the
     * last few messages are what the engine assembles, and an assembly mistake there is
     * cheap to detect and cheap to name. This is where it is named.
     *
     * <p>The default judges only what no provider can continue from -- a tool call
     * nothing answered, a tool result nothing asked for. Anything stricter is
     * protocol-specific (Anthropic will not take two user turns where OpenAI will not
     * take a request that ends on a tool call) and belongs in an override, because a
     * protocol that has no opinion must keep answering empty rather than inherit another
     * provider's rules.
     *
     * @return empty when the tail can be sent, otherwise the reason it cannot
     */
    default NOptional<String> validateTail(List<NaruMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            // a present reason, not an empty one: there is nothing to answer, and every
            // provider says so in its own words -- some with a 400, some by hanging up
            return NOptional.of("it has no messages to send");
        }
        NaruMessage last = messages.get(messages.size() - 1);
        if (last.getRole() == NaruRole.assistant
                && last.getToolCalls() != null
                && !last.getToolCalls().isEmpty()) {
            // the engine asks the model what to do next, so a call the model made and
            // nobody ran is a tail it cannot answer: it will re-ask, and the tool will
            // never run
            List<String> names = new ArrayList<>();
            for (NaruToolCall call : last.getToolCalls()) {
                names.add(call.getName());
            }
            return NOptional.of(NMsg.ofC(
                    "it ends with the model asking for %s, and nothing has answered that call yet",
                    String.join(", ", names)).toString());
        }
        if (last.getRole() == NaruRole.tool && !hasCallFor(last, messages)) {
            return NOptional.of(NMsg.ofC(
                    "it ends with a result for tool call '%s', but no earlier message asked for that call",
                    last.getToolCallId()).toString());
        }
        return NOptional.ofNamedEmpty("tail");
    }

    /**
     * Whether the call a tool result answers to was actually requested. A result with no
     * call behind it is history from another conversation -- a resumed session, or a
     * message the user edited -- and no provider accepts one.
     */
    private static boolean hasCallFor(NaruMessage result, List<NaruMessage> messages) {
        String id = result.getToolCallId();
        for (NaruMessage m : messages) {
            if (m == result || m.getRole() != NaruRole.assistant || m.getToolCalls() == null) {
                continue;
            }
            for (NaruToolCall call : m.getToolCalls()) {
                if (id != null && id.equals(call.getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * This protocol's provider name, stamped onto chunks for provenance.
     *
     * <p>May be {@code null} for a protocol that does not know it, in which case
     * its chunks carry no provider rather than a fabricated one.
     */
    default String providerName() {
        return null;
    }

    /**
     * Feeds an already-complete response through the chunk pipeline.
     *
     * <p>Deliberately configured with no thinking delimiters: this protocol has
     * already separated reasoning from the answer by whatever means it had, so
     * rescanning the text for tags here could only re-interpret a decision that
     * was already made -- and a model that merely mentions {@code <think>} in a
     * legitimate answer would have its answer truncated.
     */
    static void replayAsChunks(String provider, NaruResponse response, NaruStreamHandler handler) {
        NaruMessage message = response == null ? null : response.getMessage();
        if (message == null) {
            return;
        }
        NaruStreamPipeline pipeline = new NaruStreamPipeline(provider, handler, null);
        if (message.getThinking() != null && !message.getThinking().isEmpty()) {
            pipeline.feedNativeThinking(message.getThinking());
        }
        if (message.getContent() != null && !message.getContent().isEmpty()) {
            pipeline.feed(message.getContent());
        }
        pipeline.finish(!response.isDone());
    }
}
