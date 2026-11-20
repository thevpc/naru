package net.thevpc.naru.desktop.host;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruOutput;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruToolCallEvent;
import net.thevpc.naru.desktop.util.MsgText;
import net.thevpc.nuts.text.NMsg;

import javax.swing.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Swing host: an in-process {@link NaruInteraction} that never blocks a worker and never
 * touches a widget off the EDT.
 * <p>
 * Contract it satisfies (from {@code NaruInteraction}): {@code write} / {@code writeStream} /
 * {@code requestInput} may arrive on any worker thread, several at once, and must return
 * promptly. So the publish path is: mutate the block heuristic, enqueue into an unbounded-but-
 * capped lock-free queue, return. Nothing here waits for the EDT, and nothing here can throw
 * into the engine.
 * <p>
 * Display path is the opposite: one {@link javax.swing.Timer} drains the queue on the EDT at a
 * fixed rate, merging runs of same-block fragments into a single update, so a model emitting
 * hundreds of tokens a second produces at most {@code flushHz} widget updates a second.
 * <p>
 * Input requests are the exception to every rule above: they are never merged, never dropped,
 * and never left in the queue while the host is shutting down, because a lost request strands a
 * worker in {@code BLOCKED_ON_INPUT} forever.
 */
public class InteractionHost implements NaruInteraction {

    /** Events held before the producer starts dropping transcript events. ~50k lines of chat. */
    public static final int DEFAULT_CAPACITY = 50_000;
    /** Fragment runs longer than this merge into one chunk, so a flush stays bounded. */
    public static final int MAX_MERGE_CHARS = 64 * 1024;
    /** Widget updates per second, per plan §4.4. */
    public static final int DEFAULT_FLUSH_HZ = 30;
    /**
     * Events applied in one flush. The frame budget is the reason this exists: applying an event
     * costs about a microsecond in the model and far more once a widget row is attached, so an
     * unbounded drain of a 50 000-line flood cost 39 ms of EDT time in one tick (measured by
     * {@code TranscriptLoadBudgetTest}). 4 000 is ~5 ms of model work and 120 000 events a second
     * at 30 Hz — more than the engine produces by two orders of magnitude.
     */
    public static final int MAX_EVENTS_PER_FLUSH = 4_000;

    /** Receives a batch of events on the EDT. */
    public interface Listener {
        void onBatch(List<TranscriptEvent> batch, InteractionHost host);
    }

    private final ConcurrentLinkedQueue<TranscriptEvent> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final int capacity;
    private final BlockBoundaryHeuristic blocks = new BlockBoundaryHeuristic();
    /**
     * Guards {@link #blocks}, which is not thread-safe and is mutated by whichever worker
     * happens to be emitting. Held only for the microseconds it takes to decide a boundary and
     * enqueue — never while waiting on anything.
     */
    private final Object publishLock = new Object();

    /**
     * Set while the desktop is running a {@code /}-directive for the user, so every block the
     * directive emits is marked as conversation content rather than engine noise. Guarded by
     * {@link #publishLock}.
     */
    private boolean pendingDirective;

    private final AtomicLong publishedEvents = new AtomicLong();
    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong droppedEvents = new AtomicLong();
    private final AtomicLong droppedChars = new AtomicLong();
    private final AtomicLong flushedEvents = new AtomicLong();
    private final AtomicLong flushCount = new AtomicLong();
    private final AtomicLong maxFlushNanos = new AtomicLong();
    private final AtomicLong lastFlushNanos = new AtomicLong();
    private final AtomicLong maxPublishNanos = new AtomicLong();
    private final AtomicLong lastPublishNanos = new AtomicLong();

    /** The one request awaiting an answer. Out-of-band, so it survives a saturated queue. */
    private final AtomicReference<NaruInputRequest> pendingInput = new AtomicReference<>();
    /** Answered-but-not-yet-displayed requests, kept until the widget can show them. */
    private final ConcurrentLinkedQueue<TranscriptEvent> inputEvents = new ConcurrentLinkedQueue<>();

    private final AtomicReference<NaruSession> sessionRef = new AtomicReference<>();
    private volatile Listener listener;
    private volatile Timer timer;
    private volatile boolean closed;
    private final AtomicBoolean laggedPublished = new AtomicBoolean();

    private final String hostName;

    public InteractionHost() {
        this("swing", DEFAULT_CAPACITY, null);
    }

