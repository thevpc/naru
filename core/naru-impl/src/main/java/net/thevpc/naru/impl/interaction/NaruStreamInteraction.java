package net.thevpc.naru.impl.interaction;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruOutput;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.text.NMsg;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The headless implementation: no terminal, no thread, output forwarded to a listener.
 * <p>
 * This is the one a server embeds. A web front end hands each session a listener that
 * pushes {@link NaruOutput} onto an SSE stream or a websocket, and answers
 * {@link NaruInputRequest}s whenever the user submits a line — possibly from a different
 * process, minutes later.
 *
 * <h2>Nothing is dropped on the floor</h2>
 * An input request is published to an {@link InputListener} and then held until it is
 * delivered or cancelled, because there is no reader thread to pick it up. That is the whole
 * point: the host owns the lifecycle, and the core waits. A host that never answers leaves
 * the task blocked, which is the honest outcome and is preferable to inventing a default
 * answer.
 */
public class NaruStreamInteraction implements NaruInteraction {

    private final OutputListener listener;
    private volatile InputListener inputListener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong();
    private final NaruBufferedStreamWriter bufferedStream = new NaruBufferedStreamWriter();

    /**
     * The one request currently waiting for an answer, so that {@link #close()} can fail
     * it instead of leaving a worker blocked forever on a host that has gone away.
     */
    private final AtomicReference<NaruInputRequest> pending = new AtomicReference<>();

    /** Receives every piece of session output, in emission order per session. */
    public interface OutputListener {
        void onOutput(NaruOutput output);
    }

    /**
     * Receives the input requests this interaction wants answered.
     * <p>
     * Required: a headless session has no reader thread, so without a listener an input
     * request would be published to nobody and the waiting task would block indefinitely.
     */
    public interface InputListener {
        void onInputRequested(NaruInputRequest request);
    }

    public NaruStreamInteraction(OutputListener listener) {
        this(listener, null);
    }

    public NaruStreamInteraction(OutputListener listener, InputListener inputListener) {
        this.listener = listener;
        this.inputListener = inputListener;
    }

    public NaruStreamInteraction withInputListener(InputListener inputListener) {
        this.inputListener = inputListener;
        return this;
    }

    @Override
    public String name() {
        return NAME_STREAM;
    }

    @Override
    public void open(NaruSession session) {
        closed.set(false);
    }

    /**
     * Hands the request to the host and returns immediately.
     * <p>
     * Nothing is blocked here. The task is already parked in
     * {@code BLOCKED_ON_INPUT} and simply stays there until the host calls
     * {@link NaruInputRequest#deliver(String)} or {@link NaruInputRequest#cancel(String)},
     * so the answer may come from any thread, any later request, or another process.
     */
    @Override
    public void requestInput(NaruInputRequest request) {
        InputListener target = inputListener;
        if (closed.get() || target == null) {
            // nobody can ever answer: fail the task rather than leave it blocked forever
            request.cancel("no input listener is attached to this session");
            return;
        }
        pending.set(request);
        try {
            target.onInputRequested(request);
        } catch (Exception ignored) {
            // same rule as output: a consumer that throws must not fail the work
            pending.compareAndSet(request, null);
            request.cancel("input listener threw while handling the request");
        }
    }

    @Override
    public void write(NaruLogMode mode, NMsg message) {
        if (closed.get() || listener == null) {
            return;
        }
        NaruOutput output = new NaruOutput(sequence.incrementAndGet(), Instant.now(), mode, message);
        try {
            listener.onOutput(output);
        } catch (Exception ignored) {
            // a consumer that throws must not fail the work that produced the output
        }
    }

    /**
     * Joins streamed fragments and reports them as one output when the stream ends.
     *
     * <p>A host consuming {@link NaruOutput} gets whole messages and can render them
     * as complete units -- a half-sentence per event would be useless to it. If it
     * wants token-level updates it should consume the model's stream directly; this
     * interaction is the log of what happened, not the wire.
     */
    @Override
    public void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
        if (closed.get() || listener == null) {
            return;
        }
        bufferedStream.write(this, mode, fragment, end);
    }

    /**
     * Closes the interaction and fails any request still waiting, so a host shutting the
     * session down unblocks its workers instead of leaving them parked.
     */
    @Override
    public void close() {
        // before closed: a turn that was still streaming must not have its text
        // swallowed by the shutdown that interrupted it
        bufferedStream.flushAll(this);
        closed.set(true);
        NaruInputRequest left = pending.getAndSet(null);
        if (left != null) {
            left.cancel("the session was closed before the question was answered");
        }
    }
}
