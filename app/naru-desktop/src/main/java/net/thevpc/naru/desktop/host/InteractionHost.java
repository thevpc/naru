package net.thevpc.naru.desktop.host;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
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

    private final AtomicLong publishedEvents = new AtomicLong();
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
        long t0 = System.nanoTime();
        String text = message == null ? "" : message.toString();
        synchronized (publishLock) {
            // a whole message closes any half-drawn streamed block first
            String closing = blocks.close();
            publish(closing == null ? null : new TranscriptEvent.Closed(closing));
            String id = blocks.openForWhole(mode);
            publish(new TranscriptEvent.Chunk(id, mode, text, true));
        }
        recordPublish(t0);
    }

    @Override
    public void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
        long t0 = System.nanoTime();
        String text = fragment == null ? "" : fragment.toString();
        synchronized (publishLock) {
            String id = blocks.openForStream(mode);
            publish(new TranscriptEvent.Chunk(id, mode, text, end));
            if (end) {
                String closing = blocks.close();
                publish(closing == null ? null : new TranscriptEvent.Closed(closing));
            }
        }
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
        int budget = capacity;
        TranscriptEvent e;
        String mergeId = null;
        StringBuilder mergeText = null;
        NaruLogMode mergeMode = null;
        while (budget-- > 0 && (e = queue.poll()) != null) {
            queued.decrementAndGet();
            if (e instanceof TranscriptEvent.Chunk c
                    && mergeId != null && mergeId.equals(c.blockId()) && c.mode() == mergeMode
                    && mergeText.length() + c.text().length() <= MAX_MERGE_CHARS) {
                mergeText.append(c.text());
                if (c.finalFragment()) {
                    batch.add(new TranscriptEvent.Chunk(mergeId, mergeMode, mergeText.toString(), true));
                    mergeId = null;
                    mergeText = null;
                    mergeMode = null;
                }
                continue;
            }
            if (e instanceof TranscriptEvent.Chunk c) {
                // start a new merge run; a final fragment needs no run at all
                if (c.finalFragment()) {
                    batch.add(c);
                } else {
                    mergeId = c.blockId();
                    mergeMode = c.mode();
                    mergeText = new StringBuilder(c.text());
                }
                continue;
            }
            batch.add(e);
        }
        if (mergeText != null && mergeText.length() > 0) {
            batch.add(new TranscriptEvent.Chunk(mergeId, mergeMode, mergeText.toString(), false));
        }
        TranscriptEvent in;
        while ((in = inputEvents.poll()) != null) {
            batch.add(in);
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
