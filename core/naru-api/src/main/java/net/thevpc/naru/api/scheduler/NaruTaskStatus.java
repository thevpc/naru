package net.thevpc.naru.api.scheduler;

public enum NaruTaskStatus {
    READY(false),            // has instructions, waiting for a worker thread
    RUNNING(false),          // currently being ticked by a worker thread
    BLOCKED_ON_INPUT(false), // hit readline, waiting for user input
    BLOCKED_ON_EVENT(false), // hit /task await, waiting for event
    DONE(true),              // no more instructions, completed normally
    FAILED(true),            // terminated with error
    KILLED(true);            // terminated forcefully

    /**
     * Whether a task in this state has stopped for good. A completed state is final: a task
     * never leaves it, so anything that depends on a task being finished can rely on this
     * rather than watching for the specific state.
     * <p>
     * The blocked states are deliberately not completed. A task waiting on input or an event is
     * still going to be given another chance, and a host awaiting it is waiting for real work
     * rather than for a verdict.
     */
    private final boolean completed;

    NaruTaskStatus(boolean completed) {
        this.completed = completed;
    }

    /**
     * Whether a task in this state has stopped for good, and will make no further transitions.
     * The blocked states answer false: waiting for input or an event is still live.
     */
    public boolean isCompleted() {
        return completed;
    }

}
