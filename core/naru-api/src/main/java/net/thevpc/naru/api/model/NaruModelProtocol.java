package net.thevpc.naru.api.model;

import net.thevpc.naru.api.task.NaruTask;

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
