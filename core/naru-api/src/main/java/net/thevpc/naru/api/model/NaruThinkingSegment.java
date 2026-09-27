package net.thevpc.naru.api.model;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.util.NCopiable;

/**
 * One continuous stretch of model reasoning, kept separate from the visible
 * answer on {@link NaruMessage}.
 *
 * <p>Segmented rather than one blob because reasoning is rarely contiguous: a
 * model may think, answer, think again, and answer again. Collapsing that into a
 * single string loses the order, so a transcript cannot show how the answer was
 * arrived at, and a truncated session cannot tell which thought was cut short.
 *
 * <p>Immutable. Built incrementally by the stream pipeline and sealed once the
 * segment is known to be finished.
 */
public class NaruThinkingSegment implements NToElement, NCopiable, Cloneable {

    /**
     * Position of this segment within the message, ascending from 0. Preserved
     * so interleaving with the answer can be reconstructed from stored
     * segments alone.
     */
    private final int index;
    private final String text;
    /**
     * Whether the reasoning arrived as a provider-native field or was split out
     * of the answer text by tag delimiters.
     *
     * <p>Worth recording because the two are not equivalent: a native channel
     * can be trusted verbatim, whereas delimiter-split text is a guess that can
     * misfire on text that merely looks like a tag.
     */
    private final NaruThinkingExtraction extraction;
    /**
     * Provider that produced this reasoning, for provenance when a transcript
     * mixes models. Null when unknown, which is the normal case for a model that
     * was not recorded.
     */
    private final String provider;
    /**
     * Tokens attributable to this segment, or {@link #UNKNOWN_TOKENS} when the
     * provider does not report reasoning separately.
     *
     * <p>Only ever set from a provider usage report, never estimated locally:
     * a guess would silently inflate budget numbers. Providers that do not split
     * reasoning out usually report nothing here, which is why unknown is
     * distinct from zero.
     */
    private final long thinkingTokens;
    /**
     * Whether this segment ran to a natural end.
     *
     * <p>False means generation was interrupted, or the stream ended while the
     * reasoning channel was still open. Persisted so a later reader can tell an
     * interrupted thought from a finished one instead of assuming the shorter of
     * the two.
     */
    private final boolean complete;

    /**
     * Sentinel for "the provider did not report this", distinct from a genuine
     * zero. Matches the convention already used for cache accounting.
     */
    public static final long UNKNOWN_TOKENS = -1;

    public NaruThinkingSegment(int index, String text, NaruThinkingExtraction extraction,
                              String provider, long thinkingTokens, boolean complete) {
        this.index = index;
        this.text = text == null ? "" : text;
        this.extraction = extraction;
        this.provider = provider;
        this.thinkingTokens = thinkingTokens;
        this.complete = complete;
    }

    public NaruThinkingSegment(int index, String text, NaruThinkingExtraction extraction, String provider) {
        this(index, text, extraction, provider, UNKNOWN_TOKENS, true);
    }

    public NaruThinkingSegment(NElement other) {
        NObjectElement o = other.asObject().get();
        this.index = o.getIntValue("index").orElse(0);
        this.text = o.getStringValue("text").orElse("");
        this.provider = o.getStringValue("provider").orNull();
        this.thinkingTokens = o.getLongValue("thinkingTokens").orElse(UNKNOWN_TOKENS);
        this.complete = o.getBooleanValue("complete").orElse(true);
        String extraction1 = o.getStringValue("extraction").orNull();
        this.extraction = extraction1 == null ? null : NaruThinkingExtraction.valueOf(extraction1);
    }

    public static NaruThinkingSegment of(NElement element) {
        if (element == null || element.isNull()) {
            return null;
        }
        return new NaruThinkingSegment(element);
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder o = NObjectElementBuilder.of();
        o.set("index", index);
        o.set("text", text);
        if (extraction != null) {
            o.set("extraction", extraction.name());
        }
        if (provider != null) {
            o.set("provider", provider);
        }
        if (thinkingTokens != UNKNOWN_TOKENS) {
            // omitted when unknown, so a segment is not persisted as if it had
            // been measured when in fact nobody counted it
            o.set("thinkingTokens", thinkingTokens);
        }
        if (!complete) {
            // omitted when true: a finished segment is the expected case, and
            // storing "true" on every one of them is noise that grows with the
            // transcript
            o.set("complete", false);
        }
        return o.build();
    }

    /**
     * Whether the token count is a real measurement rather than a stand-in.
     */
    public boolean hasTokenCount() {
        return thinkingTokens != UNKNOWN_TOKENS;
    }

    /**
     * The token count, or 0 when unknown.
     *
     * <p>Returns 0 rather than the sentinel for arithmetic, so summing segments
     * cannot accidentally bill a negative number. Use {@link #hasTokenCount()}
     * when the difference between "none" and "not measured" matters.
     */
    public long tokenCountOrZero() {
        return hasTokenCount() ? thinkingTokens : 0;
    }

    @Override
    public NaruThinkingSegment copy() {
        return clone();
    }

    @Override
    protected NaruThinkingSegment clone() {
        try {
            return (NaruThinkingSegment) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    public int getIndex() {
        return index;
    }

    public String getText() {
        return text;
    }

    public NaruThinkingExtraction getExtraction() {
        return extraction;
    }

    public String getProvider() {
        return provider;
    }

    public long getThinkingTokens() {
        return thinkingTokens;
    }

    public boolean isComplete() {
        return complete;
    }

    /**
     * Concatenation of this segment's reasoning, for the legacy
     * {@link NaruMessage#getThinking()} string field.
     */
    @Override
    public String toString() {
        return text;
    }
}
