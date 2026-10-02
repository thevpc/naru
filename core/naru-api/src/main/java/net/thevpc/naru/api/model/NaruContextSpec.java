package net.thevpc.naru.api.model;

/**
 * What to do with a context that has grown too large to send as it is.
 *
 * <p>Two independent decisions, kept separate because they answer different questions and
 * are usually made by different people:
 *
 * <ul>
 *   <li>{@link #older()} -- what happens to the part of the conversation outside the
 *       {@link #window()}. {@code KEEP} sends it anyway, {@code DROP} pretends it is not
 *       there, {@code SUMMARIZE} replaces it with a summary.</li>
 *   <li>{@link #window()} -- how much of the end must survive verbatim.</li>
 * </ul>
 *
 * <p>{@code window} is the context window of the model being called, which is what turns
 * "this is too big" into a number. It is a value rather than a lookup because the spec is
 * also used for a model that is not the task's model -- a summarizer runs on its own model,
 * on a different window.
 *
 * <p>{@code older = SUMMARIZE} is the case this exists for. {@code DROP} and {@code KEEP}
 * are here so a caller can express the other two policies through the same type instead of
 * branching before it.
 */
public final class NaruContextSpec {

    /** What to do with items older than the window. */
    public enum OlderPolicy {
        /** Send them as they are, however large. */
        KEEP,
        /** Leave them out of the request entirely, keeping no record of what was lost. */
        DROP,
        /** Replace them with a summary item. The default, and the only non-lossy option. */
        SUMMARIZE
    }

    /** Context window of the model this spec applies to, in tokens. Zero means unknown. */
    private final long contextWindow;
    private final OlderPolicy older;
    private final NaruWindowSpec keep;
    private final NaruSummaryOptions summary;

    private NaruContextSpec(long contextWindow, OlderPolicy older, NaruWindowSpec keep,
                            NaruSummaryOptions summary) {
        this.contextWindow = contextWindow;
        this.older = older == null ? OlderPolicy.SUMMARIZE : older;
        this.keep = keep == null ? NaruWindowSpec.none() : keep;
        this.summary = summary == null ? NaruSummaryOptions.of() : summary;
    }

    /** A spec that summarizes everything older than the window, with default options. */
    public static NaruContextSpec of(long contextWindow, NaruWindowSpec keep) {
        return new NaruContextSpec(contextWindow, OlderPolicy.SUMMARIZE, keep, NaruSummaryOptions.of());
    }

    public static NaruContextSpec of(long contextWindow, NaruWindowSpec keep, NaruSummaryOptions summary) {
        return new NaruContextSpec(contextWindow, OlderPolicy.SUMMARIZE, keep, summary);
    }

    public static NaruContextSpec keepAll() {
        return new NaruContextSpec(0, OlderPolicy.KEEP, NaruWindowSpec.all(), NaruSummaryOptions.of());
    }

    public static NaruContextSpec dropOlderThan(NaruWindowSpec keep) {
        return new NaruContextSpec(0, OlderPolicy.DROP, keep, NaruSummaryOptions.of());
    }

    /**
     * Context window in tokens, or 0 when unknown.
     *
     * <p>Unknown is a real and common state -- a provider that does not publish one -- so it
     * has a value of its own rather than a sentinel buried in a caller's arithmetic. A
     * caller that needs a threshold should check this first: applying a fraction of zero
     * would compact on every request.
     */
    public long contextWindow() {
        return contextWindow;
    }

    public boolean isWindowKnown() {
        return contextWindow > 0;
    }

    public OlderPolicy older() {
        return older;
    }

    public NaruWindowSpec window() {
        return keep;
    }

    public NaruSummaryOptions summary() {
        return summary;
    }

    public NaruContextSpec withOlder(OlderPolicy newOlder) {
        return new NaruContextSpec(contextWindow, newOlder, keep, summary);
    }

    public NaruContextSpec withWindow(NaruWindowSpec newKeep) {
        return new NaruContextSpec(contextWindow, older, newKeep, summary);
    }

    public NaruContextSpec withSummary(NaruSummaryOptions newSummary) {
        return new NaruContextSpec(contextWindow, older, keep, newSummary);
    }

    public NaruContextSpec withWindowSize(long newWindow) {
        return new NaruContextSpec(newWindow, older, keep, summary);
    }

    @Override
    public String toString() {
        return "NaruContextSpec{window=" + (isWindowKnown() ? contextWindow : "?")
                + ", older=" + older + ", keep=" + keep + ", summary=" + summary + "}";
    }
}