package net.thevpc.naru.api.model;

import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Splits a text stream into thinking and answer content using a delimiter pair.
 *
 * <p>Stateful on purpose. A tag can be split across two network packets -- a
 * chunk may well end mid-way through the literal {@code <thi}, and a parser
 * that assumed a whole tag per chunk would leak that fragment into the answer
 * as visible text. So this buffers the longest tail that could still turn out to
 * be the start of a tag, and only emits text once it knows the tail is not a tag.
 *
 * <p>Not thread-safe: one parser belongs to one response, driven by one
 * transport thread.
 *
 * <h2>Why not a regex</h2>
 * <p>{@code Pattern} cannot express "a tag may straddle a chunk boundary" without
 * being handed the whole stream, which is exactly what defeats incremental
 * display. The buffering is the feature.
 */
public class NaruThinkingTagParser {

    /**
     * The delimiter pair used by the conventions in the wild.
     */
    public static final String DEFAULT_OPEN_TAG = "<think>";
    public static final String DEFAULT_CLOSE_TAG = "</think>";

    private final String openTag;
    private final String closeTag;
    private final NaruThinkingExtraction extraction = NaruThinkingExtraction.TAG_DELIMITED;
    private final List<NaruStreamChunk> out = new ArrayList<>();

    private StringBuilder pending = new StringBuilder();
    private boolean insideThinking;
    private int index;
    private boolean sawOpenTag;
    /** Set when a close tag never arrived, so the tail can be reported as truncated. */
    private boolean truncated;

    public NaruThinkingTagParser() {
        this(DEFAULT_OPEN_TAG, DEFAULT_CLOSE_TAG);
    }

    public NaruThinkingTagParser(String openTag, String closeTag) {
        this.openTag = openTag == null ? "" : openTag;
        this.closeTag = closeTag == null ? "" : closeTag;
    }

    public String openTag() {
        return openTag;
    }

    public String closeTag() {
        return closeTag;
    }

    public NaruThinkingExtraction extraction() {
        return extraction;
    }

    /**
     * The delimiter pair this parser was built with, for handing to a
     * {@link NaruStreamPipeline} or a {@link NaruModelConfig}.
     */
    public NaruThinkingTags tags() {
        return NaruThinkingTags.of(openTag, closeTag);
    }

    /**
     * Whether an unterminated {@code openTag} was seen. A provider that opened a
     * reasoning block and never closed it is common, and the tail is still
     * thinking -- it just has no closing boundary.
     */
    public boolean sawThinking() {
        return sawOpenTag;
    }

