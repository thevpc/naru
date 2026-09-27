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
 * {@link #open(NaruSession)} is called once when the session starts and {@link #close()}
 * when it stops. An implementation that owns a thread starts and stops it there, which is
 * why a headless session has no input thread at all.
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

    /** Called once when the session starts, before any task runs. */
    void open(NaruSession session);

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
     * them and emit the joined text once at {@code end}: both are correct, and a
     * headless host or a test harness has no reason to bother with the first. Neither
     * may drop them -- a model turn that is streamed but never shown is a turn the
     * user paid for and did not get.
     */
    void writeStream(NaruLogMode mode, NMsg fragment, boolean end);

    /** Called once when the session stops. Must not block. */
    void close();
}
