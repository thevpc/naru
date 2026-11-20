package net.thevpc.naru.api.plan;

/**
 * A plan change the extension refused.
 *
 * <p>Writes go through {@link NaruPlanService}, which lives beside the graph and its rules. The
 * reason is already phrased for a reader — "item ... is running; its text is the instruction a task
 * is working from" — so a host only has to show it, not classify the failure.
 */
public class NaruPlanException extends RuntimeException {

    public NaruPlanException(String message) {
        super(message);
    }

    public NaruPlanException(String message, Throwable cause) {
        super(message, cause);
    }
}
