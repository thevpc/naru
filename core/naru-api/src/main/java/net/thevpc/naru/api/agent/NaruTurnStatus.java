package net.thevpc.naru.api.agent;

/** How a turn ended, as {@link NaruTurnResult#status()} reports it. */
public enum NaruTurnStatus {
    /** Answered, and the session was saved and unloaded until the next turn. */
    HIBERNATED,
    /** The session has nothing left to run: its tasks are done. */
    COMPLETED,
    INTERRUPTED,
    FAILED,
    /**
     * Answered, and the session stays loaded, waiting for the next message: a handle with a
     * keep-alive ({@code hibernateAfter} above zero) reports this as soon as the session
     * settles, instead of after the keep-alive, and hibernates it later if nothing comes.
     * What the turn wrote is already durable.
     */
    AWAITING_INPUT
}
