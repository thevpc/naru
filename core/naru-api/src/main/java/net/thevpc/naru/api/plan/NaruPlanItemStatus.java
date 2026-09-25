package net.thevpc.naru.api.plan;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

/**
 * Lifecycle of a single {@link NaruPlanItem}.
 *
 * <p>The happy path is {@code PENDING -> READY -> RUNNING -> VALIDATING -> DONE}.
 * {@code VALIDATING} is only ever entered for items that declare a validator;
 * items without one go straight from {@code RUNNING} to {@code DONE}.
 *
 * <p>{@link #DONE} cannot be set directly by an executor. The manager only accepts
 * it through a completion call that also carries the validation verdict, so a tool
 * that is handed the plan tag cannot mark unvalidated work as finished.
 */
public enum NaruPlanItemStatus {
    /**
     * Declared, but dependencies are not yet all {@link #DONE}. This is also the
     * state an item returns to when a dependency is reopened.
     */
    PENDING,
    /**
     * All dependencies are {@link #DONE}; eligible for execution once the plan is
     * activated and a concurrency slot is free. Recomputed by
     * {@link NaruPlanManager#recomputeAndFill()}, never set by a caller.
     */
    READY,
    /**
     * An item-task has been spawned and has not reported back yet.
     */
    RUNNING,
    /**
     * The item-task finished and a validator is judging the result.
     */
    VALIDATING,
    /**
     * Finished and accepted. Terminal.
     */
    DONE,
    /**
     * The executor reported it cannot proceed. Terminal until a human intervenes.
     */
    BLOCKED,
    /**
     * The validator rejected the result, or the attempt budget was exhausted.
     * Terminal until a human intervenes.
     */
    FAILED;

    /**
     * Statuses that will not change on their own. Anything else is eligible for
     * automatic progression by {@code recomputeAndFill()}.
     */
    public boolean isTerminal() {
        switch (this) {
            case DONE:
            case BLOCKED:
            case FAILED:
                return true;
            default:
                return false;
        }
    }

    public static NOptional<NaruPlanItemStatus> parse(String s) {
        if (s == null || s.isBlank()) {
            return NOptional.ofNamedEmpty("plan item status");
        }
        try {
            return NOptional.of(NaruPlanItemStatus.valueOf(s.trim().toUpperCase().replace('-', '_')));
        } catch (IllegalArgumentException e) {
            return NOptional.ofNamedError(NMsg.ofC("invalid plan item status '%s'", s));
        }
    }
}
