package net.thevpc.naru.api.access;

/**
 * Thrown by {@link NaruAccessPolicy#requireAccess} when a check denies access.
 *
 * <p>A checked-exception-free signal, because it is thrown from the middle of
 * tool and directive execution where the surrounding method signatures are not
 * built to declare it. The {@link #decision()} is carried so the catcher can
 * render the same reason the policy produced instead of inventing its own.
 */
public class NaruAccessDeniedException extends RuntimeException {

    private final transient NaruAccessDecision decision;

    public NaruAccessDeniedException(NaruAccessDecision decision) {
        super(decision == null ? "access denied" : decision.reason());
        this.decision = decision;
    }

    public NaruAccessDecision decision() {
        return decision;
    }
}
