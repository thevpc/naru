package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruToolCallEvent;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.desktop.host.TranscriptEvent;
import net.thevpc.naru.desktop.util.EDT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Tools tab's model, headless. What it guarantees is what the plan asks of the panel: one row
 * per call, its state, and the whole result — until a stated cap evicts whole rows and counts it.
 */
class ToolCallModelTest {

    @BeforeEach
    void strict() {
        EDT.setStrict(true);
    }

    @AfterEach
    void relaxed() {
        EDT.setStrict(false);
    }

    private static void onEdt(ThrowingRunnable body) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                body.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static NaruToolCall call(String id, String name) {
        return new NaruToolCall(id, name, Map.of("cmd", "ls"));
    }

    private static TranscriptEvent started(long task, String id, String name) {
        return new TranscriptEvent.ToolCall(NaruToolCallEvent.started(task, call(id, name)));
    }

    private static TranscriptEvent result(long task, String id, String name, String text, long ms) {
        return new TranscriptEvent.ToolCall(NaruToolCallEvent.result(task, call(id, name), text, ms));
    }

    @Test
    void aCallGoesThroughItsStatesInOneRow() throws Exception {
        onEdt(() -> {
            ToolCallModel m = new ToolCallModel();
            m.apply(List.of(started(6, "c1", "bash")));
            assertEquals(1, m.size());
            assertEquals(ToolCallModel.Status.RUNNING, m.rowAt(0).status());
            assertNull(m.rowAt(0).result(), "nothing has come back yet, so nothing is shown");
            m.apply(List.of(new TranscriptEvent.ToolCall(
                    NaruToolCallEvent.parked(6, call("c1", "bash")))));
            assertEquals(1, m.size());
            assertEquals(ToolCallModel.Status.WAITING, m.rowAt(0).status());
            m.apply(List.of(result(6, "c1", "bash", "file.txt", 40)));
            assertEquals(1, m.size());
            ToolCallModel.Row r = m.rowAt(0);
            assertEquals(ToolCallModel.Status.OK, r.status());
            assertEquals("file.txt", r.result());
            assertEquals(40, r.durationMillis());
            assertEquals("40 ms", r.measuredDuration());
            assertEquals(6, r.taskId());
            assertEquals(Map.of("cmd", "ls"), r.arguments());
        });
    }

    @Test
    void twoTasksUsingTheSameCallIdAreTwoRows() throws Exception {
        onEdt(() -> {
            ToolCallModel m = new ToolCallModel();
            // models reuse ids like "0" across tasks; the task is part of the key for exactly this
            m.apply(List.of(started(1, "0", "echo"), started(2, "0", "echo")));
            m.apply(List.of(result(1, "0", "echo", "one", 5), result(2, "0", "echo", "two", 5)));
            assertEquals(2, m.size());
            assertEquals("one", m.rowAt(0).result());
            assertEquals("two", m.rowAt(1).result());
            assertEquals(ToolCallModel.Status.OK, m.rowAt(0).status());
        });
    }

    @Test
    void aFailureWithoutAStartStillGetsARow() throws Exception {
        onEdt(() -> {
            ToolCallModel m = new ToolCallModel();
            m.apply(List.of(new TranscriptEvent.ToolCall(NaruToolCallEvent.failed(
                    9, call("c7", "secret_tool"), "ERROR: permission denied by rule 'deny-secret'", 2))));
            assertEquals(1, m.size());
            assertEquals(ToolCallModel.Status.FAILED, m.rowAt(0).status());
            assertTrue(m.rowAt(0).result().contains("permission denied"),
                    "the rule that denied it is shown, not abbreviated away");
        });
    }

    @Test
    void theHostClosingSettlesWhateverIsStillRunning() throws Exception {
        onEdt(() -> {
            ToolCallModel m = new ToolCallModel();
            m.apply(List.of(started(3, "c1", "slow"), started(3, "c2", "slow")));
            m.apply(List.of(result(3, "c1", "slow", "back", 10)));
            m.apply(List.of(new TranscriptEvent.HostClosed("session closed")));
            assertEquals(ToolCallModel.Status.OK, m.rowAt(0).status(), "a settled row is not rewritten");
            assertEquals(ToolCallModel.Status.FAILED, m.rowAt(1).status());
            assertTrue(m.rowAt(1).result().contains("no result"));
        });
    }

    @Test
    void theRowCapEvictsWholeRowsAndSaysSo() throws Exception {
        int n = ToolCallModel.MAX_ROWS + 25;
        List<TranscriptEvent> batch = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            batch.add(result(1, "c" + i, "echo", "r" + i, 1));
        }
        onEdt(() -> {
            ToolCallModel m = new ToolCallModel();
            m.apply(batch);
            assertEquals(ToolCallModel.MAX_ROWS, m.size(), "the list is bounded");
            assertEquals(25, m.droppedRows(), "and the eviction is counted, not silent");
            assertEquals("c" + (n - 1), m.rowAt(m.size() - 1).callId(), "the newest call survives");
        });
    }

    @Test
    void anOversizedResultEvictsHistoryButNeverTheCurrentCall() throws Exception {
        String huge = "y".repeat(ToolCallModel.MAX_RETAINED_RESULT_CHARS + 1024);
        onEdt(() -> {
            ToolCallModel m = new ToolCallModel();
            m.apply(List.of(result(1, "c1", "read", "small", 1)));
            m.apply(List.of(new TranscriptEvent.ToolCall(
                    NaruToolCallEvent.failed(1, call("c2", "read"), huge, 3))));
            assertEquals(1, m.size(), "the older row is evicted to pay for the new one");
            assertEquals(1, m.droppedRows());
            assertNotNull(m.rowAt(0).result());
            assertEquals(huge.length(), m.rowAt(0).resultChars(),
                    "the call that just finished is shown whole, however big it is");
        });
    }

    @Test
    void aPlainTextChunkIsNotAToolCall() throws Exception {
        onEdt(() -> {
            ToolCallModel m = new ToolCallModel();
            m.apply(List.of(new TranscriptEvent.Chunk("b1", NaruLogMode.SCRIPT, "output", true, 1)));
            assertEquals(0, m.size(), "the panel lists calls, not output lines");
        });
    }
}
