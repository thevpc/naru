package net.thevpc.naru.api.scheduler;

public enum NaruTaskStatus {
    READY,            // has instructions, waiting for a worker thread
    RUNNING,          // currently being ticked by a worker thread
    BLOCKED_ON_INPUT, // hit readline, waiting for user input
    BLOCKED_ON_EVENT, // hit /task await, waiting for event
    DONE,             // no more instructions, completed normally
    FAILED,           // terminated with error
    KILLED            // terminated forcefully
}
