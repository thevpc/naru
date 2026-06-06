package net.thevpc.naru.api.scheduler;

public enum NaruSchedulerStatus {
    IDLE,      // created but not started yet
    RUNNING,   // normally dispatching tasks
    STOPPING,  // shutdown requested, finishing current ticks
    STOPPED,   // all workers exited cleanly
    KILLED     // interrupted forcefully
}
