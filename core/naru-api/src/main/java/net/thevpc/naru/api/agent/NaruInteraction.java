package net.thevpc.naru.api.agent;

import net.thevpc.nuts.text.NMsg;

/**
 * How a session reaches its user.
 * <p>
 * This is the seam that keeps the engine free of any assumption about a human being at a
 * terminal. The core never reads a stream, never prints, and never blocks waiting for
 * someone to type: it calls {@link #requestInput} and carries on, and it reports progress
 * through {@link #write}. What a terminal, a browser, or a test harness does with those
 * two calls is somebody else's problem.
 *
 * <h2>Threading</h2>
 * {@link #write} may be called from any worker thread, including several at once, and must
 * not block. {@link #requestInput} may also arrive on a worker thread and must not block
 * either — the answer comes later, through {@link NaruInputRequest#deliver(String)}, not
 * through a return value.
 *
 * <h2>Lifecycle</h2>
 * {@link #open(NaruSession)} and {@link #close()} bracket one activation of a session.
 * A session may be hibernated and later resumed through a fresh activation. An
 * implementation that owns a thread starts and stops it in these methods, which is why
 * a headless session has no input thread at all.
 */
public interface NaruInteraction {

    /** Interactive console session: blocks a thread on a real terminal. */
    String NAME_TERMINAL = "terminal";

    /** Headless: no thread, output forwarded to a listener. */
    String NAME_STREAM = "stream";

    /**
     * The name this interaction is registered under, for diagnostics and for hosts that
     * look one up by name.
     */
    default String name() {
        return getClass().getSimpleName();
    }

    /** Called once per activation, before any task runs in that activation. */
    void open(NaruSession session);

    /** Called when an activation is being checkpointed and released for hibernation. */
    default void onHibernate() {
    }

    /** Called after {@link #open(NaruSession)} when a fresh activation resumes stored state. */
    default void onResume(NaruSession session) {
    }

    /**
     * Asks the user a question on behalf of a blocked task. Must return promptly: the task
     * stays blocked until the returned request is delivered or cancelled.
     */
    void requestInput(NaruInputRequest request);

    /**
     * Reports a piece of session output. Called for every {@code NaruLogMode}, on
     * whichever thread produced it. A consumer that throws is ignored — output is a
     * side channel and must never fail the work that produced it.
     */
    void write(NaruLogMode mode, NMsg message);

    /**
     * Reports a fragment of output that is still being produced, and that the user is
     * meant to see as it arrives rather than after the work finishes.
     *
     * <p>Fragments of the same {@code mode} concatenate: the caller sends
     * {@code end=false} for each piece and a final {@code end=true} for that mode, and
     * what the user reads is the fragments joined in the order they arrived. A
     * {@link #write} of any mode in between closes the open fragment first, so an
     * unrelated log line can never land in the middle of a half-drawn sentence.
     *
     * <p>An implementation may show fragments the moment they arrive, or it may hold
     * them and emit the joined text once at {@code end}: both are correct. An
     * interaction that is the only output channel (such as a terminal) must not drop
     * them. A network listener may lag; in that case the durable message store remains
     * the source of truth and the host can resynchronize from its last completed-message
     * cursor.
     */
    void writeStream(NaruLogMode mode, NMsg fragment, boolean end);

    /**
     * Reports that a model tool call started, parked, returned or failed.
     *
     * <p>The default does nothing, which is what keeps a terminal unchanged: the same call
     * already prints its {@code PROGRESS} line through {@link #write}, and a host that draws
     * structured tool cards overrides this. A host that overrides it must not block — this
     * arrives on the worker thread that dispatched the tool — and a consumer that throws is
     * ignored, because a tool's result must never depend on who was watching it.
     */
    default void reportToolCall(NaruToolCallEvent event) {
    }

    /**
     * Reports a piece of output together with the task that produced it.
     *
     * <p>The default ignores the task and calls {@link #write(NaruLogMode, NMsg)}, so an
     * interaction that shows one transcript for the whole session stays correct. A host with
     * per-task surfaces overrides this one and gets the attribution for free; the id is
     * whatever {@code NaruTask.id()} was when the line was logged, and {@code 0} means the
     * session itself produced it.
     */
    default void write(NaruLogMode mode, NMsg message, long taskId) {
        write(mode, message);
    }

    /**
     * Streaming counterpart of {@link #write(NaruLogMode, NMsg, long)}: every fragment of one
     * open stream is stamped with the task that is producing it. The default drops the id and
     * calls {@link #writeStream(NaruLogMode, NMsg, boolean)}.
     */
    default void writeStream(NaruLogMode mode, NMsg fragment, boolean end, long taskId) {
        writeStream(mode, fragment, end);
    }

    /** Called once per activation when it closes. Must not block. */
    void close();
}
