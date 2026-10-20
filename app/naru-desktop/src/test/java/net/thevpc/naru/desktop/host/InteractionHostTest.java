package net.thevpc.naru.desktop.host;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruOutput;
import net.thevpc.naru.api.agent.NaruToolCallEvent;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NMsg;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        // one task and one mode: every whole message joins the same block rather than becoming
        // a one-line card of its own
        long distinctBlocks = all.stream()
                .filter(e -> e instanceof TranscriptEvent.Chunk)
                .map(e -> ((TranscriptEvent.Chunk) e).blockId())
                .distinct().count();
        assertEquals(1, distinctBlocks, "consecutive whole messages of one task share a block");
    }

    @Test
    void consecutiveWholeMessagesShareOneBlockUntilTheModeChanges() throws Exception {
        InteractionHost host = new InteractionHost("test", 1000, null);
        List<TranscriptEvent> seen = new ArrayList<>();
        host.setListener((batch, h) -> seen.addAll(batch));
        onEdt(() -> {
            host.write(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("first line"), 4);
            host.write(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("second line"), 4);
            host.write(NaruLogMode.PROGRESS, NMsg.ofC("a progress line"), 4);
            host.flushOnce();
        });
        List<TranscriptEvent> chunks = seen.stream()
                .filter(e -> e instanceof TranscriptEvent.Chunk).toList();
        assertEquals(3, chunks.size());
        String first = ((TranscriptEvent.Chunk) chunks.get(0)).blockId();
        String second = ((TranscriptEvent.Chunk) chunks.get(1)).blockId();
        String third = ((TranscriptEvent.Chunk) chunks.get(2)).blockId();
        assertEquals(first, second, "same mode and task: one row, not one row per line");
        assertTrue(!first.equals(third), "a mode change is still a block boundary");
        assertEquals(1, seen.stream().filter(e -> e instanceof TranscriptEvent.Closed).count(),
                "the previous block closes exactly once, at the boundary");
    }

    @Test
    void anExplicitBoundarySplitsSameModeOutputIntoTwoCards() throws Exception {
        // A failed turn parks its task with the AGENT_RESPONSE block still open and no final
        // fragment. The next directive logs the same mode on the same task, so without a boundary
        // its result is merged into that error text and reads as "nothing happened".
        InteractionHost host = new InteractionHost("test", 1000, null);
        List<TranscriptEvent> seen = new ArrayList<>();
        host.setListener((batch, h) -> seen.addAll(batch));
        onEdt(() -> {
            host.write(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("No model is selected."), 4);
            host.beginBlock();
            host.write(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("/home/user/project"), 4);
            host.flushOnce();
        });
        List<TranscriptEvent.Chunk> chunks = seen.stream()
                .filter(e -> e instanceof TranscriptEvent.Chunk)
                .map(e -> (TranscriptEvent.Chunk) e).toList();
        assertEquals(2, chunks.size(), "both lines are still published");
        assertTrue(!chunks.get(0).blockId().equals(chunks.get(1).blockId()),
                "the directive's output is its own card, not appended to the previous error");
        assertEquals(1, seen.stream().filter(e -> e instanceof TranscriptEvent.Closed).count(),
                "the forced boundary closes exactly once");
    }

    @Test
    void anExplicitBoundaryWithNothingOpenClosesNothing() throws Exception {
        // The common case: the previous turn already closed its block, so the boundary is a no-op
        // and must not publish a spurious Closed.
        InteractionHost host = new InteractionHost("test", 1000, null);
        List<TranscriptEvent> seen = new ArrayList<>();
        host.setListener((batch, h) -> seen.addAll(batch));
        onEdt(() -> {
            host.beginBlock();
            host.flushOnce();
        });
        assertTrue(seen.stream().noneMatch(e -> e instanceof TranscriptEvent.Closed),
                "nothing was open, so nothing closed");
    }

    // ── E1: the typed tool-call lifecycle ─────────────────────────────────────

    private static NaruToolCall call(String id, String name) {
        return new NaruToolCall(id, name, Map.of("path", "notes.txt"));
    }

    @Test
    void aToolCallKeepsItsPlaceInTheStreamAndIsNeverMergedAway() throws Exception {
        InteractionHost host = new InteractionHost("test", 1000, null);
        List<TranscriptEvent> seen = new ArrayList<>();
        host.setListener((batch, h) -> seen.addAll(batch));
        onEdt(() -> {
            host.writeStream(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("hal"), false);
            host.reportToolCall(NaruToolCallEvent.started(3, call("call-1", "file_read")));
            host.writeStream(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("lo"), true);
            host.flushOnce();
        });
        List<String> kinds = new ArrayList<>();
        for (TranscriptEvent e : seen) {
            kinds.add(e.getClass().getSimpleName());
        }
        assertEquals(List.of("Chunk", "ToolCall", "Chunk", "Closed"), kinds,
                "a card lands where the call happened, and a merged text run stops for it");
        assertEquals("hal", ((TranscriptEvent.Chunk) seen.get(0)).text(),
                "the half-run is published as-is rather than overtaken by the card");
    }

    @Test
    void saturationDropsChunksButNeverAToolCall() throws Exception {
        InteractionHost host = new InteractionHost("test", 8, null);
        List<TranscriptEvent> seen = new ArrayList<>();
        host.setListener((batch, h) -> seen.addAll(batch));
        int events = 0;
        for (int i = 0; i < 40; i++) {
            host.write(NaruLogMode.SCRIPT, NMsg.ofC("line %d", i));
            host.reportToolCall(NaruToolCallEvent.result(5, call("c" + i, "echo"), "ok", 3));
            events += 2;
        }
        assertTrue(host.droppedEvents() > 0, "the flood did drop transcript chunks");
        onEdt(host::drainAllOnEdt);
        long cards = seen.stream().filter(e -> e instanceof TranscriptEvent.ToolCall).count();
        assertEquals(40, cards, "a settled call with no card would leave the panel lying");
        assertEquals(40, host.toolCallEvents());
    }

    @Test
    void outputIsStampedWithTheTaskThatWroteIt() throws Exception {
        InteractionHost host = new InteractionHost("test", 1000, null);
        List<TranscriptEvent> seen = new ArrayList<>();
        host.setListener((batch, h) -> seen.addAll(batch));
        onEdt(() -> {
            // two tasks, same mode, interleaved: they must not share a block that claims one owner
            host.write(NaruLogMode.SCRIPT, NMsg.ofC("from one"), 1);
            host.write(NaruLogMode.SCRIPT, NMsg.ofC("from two"), 2);
            for (int i = 0; i < 20; i++) {
                host.writeStream(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("t%d ", i), false, 7);
            }
            host.writeStream(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("end"), true, 7);
            host.flushOnce();
        });
        List<TranscriptEvent.Chunk> chunks = new ArrayList<>();
        for (TranscriptEvent e : seen) {
            if (e instanceof TranscriptEvent.Chunk c) {
                chunks.add(c);
            }
        }
        assertEquals(3, chunks.size(), "two whole lines, plus 21 fragments of one task merged to one");
        assertEquals(1, chunks.get(0).taskId(), "the first line says which task wrote it");
        assertEquals(2, chunks.get(1).taskId());
        assertTrue(!chunks.get(0).blockId().equals(chunks.get(1).blockId()),
                "a task switch is a block boundary, not a guess");
        TranscriptEvent.Chunk merged = chunks.get(2);
        assertEquals(7, merged.taskId(), "and merging keeps the stamp");
        assertTrue(merged.text().startsWith("t0 ") && merged.text().contains("t19 "),
                "order inside a merged run survives: " + merged.text());
        assertTrue(merged.finalFragment());
        assertTrue(seen.stream().noneMatch(e -> e instanceof TranscriptEvent.Chunk c
                        && c.taskId() == NaruOutput.NO_TASK),
                "nothing unattributed slipped through a stamped path");
    }
}
