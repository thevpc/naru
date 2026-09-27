package net.thevpc.naru.api.model;

import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a model's output into the one form everything downstream understands:
 * an ordered list of {@link NaruStreamChunk}s, and an assistant message whose
 * reasoning is already separated from its answer.
 *
 * <p>Exists so that no consumer ever has to ask where reasoning came from. A
 * provider that has a real reasoning channel, a model that inlines it in
 * {@code <think>} tags, and a model that does neither all end up as the same
 * chunk sequence, which is what makes the batch and streaming paths
 * interchangeable.
 *
 * <p>Incrementally fed, so the same instance serves a streamed response and a
 * batch one. {@link #batch} is the batch entry point and is exactly equivalent
 * to feeding the whole body and finishing.
 *
 * <p>Not thread-safe; a stream belongs to one reader.
 */
public class NaruStreamPipeline {

    private final String provider;
    private final NaruStreamHandler handler;
    /**
     * Null when this model reports reasoning only out of band, in which case the
     * answer text is passed through untouched rather than scanned for tags.
     */
    private final NaruThinkingTagParser tagParser;
    private final StringBuilder answer = new StringBuilder();
    private final StringBuilder nativeThinking = new StringBuilder();
    private int segmentIndex;
    private int chunkIndex;
    private boolean finished;
    /**
     * Token count for reasoning, or UNKNOWN when the provider did not report it.
     * Kept until the response ends because usage arrives after the content, in
     * a trailing chunk, on some protocols.
     */
    private long thinkingTokens = NaruThinkingSegment.UNKNOWN_TOKENS;

    public NaruStreamPipeline(String provider, NaruStreamHandler handler, NaruThinkingTags tags) {
        this.provider = provider;
        this.handler = handler;
        this.tagParser = tags == null ? null : tags.newParser();
    }

    /**
     * Convenience using the protocol's default delimiters.
     */
    public static NaruStreamPipeline of(String provider, NaruStreamHandler handler) {
        return new NaruStreamPipeline(provider, handler, new NaruThinkingTagParser().tags());
    }

    /**
     * Consumes a whole batch body.
     */
    public static NaruStreamPipeline batch(String provider, String text, NaruStreamHandler handler, NaruThinkingTags tags) {
        NaruStreamPipeline p = new NaruStreamPipeline(provider, handler, tags);
        p.feed(text);
        p.finish();
        return p;
    }

    /**
     * Feeds the next piece of the answer body as it arrives.
     *
     * <p>Reasoning already received as a native field should be passed here as
     * well, via {@link #feedNativeThinking}; the two are kept apart internally so
     * the order between them is preserved in the final message.
     */
    public NaruStreamPipeline feed(String text) {
        if (text == null || text.isEmpty()) {
            return this;
        }
        if (finished) {
            throw new IllegalStateException("pipeline already finished");
        }
        if (tagParser == null) {
            // no delimiters configured: the text is answer, verbatim
            emitAnswer(text);
            return this;
        }
        for (NaruStreamChunk chunk : tagParser.feed(text, provider)) {
            accept(chunk);
        }
        return this;
    }

    /**
     * Records reasoning that arrived in a provider-native field rather than
     * inline in the answer.
     *
     * <p>Delivered as its own chunks rather than appended to the answer, so the
     * transcript can interleave reasoning and answer in the order the model
     * produced them.
     */
    public NaruStreamPipeline feedNativeThinking(String text) {
        if (text == null || text.isEmpty()) {
            return this;
        }
        if (finished) {
            throw new IllegalStateException("pipeline already finished");
        }
        nativeThinking.append(text);
        if (handler != null) {
            handler.onChunk(NaruStreamChunkData.thinking(
                    text, chunkIndex++, provider, NaruThinkingExtraction.NATIVE_FIELD, false));
        }
        return this;
    }

    /**
     * Records the reasoning token count reported by the provider.
     *
     * <p>Only stored, never estimated: a made-up count would quietly inflate
     * budget numbers. May be called at any point, since some protocols report
     * usage in a final chunk after the content.
     */
    public NaruStreamPipeline thinkingTokens(long tokens) {
        this.thinkingTokens = tokens;
        return this;
    }

    /**
     * Signals that no more content is coming, whether the stream ended normally
     * or was interrupted.
     *
     * <p>Safe to call on an interrupted stream, which is the point: whatever
     * reasoning was in flight becomes a segment marked incomplete rather than
     * being dropped.
     *
     * @return the assistant message, with reasoning already separated out
     */
    public NaruMessage finish() {
        return finish(false);
    }

    /**
     * @param interrupted whether generation was cut short
     */
    public NaruMessage finish(boolean interrupted) {
        if (finished) {
            return message();
        }
        finished = true;
        boolean complete = !interrupted;
        if (tagParser != null) {
            for (NaruStreamChunk chunk : (interrupted
                    ? tagParser.markOpenSegmentTruncated(provider)
                    : tagParser.flush(provider))) {
                accept(chunk);
            }
        }
        return message();
    }

    /**
     * The message built so far. Valid before {@link #finish()} for rendering, but
     * only complete afterwards.
     *
     * <p>Side-effect free, so rendering code may hold on to it and ask again
     * without the segment numbering drifting.
     */
    public NaruMessage message() {
        NaruMessage m = NaruMessage.assistant(answer.toString());
        if (nativeThinking.length() > 0) {
            // native reasoning is a single contiguous stretch, so it becomes one
            // segment; addThinkingSegment puts it in the right position
            m.addThinkingSegment(new NaruThinkingSegment(0, nativeThinking.toString(),
                    NaruThinkingExtraction.NATIVE_FIELD, provider, thinkingTokens, true));
        }
        for (NaruThinkingSegment segment : segments()) {
            m.addThinkingSegment(segment);
        }
        return m;
    }

    /**
     * Reasoning recovered from the answer text, one segment per contiguous run of
     * thinking chunks.
     *
     * <p>Chunks are merged back together because a stream splits a single
     * thought into many chunks; persisting each fragment as its own segment would
     * make one long thought read as dozens of unrelated ones.
     */
    public List<NaruThinkingSegment> segments() {
        List<NaruThinkingSegment> out = new ArrayList<>();
        if (tagParser == null) {
            return out;
        }
        StringBuilder current = null;
        NaruThinkingExtraction extraction = null;
        boolean complete = true;
        for (NaruStreamChunk chunk : tagParser.chunks()) {
            if (chunk.kind() == NaruChunkKind.THINKING) {
                if (current == null) {
                    current = new StringBuilder(chunk.text());
                    extraction = chunk.extraction();
                    complete = true;
                } else {
                    current.append(chunk.text());
                }
                // one unfinished chunk taints the whole segment it belongs to:
                // the thought as a whole was cut short
                complete &= chunk.complete();
            } else if (current != null) {
                // answer text ended this run of reasoning
                out.add(new NaruThinkingSegment(out.size(), current.toString(), extraction,
                        provider, thinkingTokens, complete));
                current = null;
            }
        }
        if (current != null) {
            out.add(new NaruThinkingSegment(out.size(), current.toString(), extraction,
                    provider, thinkingTokens, complete));
        }
        return out;
    }

    private void accept(NaruStreamChunk chunk) {
        if (chunk.kind() != NaruChunkKind.THINKING) {
            answer.append(chunk.text());
        }
        if (handler != null) {
            // forwarded with the metadata the parser gave it, so a thinking chunk
            // keeps its extraction mode and completion state all the way to the
            // renderer and the persisted segment
            handler.onChunk(chunk);
        }
    }

    private void emitAnswer(String text) {
        answer.append(text);
        if (handler != null) {
            handler.onChunk(new NaruStreamChunkData(NaruChunkKind.ANSWER, text, chunkIndex++, provider, null, false));
        }
    }
}
