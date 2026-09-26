package net.thevpc.naru.api.scheduler;

public enum NaruTaskStatus {
    READY,            // has instructions, waiting for a worker thread
    RUNNING,          // currently being ticked by a worker thread
    BLOCKED_ON_INPUT, // hit readline, waiting for user input
    BLOCKED_ON_EVENT, // hit /task await, waiting for event
    DONE,             // no more instructions, completed normally
    FAILED,           // terminated with error
    KILLED;           // terminated forcefully

    private static final java.util.Set<NaruTaskStatus> TERMINAL = java.util.Set.of(DONE, FAILED, KILLED);

    /**
     * Whether a task in this state has stopped for good. A terminal state is final: a task
     * never leaves it, so anything that depends on a task being finished can rely on this
     * rather than watching for the specific state.
     * <p>
     * The blocked states are deliberately not terminal. A task waiting on input or an event
     * is still going to be given another chance, and a host awaiting it is waiting for real
     * work rather than for a verdict.
     */
    public static boolean isTerminalStatus(NaruTaskStatus status) {
        return status != null && TERMINAL.contains(status);
    }
}
