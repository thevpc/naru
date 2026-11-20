package net.thevpc.naru.api.plan;

/**
 * The state of a whole plan, derived from its items.
 *
 * <p>The names mirror {@code NaruPlanStatus} in the plan extension so a snapshot crosses the
 * seam without a translation table to keep in sync. It is declared here because a host cannot
 * name a type from an optional jar — see {@link NaruPlanReader} for why that matters.
 */
public enum NaruPlanState {
    /** No item is ready yet: the plan is empty or every item still waits on a dependency. */
    PENDING,
    /** At least one item is ready, running or validating. */
    OPEN,
    /** Nothing can move on its own; a blocked or failed item holds the plan. */
    BLOCKED,
    /** Every item is done. */
    COMPLETED
}
