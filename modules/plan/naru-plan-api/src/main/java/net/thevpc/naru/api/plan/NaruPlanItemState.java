package net.thevpc.naru.api.plan;

/**
 * The state of one item in a plan.
 *
 * <p>Names match {@code NaruPlanItemStatus} in the plan extension one for one, which is what lets
 * the snapshot cross the seam by name. A host must not import the extension's own enum: it lives in
 * a jar the host does not compile against.
 */
public enum NaruPlanItemState {
    /** Still waiting for a dependency. */
    PENDING,
    /** Every dependency is done; nothing is working on it. */
    READY,
    /** A task is doing the work. */
    RUNNING,
    /** The work is finished and its validator is judging it. */
    VALIDATING,
    /** Done. */
    DONE,
    /** Stopped on purpose — a gate, or a dependency that cannot be met. */
    BLOCKED,
    /** The work or its validator failed. */
    FAILED;

    /** True when no further work will happen to this item without someone reopening it. */
    public boolean isTerminal() {
        return this == DONE || this == BLOCKED || this == FAILED;
    }
}
