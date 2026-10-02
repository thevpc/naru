package net.thevpc.naru.api.model;

/**
 * What caused a summary item to be created.
 *
 * <p>Recorded so a reader can tell a compaction the agent asked for from one it asked for
 * itself, and so {@code /compact undo} can prefer to reverse the last automatic one when
 * the user did not name a specific summary.
 */
public enum NaruSummaryTrigger {

    /** Created by an explicit {@code /compact} or a {@code context_compact} tool call. */
    MANUAL,

    /**
     * Created by the pre-request hook because the context view grew past the configured
     * threshold of the model's context window.
     */
    AUTO,

    /** Created for a forked child task, summarizing the source task's context view. */
    FORK,

    /**
     * Carried over from a parent task when a child inherited its context, so the child does
     * not re-summarize content the parent already summarized.
     */
    FORK_PARENT;

    public static NaruSummaryTrigger parse(String value) {
        if (value == null) {
            return null;
        }
        switch (value.trim().toUpperCase()) {
            case "MANUAL":
                return MANUAL;
            case "AUTO":
                return AUTO;
            case "FORK":
                return FORK;
            case "FORK_PARENT":
                return FORK_PARENT;
            default:
                return null;
        }
    }
}