    /**
     * Whether the last thinking segment was left open at the end of the stream.
     */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * Feeds one chunk of stream text.
     *
     * @return the chunks this input completed, in order; empty when the input
     * was absorbed into the tag-boundary buffer
     */
    public List<NaruStreamChunk> feed(String text, String provider) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        pending.append(text);
        return drain(provider, false);
    }

    /**
     * Signals the end of the stream and returns whatever was still buffered.
     *
     * <p>Must be called, or a tag that straddles the final chunk is never
     * emitted and text is silently lost.
     */
    public List<NaruStreamChunk> flush(String provider) {
        return drain(provider, true);
    }

    private List<NaruStreamChunk> drain(String provider, boolean atEnd) {
        List<NaruStreamChunk> produced = new ArrayList<>();
        while (pending.length() > 0) {
            String buffer = pending.toString();

            String tag = insideThinking ? closeTag : openTag;
            int at = buffer.indexOf(tag);
            if (at >= 0) {
                // A delimiter was found, so the text before it is settled: the
                // segment ends (or begins) right here and cannot grow.
                emit(produced, buffer.substring(0, at), provider, true);
                pending.setLength(0);
                pending.append(buffer.substring(at + tag.length()));
                insideThinking = !insideThinking;
                if (insideThinking) {
                    sawOpenTag = true;
                }
                continue;
            }

            // No whole tag in the buffer. Emit everything that cannot be the
            // start of one, and keep the ambiguous tail for the next chunk.
            int keep = atEnd ? 0 : ambiguousTailLength(buffer, tag);
            int flushable = buffer.length() - keep;
            if (flushable > 0) {
                // Mid-stream this text may still be extended by the next chunk, so
                // it is not final and must not claim to be. At end of stream it is
                // final -- unless a reasoning block is still open, in which case
                // the stream was cut off and the text is genuinely unfinished.
                emit(produced, buffer.substring(0, flushable), provider, atEnd && !insideThinking);
                pending.setLength(0);
                pending.append(buffer.substring(flushable));
            }
            break;
        }
        if (atEnd) {
            if (insideThinking) {
                // An unclosed reasoning block: the text is still thinking, but it
                // never terminated, so it must be reported as truncated rather
                // than as a finished segment.
                truncated = true;
            }
            pending.setLength(0);
        }
        return produced;
    }

    /**
     * Length of the longest suffix of {@code buffer} that could be the beginning
     * of {@code tag}, and so cannot be emitted yet.
     */
    private static int ambiguousTailLength(String buffer, String tag) {
        if (tag.isEmpty()) {
            return 0;
        }
        int max = Math.min(buffer.length(), tag.length() - 1);
        for (int len = max; len > 0; len--) {
            if (buffer.endsWith(tag.substring(0, len))) {
                return len;
            }
        }
        return 0;
    }

    private void emit(List<NaruStreamChunk> produced, String text, String provider, boolean complete) {
        if (text.isEmpty()) {
            return;
        }
        NaruStreamChunk chunk = insideThinking
                ? NaruStreamChunkData.thinking(text, index, provider, extraction, complete)
                : new NaruStreamChunkData(NaruChunkKind.ANSWER, text, index, provider, null, complete);
        index++;
        produced.add(chunk);
        out.add(chunk);
    }

    /**
     * Re-tags the chunk of the given kind that is still open as truncated.
     *
     * <p>Called when a generation is interrupted. Mid-stream text was already
     * emitted as non-final, so usually there is nothing buffered here; this
     * exists for the case where the interrupt landed on a chunk boundary and
     * the tail is still ambiguous, and it settles the segment either way.
     */
    public List<NaruStreamChunk> markOpenSegmentTruncated(String provider) {
        List<NaruStreamChunk> tail = new ArrayList<>();
        if (insideThinking && pending.length() > 0) {
            String text = pending.toString();
            pending.setLength(0);
            emit(tail, text, provider, false);
        }
        truncated = true;
        insideThinking = false;
        return tail;
    }

    /**
     * Whether the stream ended with every segment properly closed.
     *
     * <p>This is the authoritative completion signal, and unlike
     * {@link NaruStreamChunk#complete()} it is meaningful for a stream that was
     * cut short -- a chunk delivered mid-stream cannot know what comes next, but
     * after the stream ends this says definitively whether the reasoning was
     * finished or interrupted. This is what a persisted thinking segment records.
     */
    public boolean isSegmentComplete() {
        return !truncated;
    }

    /**
     * Every chunk this parser has produced, in order.
     */
    public List<NaruStreamChunk> chunks() {
        return List.copyOf(out);
    }

    /**
     * Feeds a whole response's text through the parser and hands each chunk to
     * {@code sink}, then flushes. The batch counterpart of {@link #feed}.
     */
    public static void parseAll(String text, String openTag, String closeTag, String provider,
                                Consumer<NaruStreamChunk> sink) {
        NaruThinkingTagParser parser = new NaruThinkingTagParser(openTag, closeTag);
        for (NaruStreamChunk chunk : parser.feed(text, provider)) {
            sink.accept(chunk);
        }
        for (NaruStreamChunk chunk : parser.flush(provider)) {
            sink.accept(chunk);
        }
    }

    @Override
    public String toString() {
        return "NaruThinkingTagParser[" + openTag + "/" + closeTag
                + (NBlankable.isBlank(String.valueOf(index)) ? "" : " chunks=" + index)
                + (insideThinking ? " INSIDE" : "")
                + (truncated ? " TRUNCATED" : "") + "]";
    }
}
