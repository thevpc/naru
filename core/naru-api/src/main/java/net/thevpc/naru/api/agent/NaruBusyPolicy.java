package net.thevpc.naru.api.agent;

/**
 * What a session handle does with a turn submitted while the previous one is still running.
 * <p>
 * Busy means a turn is in progress. A session being saved for hibernation is not busy: its
 * turn is over, so a submit waits for the save (at most the capacity timeout) and is served
 * from the stored session, whatever the policy.
 */
public enum NaruBusyPolicy {
    /** Refuse the turn with {@link NaruSessionBusyException}. */
    REJECT,
    /** Queue the turn's input; it is delivered the next time the session asks for input. */
    QUEUE,
    /** Cancel the running turn's tasks and refuse this one, so the caller can resubmit. */
    INTERRUPT
}
