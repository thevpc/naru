package net.thevpc.naru.desktop.util;

import net.thevpc.naru.api.scheduler.NaruSchedulerMode;
import net.thevpc.naru.api.scheduler.NaruTaskMode;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.task.NaruTaskStackFrame;
import net.thevpc.naru.api.task.NaruTaskStackItem;
import net.thevpc.naru.desktop.model.NaruSessionModel.TaskDetail;
import net.thevpc.naru.desktop.model.NaruSessionModel.TaskRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The monitor's text, with the clock pinned.
 * <p>
 * The labels are what a user actually reads, and two of their properties only show up in a test:
 * a row built twice from the same task must produce the same string (otherwise the pane repaints on
 * every poll tick for nothing), and the frame lines must match what {@code /task frames} prints in
 * the terminal, because the same run inspected from two hosts should not look like two different
 * runs.
 */
class TaskTextTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    private static TaskRow row(long id, long parentId, NaruTaskStatus status, boolean held,
                               boolean fg, int depth, Instant created, String error) {
        return new TaskRow(id, "worker", status, fg, parentId, held, 3, created, error, depth);
    }

    @Test
    void aChildRowCarriesIndentFlagParentAndAge() {
        String label = TaskText.rowLabel(
                row(12, 7, NaruTaskStatus.RUNNING, true, false, 1, NOW.minusSeconds(5), null), NOW);
        assertEquals("   └ #12 RUNNING [H] ←#7 5s worker", label);
    }

    @Test
    void aRootRowCarriesNeitherIndentNorParent() {
        String label = TaskText.rowLabel(
                row(3, -1, NaruTaskStatus.BLOCKED_ON_INPUT, false, true, 0,
                        NOW.minusSeconds(90), null), NOW);
        assertEquals("#3 BLOCKED_ON_INPUT [F] 1m+30s worker", label,
                "the foreground marker is the only flag a plain waiting task shows");
    }

    @Test
    void anErrorIsAppendedRatherThanReplaced() {
        String label = TaskText.rowLabel(
                row(4, -1, NaruTaskStatus.KILLED, false, false, 0, NOW.minusSeconds(1),
                        "user pressed stop"), NOW);
        assertTrue(label.endsWith("— user pressed stop"), label);
        assertTrue(label.contains("KILLED"), label);
    }

    @Test
    void theSameTaskAlwaysRendersTheSameRow() {
        TaskRow r = row(9, -1, NaruTaskStatus.READY, false, false, 0, NOW.minusSeconds(2), null);
        assertEquals(TaskText.rowLabel(r, NOW), TaskText.rowLabel(r, NOW),
                "an unstable label would make the pane repaint its whole list every 500 ms");
        assertEquals("#9 READY 2s worker", TaskText.rowLabel(r, NOW),
                "no flags, no parent, no error — the bare row");
    }

    @Test
    void agesReadAsSpokenRatherThanAsDurations() {
        assertEquals("59s", TaskText.age(NOW.minusSeconds(59), NOW));
        assertEquals("1m", TaskText.age(NOW.minusSeconds(60), NOW));
        assertEquals("1m+30s", TaskText.age(NOW.minusSeconds(90), NOW));
        assertEquals("2h15m", TaskText.age(NOW.minusSeconds(2 * 3600 + 15 * 60), NOW));
        // a clock skew in the engine's favour is a real possibility across a long session; the row
        // must not turn into "-3s"
        assertEquals("0s", TaskText.age(NOW.plusSeconds(3), NOW));
    }

    private static Map<String, Object> vars() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("beta", 2);
        m.put("alpha", 1);
        return m;
    }

    @Test
    void framesUseTheTerminalsOwnLineShape() {
        String text = TaskText.frames(List.of(
                new NaruTaskStackFrame("main", 3, "/print hello", vars(), Map.of())));
        assertTrue(text.contains("[1] 3 <main> /print hello"), text);
        assertTrue(text.contains("params : 2"), text);
        assertTrue(text.indexOf("alpha = 1") < text.indexOf("beta = 2"),
                "params are sorted by key, the way /task frames prints them: " + text);
        assertTrue(text.contains("local vars : 0"), text);
    }

    @Test
    void anEmptyFrameListSaysSoInsteadOfPrintingNothing() {
        assertEquals("\nFrames\n  (none)\n", TaskText.frames(List.of()));
        assertEquals("\nStacktrace\n  (none)\n", TaskText.stacktrace(null));
    }

    @Test
    void stacktraceItemsKeepTheirOrder() {
        String text = TaskText.stacktrace(List.of(
                new NaruTaskStackItem("outer", 1, "/source a.naru"),
                new NaruTaskStackItem("inner", 2, "/call b.naru")));
        assertTrue(text.indexOf("[1] 1 <outer>") < text.indexOf("[2] 2 <inner>"), text);
    }

    private static TaskDetail detail(String error, String model) {
        return new TaskDetail(12, "worker", NaruTaskStatus.RUNNING, true, 7, false, 3,
                false, true, NOW.minusSeconds(10), null,
                NaruTaskMode.INTERACTIVE, NaruSchedulerMode.AUTO, model, error,
                List.of(new NaruTaskStackFrame("main", 0, "/print hello", Map.of(), Map.of())),
                List.of());
    }

    @Test
    void theDetailBlockAnswersIdentityStateAndStackInOneRead() {
        String text = TaskText.detail(detail(null, "fake-model"), NOW);
        assertTrue(text.startsWith("Task #12 · worker"), text);
        // the columns are padded, so a label/value pair is matched across the gap rather than by an
        // exact number of spaces — the padding is layout, the pairing is the claim
        assertTrue(text.matches("(?s).*\\bstatus\\s+RUNNING.*"), text);
        assertTrue(text.contains("#7"), text);
        assertTrue(text.contains("model fake-model"), text);
        assertTrue(text.contains("interrupt-requested"), text);
        assertTrue(text.contains("[1] 0 <main> /print hello"), text);
        assertFalse(text.matches("(?s).*\\berror\\s+\\S.*"),
                "a task that did not fail has nothing to report: " + text);
    }

    @Test
    void aTaskWithNoModelSaysNoneSelected() {
        // explicit-model-only: this is the state a freshly opened project really is in
        assertTrue(TaskText.detail(detail(null, null), NOW).contains("model none selected"));
    }

    @Test
    void anErrorIsShownWhenThereIsOne() {
        assertTrue(TaskText.detail(detail("user pressed stop", "m"), NOW)
                        .matches("(?s).*\\berror\\s+user pressed stop.*"),
                "the finished task's error belongs in the detail, not only in the row");
    }

    @Test
    void aTaskThatVanishedBetweenTheClickAndTheReadIsSaidOutLoud() {
        assertEquals("no task selected", TaskText.detail(null));
    }
}
