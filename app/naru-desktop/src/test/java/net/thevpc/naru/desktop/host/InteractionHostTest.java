package net.thevpc.naru.desktop.host;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NMsg;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host contract, tested without a session or a widget (plan §8). These are the properties the
 * transcript relies on: fragments of one block keep their order and are not dropped under normal
 * load, input requests survive a saturated queue, and closing the host answers nothing by leaving
 * a question cancelled rather than parked.
 */
class InteractionHostTest {

    /** A question that records what happened to it. */
    private static final class FakeRequest implements NaruInputRequest {
        final AtomicReference<String> delivered = new AtomicReference<>();
        final AtomicReference<String> cancelled = new AtomicReference<>();

        @Override
        public NMsg prompt() {
            return NMsg.ofC("choose");
        }

        @Override
        public NaruTask task() {
            return null;
        }

        @Override
        public void deliver(String line) {
            delivered.compareAndSet(null, line);
        }

        @Override
        public void cancel(String reason) {
            cancelled.compareAndSet(null, reason);
        }
    }

    private static void onEdt(Runnable body) throws Exception {
        SwingUtilities.invokeAndWait(body);
    }

    @Test
    void fragmentsOfOneBlockAreMergedInOrder() throws Exception {
        InteractionHost host = new InteractionHost("test", 1000, null);
        List<TranscriptEvent> seen = new ArrayList<>();
        host.setListener((batch, h) -> seen.addAll(batch));
        for (int i = 0; i < 500; i++) {
            host.writeStream(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("tok%d ", i), i == 499);
        }
        onEdt(host::flushOnce);
        long chunks = seen.stream().filter(e -> e instanceof TranscriptEvent.Chunk).count();
        assertEquals(1, chunks, "one flush turns 500 fragments into one update");
        TranscriptEvent.Chunk c = (TranscriptEvent.Chunk) seen.stream()
                .filter(e -> e instanceof TranscriptEvent.Chunk).findFirst().orElseThrow();
        assertTrue(c.text().startsWith("tok0 "), "order preserved at the front");
        assertTrue(c.text().contains("tok499"), "order preserved at the end");
        assertTrue(c.finalFragment());
        assertEquals(0, host.droppedEvents());
    }

    @Test
    void aWholeMessageClosesTheStreamingBlockFirst() throws Exception {
        InteractionHost host = new InteractionHost("test", 1000, null);
        List<TranscriptEvent> seen = new ArrayList<>();
        host.setListener((batch, h) -> seen.addAll(batch));
        onEdt(() -> {
            host.writeStream(NaruLogMode.MODEL_THINKING, NMsg.ofC("half a thought"), false);
            host.write(NaruLogMode.SCRIPT, NMsg.ofC("an unrelated log line"));
            host.flushOnce();
        });
        List<String> kinds = new ArrayList<>();
        for (TranscriptEvent e : seen) {
            kinds.add(e.getClass().getSimpleName() + ":" + mode(e));
        }
        assertEquals(List.of("Chunk:MODEL_THINKING", "Closed:null", "Chunk:SCRIPT"), kinds,
                "a log line never lands inside a half-drawn sentence");
    }

    private static String mode(TranscriptEvent e) {
        if (e instanceof TranscriptEvent.Chunk c) {
            return String.valueOf(c.mode());
        }
        return null;
    }

    @Test
    void saturationDropsChunksButNeverAnInputRequest() throws Exception {
        InteractionHost host = new InteractionHost("test", 8, null);
        List<TranscriptEvent> seen = new ArrayList<>();
        host.setListener((batch, h) -> seen.addAll(batch));
        for (int i = 0; i < 200; i++) {
            host.write(NaruLogMode.SCRIPT, NMsg.ofC("line %d", i));
        }
        FakeRequest r = new FakeRequest();
        host.requestInput(r);
        assertTrue(host.droppedEvents() > 0, "transcript chunks are the only sacrifice");
        onEdt(host::drainAllOnEdt);
        boolean sawInput = seen.stream().anyMatch(e -> e instanceof TranscriptEvent.InputRequested);
        boolean sawLag = seen.stream().anyMatch(e -> e instanceof TranscriptEvent.Lagged);
        assertTrue(sawInput, "a lost question strands a worker forever");
        assertTrue(sawLag, "and the UI is told the transcript is incomplete");
    }

    @Test
    void closeCancelsThePendingQuestionRatherThanParkingIt() throws Exception {
        InteractionHost host = new InteractionHost("test", 100, null);
        FakeRequest r = new FakeRequest();
        host.requestInput(r);
        assertNotNull(host.pendingInput());
        onEdt(host::close);
        assertNull(host.pendingInput());
        assertNotNull(r.cancelled.get(), "close() must release the parked task");
        assertNull(r.delivered.get(), "…by cancelling, not by inventing an answer");
        assertTrue(host.isClosed());
    }

    @Test
    void publishingFromManyWorkersStaysBoundedAndOrderedPerBlock() throws Exception {
        InteractionHost host = new InteractionHost("test", 50_000, null);
        AtomicInteger chunks = new AtomicInteger();
        List<TranscriptEvent> all = new ArrayList<>();
        host.setListener((batch, h) -> {
            for (TranscriptEvent e : batch) {
                if (e instanceof TranscriptEvent.Chunk) {
                    chunks.incrementAndGet();
                }
                all.add(e);
            }
        });
        int threads = 4;
        int each = 250;
        CountDownLatch go = new CountDownLatch(threads);
        List<Thread> ws = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            Thread w = new Thread(() -> {
                for (int i = 0; i < each; i++) {
                    host.write(NaruLogMode.SCRIPT, NMsg.ofC("t%d line %d", tid, i));
                }
                go.countDown();
            });
            ws.add(w);
            w.start();
        }
        for (Thread w : ws) {
            w.join();
        }
        go.await();
        int flushed = 0;
        while (true) {
            int[] n = new int[1];
            onEdt(() -> n[0] = host.flushOnce());
            if (n[0] == 0) {
                break;
            }
            flushed += n[0];
        }
        assertEquals(threads * each, chunks.get(), "nothing lost at the default capacity");
        assertEquals(0, host.droppedEvents());
        assertEquals(0, host.queueDepth(), "and the queue drains");
        // every whole message is its own closed block, which is what lets markdown render once
        long closes = all.stream().filter(e -> e instanceof TranscriptEvent.Closed).count();
        assertEquals(threads * each - 1, closes, "one close per block, none duplicated");
    }
}
