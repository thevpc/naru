package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.desktop.host.InteractionHost;
import net.thevpc.naru.desktop.util.EDT;
import net.thevpc.nuts.text.NMsg;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 spike 3 promoted to a module test (plan §8, owner decision G3 at the Phase 1 gate):
 * 50 000 lines across ~2 000 blocks, published by four threads as fast as they can through the
 * real {@link InteractionHost}, applied to the real {@link TranscriptModel} on the real EDT at the
 * production 30 Hz tick, with the Phase 0 budget asserted rather than eyeballed.
 * <p>
 * Deliberately headless: the widgets are measured by the Phase 1 gate harness, and a test that
 * needs a display is a test that gets skipped. What this protects is the invariant every Phase 2
 * panel depends on — however much output arrives, one drain-plus-apply fits inside one frame,
 * nothing is lost, and the transcript cannot grow without bound.
 * <p>
 * The load runs twice and only the second run is asserted, because the first drain of a cold JVM
 * measures the JIT rather than the design.
 */
class TranscriptLoadBudgetTest {

    private static final int LINES = 50_000;
    private static final int BLOCKS = 2_000;
    private static final int THREADS = 4;
    /** Plan §5 Phase 0: "streaming flush never exceeds one frame budget". */
    private static final long FRAME_BUDGET_MILLIS = 16;
    /** {@code InteractionHost.DEFAULT_FLUSH_HZ} is 30, so this is the tick the widgets get. */
    private static final long TICK_MILLIS = 33;

    /** One measured run: what the load cost, and what the transcript ended up holding. */
    private record Run(long maxDrainMillis, long wallMillis, int drains, InteractionHost host,
                       TranscriptModel model, long publishedChars, String missingTail) {
    }

    @Test
    void fiftyThousandLinesStayInsideTheFrameBudget() throws Exception {
        run();                              // warm up: JIT, the merger, the block cache
        Run r = run();                      // the asserted run

        assertEquals(0, r.host.droppedEvents(),
                "nothing may be lost at this size: " + r.host.statsLine());
        assertTrue(r.maxDrainMillis <= FRAME_BUDGET_MILLIS,
                "one drain-plus-apply cost " + r.maxDrainMillis + " ms on the EDT, the budget is "
                        + FRAME_BUDGET_MILLIS + " ms: " + r.host.statsLine());
        assertTrue(r.model.retainedChars() <= TranscriptModel.DEFAULT_MAX_CHARS,
                "the transcript grew past its trim budget: " + r.model.retainedChars() + " chars");
        assertTrue(r.model.totalBlocks() > 0, "the transcript stayed empty");
        // exact no-loss arithmetic: every published character is in the model, plus the newline
        // TranscriptModel appends to close each whole-message fragment. If a single event were
        // dropped or merged away, this number is off by that line.
        assertEquals(r.publishedChars + LINES, r.model.retainedChars(),
                "the transcript does not hold everything that was published: " + r.host.statsLine());
        assertNull(r.missingTail, "a publisher's last line never reached the transcript");
        System.out.println("TranscriptLoadBudgetTest lines=" + LINES + " blocks~" + BLOCKS
                + " threads=" + THREADS + " wall-ms=" + r.wallMillis + " drains=" + r.drains
                + " max-drain-ms=" + r.maxDrainMillis + " " + r.host.statsLine()
                + " transcript blocks=" + r.model.totalBlocks()
                + " retained-chars=" + r.model.retainedChars() + "/" + r.publishedChars
                + " trimmed-blocks=" + r.model.trimmedBlocks());
    }

    private Run run() throws Exception {
        EDT.setStrict(true);
        TranscriptModel model = new TranscriptModel();
        // capacity above the event count: at this size a drop would be a bug, not the guard rail
        // doing its job (the spike used 200 000 for the same reason)
        InteractionHost host = new InteractionHost("load-budget", 4 * LINES,
                (batch, h) -> model.apply(batch));
        AtomicLong publishedChars = new AtomicLong();
        String[] lastLine = new String[THREADS];

        long t0 = System.currentTimeMillis();
        List<Thread> publishers = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            final int tid = t;
            int perThread = LINES / THREADS;
            int linesPerBlock = Math.max(1, perThread / (BLOCKS / THREADS));
            Thread w = new Thread(() -> {
                NaruLogMode[] modes = NaruLogMode.values();
                for (int b = 0; b < perThread / linesPerBlock; b++) {
                    NaruLogMode mode = modes[(tid + b) % modes.length];
                    for (int l = 0; l < linesPerBlock; l++) {
                        String text = "line " + b + "/" + l + " thread " + tid
                                + " :: the quick brown fox jumps over the lazy dog";
                        publishedChars.addAndGet(text.length());
                        lastLine[tid] = text;
                        host.write(mode, NMsg.ofC(text));
                    }
                }
            }, "publisher-" + tid);
            w.setDaemon(true);
            publishers.add(w);
            w.start();
        }

        // the EDT side of the production loop: one bounded drain per tick, timed end to end,
        // because InteractionHost's own maxFlushMillis stops before the listener runs
        List<Long> drains = new ArrayList<>();
        long deadline = t0 + 180_000;
        while (System.currentTimeMillis() < deadline) {
            boolean anyAlive = publishers.stream().anyMatch(Thread::isAlive);
            long[] one = new long[1];
            SwingUtilities.invokeAndWait(() -> {
                long s = System.nanoTime();
                host.flushOnce();
                one[0] = (System.nanoTime() - s) / 1_000_000L;
            });
            drains.add(one[0]);
            if (!anyAlive && host.queueDepth() == 0) {
                break;
            }
            Thread.sleep(TICK_MILLIS);
        }
        for (Thread w : publishers) {
            w.join(10_000);
        }
        SwingUtilities.invokeAndWait(host::drainAllOnEdt);
        long wallMillis = System.currentTimeMillis() - t0;
        long maxDrain = drains.stream().mapToLong(Long::longValue).max().orElse(0);

        // the tail of every publisher must be in the transcript: that proves ordering survived the
        // merger and that the load did not quietly discard the most recent output
        String missing = null;
        for (int t = 0; t < THREADS; t++) {
            if (!containsLine(model, lastLine[t])) {
                missing = "thread " + t + ": " + lastLine[t];
            }
        }
        EDT.setStrict(false);
        return new Run(maxDrain, wallMillis, drains.size(), host, model,
                publishedChars.get(), missing);
    }

    /** A whole-message line can sit anywhere in its block, so the scan covers the transcript. */
    private static boolean containsLine(TranscriptModel model, String want) {
        if (want == null) {
            return false;
        }
        for (TranscriptBlock b : model.blocks()) {
            if (b.text().contains(want)) {
                return true;
            }
        }
        return false;
    }
}