    public InteractionHost(String hostName, int capacity, Listener listener) {
        this.hostName = hostName;
        this.capacity = capacity;
        this.listener = listener;
    }

    public InteractionHost(Listener listener) {
        this("swing", DEFAULT_CAPACITY, listener);
    }

    @Override
    public String name() {
        return hostName;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ── engine side: must never block ─────────────────────────────────────────

    @Override
    public void open(NaruSession session) {
        closed = false;
        sessionRef.set(session);
        laggedPublished.set(false);
    }

    @Override
    public void write(NaruLogMode mode, NMsg message) {
        write(mode, message, NaruOutput.NO_TASK);
    }

    /**
     * Whole message, stamped with the task that emitted it (E1). The stamp is not cosmetic: it is
     * what makes a task switch a block boundary in {@link BlockBoundaryHeuristic} instead of an
     * inference, and what lets the tools panel and the transcript agree about who ran a call.
     */
    @Override
    public void write(NaruLogMode mode, NMsg message, long taskId) {
        long t0 = System.nanoTime();
        String text = MsgText.plain(message == null ? "" : message.toString());
        synchronized (publishLock) {
            // A whole message is a boundary only when the mode or task changed, or when it
            // follows a stream. Consecutive whole messages of one mode/task belong to one
            // block: directives log their help, listings and errors line by line, and closing
            // on every write used to turn each of those lines into its own transcript card.
            if (blocks.startsNewBlock(mode, taskId)) {
                String closing = blocks.close();
                publish(closing == null ? null : new TranscriptEvent.Closed(closing));
            }
            String id = blocks.openForWhole(mode, taskId);
            boolean directive = directiveMark();
            publish(new TranscriptEvent.Chunk(id, mode, text, true, taskId, directive,
                    message != null && message.isError()));
        }
        recordPublish(t0);
    }

    @Override
    public void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
        writeStream(mode, fragment, end, NaruOutput.NO_TASK);
    }

    @Override
    public void writeStream(NaruLogMode mode, NMsg fragment, boolean end, long taskId) {
        long t0 = System.nanoTime();
        String text = MsgText.plain(fragment == null ? "" : fragment.toString());
        synchronized (publishLock) {
            String id = blocks.openForStream(mode, taskId);
            boolean directive = directiveMark();
            publish(new TranscriptEvent.Chunk(id, mode, text, end, taskId, directive));
            if (end) {
                String closing = blocks.close();
                publish(closing == null ? null : new TranscriptEvent.Closed(closing));
            }
        }
        recordPublish(t0);
    }

    /**
     * A tool call changed state. Published under the same lock as text, so a card lands in the
     * transcript where the call actually happened rather than where the flush timer noticed it;
     * and it is not a {@code Chunk}, so the saturation rule cannot drop it — a settled call with
     * no card would leave the panel showing a call that never finishes.
     */
    @Override
    public void reportToolCall(NaruToolCallEvent event) {
        if (event == null) {
            return;
        }
        long t0 = System.nanoTime();
        synchronized (publishLock) {
            publish(new TranscriptEvent.ToolCall(event));
        }
        toolCalls.incrementAndGet();
        recordPublish(t0);
    }

    @Override
    public void requestInput(NaruInputRequest request) {
        long t0 = System.nanoTime();
        pendingInput.set(request);
        // never merged, never dropped: if the queue is saturated this still gets through
        inputEvents.add(new TranscriptEvent.InputRequested(request));
        recordPublish(t0);
    }

    @Override
    public void onHibernate() {
        synchronized (publishLock) {
            String closing = blocks.close();
            publish(closing == null ? null : new TranscriptEvent.Closed(closing));
        }
    }

    /**
     * Force a block boundary and mark the block that follows as a directive result: close whatever
     * is open so the next output opens a fresh card.
     * <p>
     * The boundary matters because the heuristic merges consecutive whole messages of the same mode
     * and task — that is what keeps a multi-line listing from exploding into one card per line —
     * and a failed turn can leave its {@code AGENT_RESPONSE} block open with no final fragment, so
     * the next directive's output (same mode, same task) used to be appended to that error text
     * instead of appearing as its own result.
     * <p>
     * The mark matters because directive output is <em>conversation</em> — a reply to something the
     * user typed — and would otherwise be indistinguishable from the raw {@code AGENT_RESPONSE}
     * the engine also uses for a streamed model answer. The conversation view shows a directive
     * result unconditionally rather than hiding it behind the activity toggle.
     */
    public void beginDirectiveBlock() {
        synchronized (publishLock) {
            String closing = blocks.close();
            publish(closing == null ? null : new TranscriptEvent.Closed(closing));
            pendingDirective = true;
        }
    }

