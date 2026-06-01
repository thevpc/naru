package net.thevpc.naru.api.scheduler;

public enum NaruSchedulerStatus {
    IDLE,      // created but not started yet
    RUNNING,   // normally dispatching tasks
    HELD,      // all workers paused at tick boundary
    STOPPING,  // shutdown requested, finishing current ticks
    STOPPED,   // all workers exited cleanly
    KILLED     // interrupted forcefully
}
