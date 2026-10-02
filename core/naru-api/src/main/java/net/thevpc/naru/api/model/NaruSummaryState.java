package net.thevpc.naru.api.model;

/**
 * Whether a summary item still stands in for the items it covers.
 *
 * <p>A summary never deletes what it covers, so reversing one is always possible. The state
 * is what makes that safe to reason about: only an {@code ACTIVE} summary is sent to the
 * model, and only an {@code ACTIVE} summary's exclusions are in force.
 */
public enum NaruSummaryState {

    /** In force. Its covered items are excluded from the context view. */
    ACTIVE,

    /**
     * Reversed by {@code /compact undo}, or deactivated because the content it covered was
     * edited and the summary can no longer be trusted. Its exclusions were cleared, so the
     * covered items are back in the context view exactly as they were.
     */
    UNDONE,

    /**
     * Covered by a later, newer summary, which stands in for this one as well. The covered
     * items stay excluded; undoing the newer summary restores this one to {@code ACTIVE}.
     *
     * <p>Distinct from {@code UNDONE} precisely so that undo can be exact: a summary of a
     * summary is still a summary, and throwing it away would lose the inner one too.
     */
    SUPERSEDED
}