    /**
     * The mark for a block that just opened. A directive sets it before it runs and clears it after,
     * so every block the directive emits carries the mark.
     */
    private boolean directiveMark() {
        return pendingDirective;
    }

    /** Stop marking new blocks as directive results; the directive that set the mark has finished. */
    public void endDirectiveBlock() {
        synchronized (publishLock) {
            pendingDirective = false;
        }
    }

    @Override
    public void onResume(NaruSession session) {
        sessionRef.set(session);
    }

    @Override
    public void close() {
        // flush what already streamed before marking closed, so a turn interrupted by
        // shutdown does not lose its text (same ordering rule NaruStreamInteraction follows)
        synchronized (publishLock) {
            String closing = blocks.close();
            publish(closing == null ? null : new TranscriptEvent.Closed(closing));
            publish(new TranscriptEvent.HostClosed("session closed"));
        }
        closed = true;
        NaruInputRequest left = pendingInput.getAndSet(null);
        if (left != null) {
            // the honest outcome: nobody will ever answer, so terminate rather than park
            left.cancel("the desktop host was closed before the question was answered");
        }
        // a parked tool leg never settles in this activation. The host does not invent a final
        // event for it — the engine owns that vocabulary — it publishes HostClosed, and
        // TranscriptModel settles the open card so the panel cannot show a call that finished
        // in an earlier activation as still running.
        stopFlushTimer();
    }

    // ── publishing ────────────────────────────────────────────────────────────

    private void publish(TranscriptEvent event) {
        if (event == null) {
            return;
        }
        publishedEvents.incrementAndGet();
        if (queued.get() >= capacity) {
            // transcript events are the only thing we may sacrifice; drop and say so once
            if (event instanceof TranscriptEvent.Chunk c) {
                droppedEvents.incrementAndGet();
                droppedChars.addAndGet(c.text().length());
                if (laggedPublished.compareAndSet(false, true)) {
                    offer(new TranscriptEvent.Lagged(droppedEvents.get()));
                }
                return;
            }
        }
        offer(event);
    }

    private void offer(TranscriptEvent event) {
        queue.add(event);
        queued.incrementAndGet();
    }

    private void recordPublish(long t0) {
        long dt = System.nanoTime() - t0;
        lastPublishNanos.set(dt);
        maxPublishNanos.accumulateAndGet(dt, Math::max);
    }

    // ── EDT side ──────────────────────────────────────────────────────────────

    /**
     * Start coalescing updates. Must be called on the EDT; {@code flushHz} is the ceiling on
     * widget updates per second regardless of how fast fragments arrive.
     */
    public void startFlushTimer() {
        if (timer != null) {
            return;
        }
        Timer t = new Timer(1000 / DEFAULT_FLUSH_HZ, e -> flushOnce());
        t.setRepeats(true);
        t.setCoalesce(true);
        timer = t;
        t.start();
    }

    public void stopFlushTimer() {
        Timer t = timer;
        if (t != null) {
            t.stop();
        }
    }

