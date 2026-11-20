package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NMsg;

/**
 * A pending question the core needs answered before a task can continue.
 * <p>
 * The core does not care how the answer arrives. A terminal implementation blocks a
 * readline thread; a web implementation pushes the prompt to a browser and calls
 * {@link #deliver(String)} minutes later from an HTTP handler. Both are the same event.
 *
 * <h2>Exactly one terminal call</h2>
 * A request must be answered with exactly one of {@link #deliver(String)} or
 * {@link #cancel(String)}. Later calls are ignored, so a browser that reconnects and
 * replays a submission cannot resume a task twice. Implementations are expected to be
 * callable from any thread.
 */
public interface NaruInputRequest {

    /** The question to put to the user, already formatted by the core. */
    NMsg prompt();

    /** The task blocked on this answer. */
    NaruTask task();

    /** Stable within a persisted task activation; old interactions may ignore this. */
    default String requestId() { return Long.toString(task().id()); }

    /**
     * Answers the question and lets the task continue.
     *
     * @param line what the user typed. May be empty, but not null.
     */
    void deliver(String line);

    /** Delivers an idempotent host submission when the implementation supports it. */
    default void deliver(String line, String idempotencyKey) { deliver(line); }

    /**
     * Abandons the question because no answer will come — the user closed the tab, the
     * websocket dropped, the host is shutting down. The task is terminated rather than
     * left blocked forever, since nothing would ever resume it.
     */
    void cancel(String reason);
}
