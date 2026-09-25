package net.thevpc.naru.api.plan;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

/**
 * Aggregate state of a whole {@link NaruPlan}, always derived from its items by
 * {@link NaruPlan#status()}. It is never stored, so it cannot drift from the items.
 *
 * <p>Note this used to be the per-step status enum; the item lifecycle lives in
 * {@link NaruPlanItemStatus}.
 */
public enum NaruPlanStatus {
    /**
     * Items exist but none is currently runnable or running.
     */
    PENDING,
    /**
     * At least one item is {@link NaruPlanItemStatus#READY},
     * {@link NaruPlanItemStatus#RUNNING} or {@link NaruPlanItemStatus#VALIDATING}.
     */
    ACTIVE,
    /**
     * Nothing is runnable and at least one item is
     * {@link NaruPlanItemStatus#BLOCKED} or {@link NaruPlanItemStatus#FAILED}.
     * Needs a human to make progress.
     */
    BLOCKED,
    /**
     * Every item is {@link NaruPlanItemStatus#DONE}.
     */
    COMPLETED;

    public static NOptional<NaruPlanStatus> parse(String s) {
        if (s == null || s.isBlank()) {
            return NOptional.ofNamedEmpty("plan status");
        }
        try {
            return NOptional.of(NaruPlanStatus.valueOf(s.trim().toUpperCase().replace('-', '_')));
        } catch (IllegalArgumentException e) {
            return NOptional.ofNamedError(NMsg.ofC("invalid plan status '%s'", s));
        }
    }
}