    /** Drain one bounded batch and hand it to the listener. Called on the EDT. */
    public int flushOnce() {
        long t0 = System.nanoTime();
        List<TranscriptEvent> batch = new ArrayList<>();
        // one flush may never drain the whole queue: at 50 000 lines the unbounded drain applied
        // ~33 000 events in one EDT turn and cost 39 ms, which breaks the §4.4 frame budget under
        // a flood. Whatever is left waits for the next tick, in order.
        int budget = Math.min(capacity, MAX_EVENTS_PER_FLUSH);
        TranscriptEvent e;
        String mergeId = null;
        StringBuilder mergeText = null;
        NaruLogMode mergeMode = null;
        long mergeTaskId = NaruOutput.NO_TASK;
        boolean mergeDirective = false;
        while (budget-- > 0 && (e = queue.poll()) != null) {
            queued.decrementAndGet();
            if (e instanceof TranscriptEvent.Chunk c
                    && mergeId != null && mergeId.equals(c.blockId()) && c.mode() == mergeMode
                    && c.taskId() == mergeTaskId
                    && mergeText.length() + c.text().length() <= MAX_MERGE_CHARS) {
                mergeText.append(c.text());
                if (c.finalFragment()) {
                    batch.add(new TranscriptEvent.Chunk(mergeId, mergeMode, mergeText.toString(),
                            true, mergeTaskId, mergeDirective));
                    mergeId = null;
                    mergeText = null;
                    mergeMode = null;
                    mergeTaskId = NaruOutput.NO_TASK;
                    mergeDirective = false;
                }
                continue;
            }
            if (e instanceof TranscriptEvent.Chunk c) {
                // a run never spans two blocks, and text never overtakes a later event
                flushMerge(batch, mergeId, mergeMode, mergeText, mergeTaskId, mergeDirective);
                mergeId = null;
                mergeText = null;
                mergeMode = null;
                mergeTaskId = NaruOutput.NO_TASK;
                mergeDirective = false;
                if (c.finalFragment()) {
                    batch.add(c);
                } else {
                    mergeId = c.blockId();
                    mergeMode = c.mode();
                    mergeTaskId = c.taskId();
                    mergeDirective = c.directiveResult();
                    mergeText = new StringBuilder(c.text());
                }
                continue;
            }
            flushMerge(batch, mergeId, mergeMode, mergeText, mergeTaskId, mergeDirective);
            mergeId = null;
            mergeText = null;
            mergeMode = null;
            mergeTaskId = NaruOutput.NO_TASK;
            mergeDirective = false;
            batch.add(e);
        }
        flushMerge(batch, mergeId, mergeMode, mergeText, mergeTaskId, mergeDirective);
        TranscriptEvent outOfBand;
        while ((outOfBand = inputEvents.poll()) != null) {
            batch.add(outOfBand);
        }
        long dt = System.nanoTime() - t0;
        lastFlushNanos.set(dt);
        maxFlushNanos.accumulateAndGet(dt, Math::max);
        flushCount.incrementAndGet();
        flushedEvents.addAndGet(batch.size());
        if (!batch.isEmpty()) {
            Listener l = listener;
            if (l != null) {
                l.onBatch(batch, this);
            }
        }
        return batch.size();
    }

    /** Publish a half-merged run as a non-final chunk so a later event cannot overtake it. */
    private static void flushMerge(List<TranscriptEvent> batch, String mergeId, NaruLogMode mergeMode,
                                   StringBuilder mergeText, long mergeTaskId, boolean mergeDirective) {
        if (mergeText != null && mergeText.length() > 0) {
            // a merged run is always streamed fragments, and the engine neither streams nor merges
            // an error: text fragments carry no severity
            batch.add(new TranscriptEvent.Chunk(mergeId, mergeMode, mergeText.toString(), false, mergeTaskId,
                    mergeDirective, false));
        }
    }

    /** Drain everything, synchronously. For tests and for shutdown. */
    public int drainAllOnEdt() {
        int total = 0;
        int n;
        while ((n = flushOnce()) > 0) {
            total += n;
        }
        return total;
    }

    // ── measurements ──────────────────────────────────────────────────────────

    public long publishedEvents() {
        return publishedEvents.get();
    }

    /** Tool-call lifecycle events published, whatever the queue then did with them. */
    public long toolCallEvents() {
        return toolCalls.get();
    }

    public long droppedEvents() {
        return droppedEvents.get();
    }

    public long droppedChars() {
        return droppedChars.get();
    }

    public long flushedEvents() {
        return flushedEvents.get();
    }

    public long flushCount() {
        return flushCount.get();
    }

    public long maxFlushMillis() {
        return maxFlushNanos.get() / 1_000_000;
    }

    public long lastFlushMillis() {
        return lastFlushNanos.get() / 1_000_000;
    }

    public long maxPublishMicros() {
        return maxPublishNanos.get() / 1_000;
    }

    public int queueDepth() {
        return queued.get();
    }

    public boolean isClosed() {
        return closed;
    }

    public NaruInputRequest pendingInput() {
        return pendingInput.get();
    }

    public NaruSession session() {
        return sessionRef.get();
    }

    public String statsLine() {
        return "published=" + publishedEvents()
                + " flushedBatches=" + flushCount()
                + " flushedEvents=" + flushedEvents()
                + " dropped=" + droppedEvents()
                + " droppedChars=" + droppedChars()
                + " maxFlushMs=" + maxFlushMillis()
                + " maxPublishUs=" + maxPublishMicros()
                + " queueDepth=" + queueDepth();
    }
}
