package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.task.NaruTask;

public interface NaruSessionListener {
    void onEventAppended(NaruEvent newEvent);
    void sessionStarted(NaruSession session);
    void sessionStopped(NaruSession session);

    void onSessionReloaded(NaruSession naruSession);

    /**
     * Fired on every task status transition, including the transient
     * {@code READY->RUNNING} and {@code RUNNING->READY} pair the scheduler
     * performs around each tick. Listeners that care about task lifecycle
     * should filter on the terminal states ({@code DONE}, {@code FAILED},
     * {@code KILLED}) rather than react to every edge.
     */
    default void onTaskStatusChanged(NaruTask task, NaruTaskStatus oldStatus, NaruTaskStatus newStatus) {
    }
}
