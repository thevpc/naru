package net.thevpc.naru.api.model;

/**
 * One normalised piece of model output.
 *
 * <p>This is the only shape the actor loop, the session writer and the
 * renderers are allowed to see. Providers hand over wildly different payloads
 * -- a dedicated reasoning field, an SSE frame, a JSON line, a whole finished
 * body -- and all of it is normalised to this before anything downstream runs,
 * so a renderer never has to know which provider it is displaying.
 *
 * <p>A chunk is a fragment, not a message. A streamed answer arrives as many
 * ANSWER chunks that concatenate to the answer, and a single response may
 * interleave THINKING and ANSWER freely. Anything that needs the whole thing
 * must accumulate; see {@link #index()} for the order to accumulate in.
 */
public interface NaruStreamChunk {

    /**
     * What this content is.
     */
    NaruChunkKind kind();

    /**
     * The content, which may be empty but is never {@code null}. For
     * {@link NaruChunkKind#TOOL_CALL} this is the call's rendered form rather
     * than a structured payload; structured tool calls remain on
     * {@link NaruResponse}.
     */
    String text();

    /**
     * Position of this chunk in its response, counting from zero and
     * increasing by one per emitted chunk. Order is what lets a consumer
     * reassemble, so it is assigned at emission rather than derived later.
     */
    int index();

    /**
     * Provider that produced this chunk, for provenance. May be {@code null}
     * for a chunk synthesised by NARU itself rather than by a model.
     */
    String provider();

    /**
     * How thinking content was recovered, meaningful only for
     * {@link NaruChunkKind#THINKING} chunks; {@code null} otherwise.
     */
    NaruThinkingExtraction extraction();

    /**
     * Whether NARU knows this chunk is final, meaning no further content of the
     * same segment can still arrive.
     *
     * <p>This is deliberately conservative, because a streaming reader emits
     * before it knows what comes next. Text that precedes a delimiter this
     * parser has already seen is final; text emitted speculatively mid-stream is
     * not, and neither is text from a reasoning block that never closed.
     *
     * <p>The consequence worth knowing: a <i>streamed</i> response reports
     * {@code false} on its trailing chunks, and a <i>non-streamed</i> one
     * reports {@code true} throughout, because in the batch case the whole body
     * is known before anything is emitted. Callers that need to know whether
     * generation actually finished should ask the source of truth for the whole
     * stream rather than reading this off the last chunk -- see
     * {@link NaruThinkingTagParser#isSegmentComplete()}.
     */
    boolean complete();
}
