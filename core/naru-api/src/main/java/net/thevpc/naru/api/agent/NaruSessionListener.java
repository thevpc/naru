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

    /** A complete message body and its stable store cursor have been committed. */
    default void onMessageComplete(long taskId, String cursor) { }

    /**
     * The task's granted tool-tag set changed — a directive, a {@code tag_add}/{@code tag_remove}
     * call, or a strip toggle. It is the only moment the permission floor moves without an event or
     * a status change, so a UI that mirrors the grant needs this edge to stay live. Fired on the
     * thread that made the change; listeners must not assume the EDT.
     */
    default void onToolTagsChanged(NaruTask task) { }

    /**
     * The task's tool-exclusion set changed. Like {@link #onToolTagsChanged(NaruTask)}: the only
     * moment the name gate moves without an event, and the only way a mirror of {@code findTools()}
     * — the desktop's tool list — stays honest about what the model is offered.
     */
    default void onToolExclusionsChanged(NaruTask task) { }
}
