package net.thevpc.naru.api.model;

/**
 * Receives model output as it is produced.
 *
 * <p>Implemented by the actor loop, the renderer and the session writer. All
 * three consume chunks and none of them may branch on whether the model
 * actually streamed: a provider that cannot stream is normalised to the same
 * chunks, just delivered in one batch at the end.
 */
public interface NaruStreamHandler {

    /**
     * Called once per chunk, in {@link NaruStreamChunk#index()} order.
     *
     * <p>Called on the transport thread, so an implementation that touches the
     * terminal or the session must not block and must be safe to call from
     * another thread than the one that will read the final response.
     */
    void onChunk(NaruStreamChunk chunk);

    /**
     * Whether the reader should stop emitting.
     *
     * <p>Polled once per chunk, which is what makes interruption work during
     * a long generation: a cancel lands between two chunks and the reader bails
     * out instead of running to completion. This is the cooperative mechanism --
     * the transport cannot be aborted mid-socket, so this is the only place a
     * stop can be observed.
     *
     * @return {@code true} to stop emitting; remaining chunks are not delivered
     */
    default boolean isCancelled() {
        return false;
    }
}
