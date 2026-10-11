package net.thevpc.naru.ext.tools.plan;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

/**
 * The gate that must pass before an item's work is accepted as {@link
 * NaruPlanItemStatus#DONE}.
 *
 * <p>Execution of these validators is not part of the plan model; the model only
 * records which kind was requested and whether it passed, so that a plan can be
 * reloaded and audited without re-running any model call.
 */
public enum NaruPlanValidatorKind {
    /**
     * No gate. The executor's own completion report is accepted as-is.
     */
    NONE,
    /**
     * A second model judges the item's output. Requires a distinct model from the
     * executor, otherwise the item is reviewing its own work.
     */
    MODEL_REVIEW,
    /**
     * A human approves the item before it is accepted. Implemented with
     * {@code NaruTask.requestInput(..)}, which the readline thread services for
     * background tasks, so it does not require the item to be foreground.
     */
    USER_APPROVAL;

    public boolean isGate() {
        return this != NONE;
    }

    public static NOptional<NaruPlanValidatorKind> parse(String s) {
        if (s == null || s.isBlank()) {
            return NOptional.of(NaruPlanValidatorKind.NONE);
        }
        try {
            return NOptional.of(NaruPlanValidatorKind.valueOf(s.trim().toUpperCase().replace('-', '_')));
        } catch (IllegalArgumentException e) {
            return NOptional.ofNamedError(NMsg.ofC("invalid plan validator '%s'", s));
        }
    }
}
