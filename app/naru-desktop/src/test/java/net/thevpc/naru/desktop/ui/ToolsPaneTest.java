package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruToolCallEvent;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.desktop.host.TranscriptEvent;
import net.thevpc.naru.desktop.model.ToolCallModel;
import net.thevpc.naru.desktop.model.DrawnCalls;
import net.thevpc.naru.desktop.util.EDT;
import net.thevpc.naru.desktop.util.PermissionsText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import javax.swing.table.TableModel;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Tools table as an object, built off-screen.
 * <p>
 * {@code ToolCallModelTest} proves what the store holds; this proves the things only the widget can
 * get wrong: that the table is ordered newest first and follows its own head, that a call the reader
 * opened is kept across a reload, that a column sort reorders the rows it is supposed to, that the
 * task column and the task filter agree, that a number still fits the column it sits in, and that an
 * enormous result is capped instead of freezing the window.
 */
class ToolsPaneTest {

    /** Column indexes, mirroring {@link ToolsPane}'s private constants. */
    private static final int TOOL = 0;
    private static final int STATE = 1;
    private static final int TOOK = 2;
    private static final int OUT = 3;
    private static final int TASK = 4;

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

    private static List<Component> walk(Container c) {
        List<Component> out = new ArrayList<>();
        for (Component ch : c.getComponents()) {
            out.add(ch);
            if (ch instanceof Container inner) {
                out.addAll(walk(inner));
            }
        }
        return out;
    }

    private static JTable table(Container c) {
        return walk(c).stream().filter(JTable.class::isInstance).map(JTable.class::cast)
                .findFirst().orElseThrow(() -> new IllegalStateException("no JTable in " + c));
    }

    private static JTextField search(Container c) {
        return walk(c).stream().filter(JTextField.class::isInstance).map(JTextField.class::cast)
                .findFirst().orElseThrow(() -> new IllegalStateException("no filter field in " + c));
    }

    private static TranscriptEvent started(long task, String id, String name) {
        return new TranscriptEvent.ToolCall(
                NaruToolCallEvent.started(task, new NaruToolCall(id, name, Map.of("cmd", "ls"))));
    }

    private static TranscriptEvent result(long task, String id, String name, String text, long ms) {
        return new TranscriptEvent.ToolCall(
                NaruToolCallEvent.result(task, new NaruToolCall(id, name, Map.of()), text, ms));
    }

    private static TranscriptEvent denial(long task, String id, String name) {
        return new TranscriptEvent.ToolCall(NaruToolCallEvent.failed(task,
                new NaruToolCall(id, name, Map.of()),
                PermissionsText.DENIAL_PREFIX + " write file  matched rule: deny-write (source=core)",
                5));
    }

    /**
     * The value of one column in view row {@code view}, so a test can read what the reader sees.
     * Through the table model on purpose: {@code JTable.getValueAt} takes a view row, so asking it
     * for a converted model index converts the index a second time and reports another row.
     */
    private static Object at(JTable t, int view, int column) {
        return t.getModel().getValueAt(t.convertRowIndexToModel(view), column);
    }

    @Test
    void theHeadIsTheNewestCallAndItIsSelected() throws Exception {
        ToolCallModel model = new ToolCallModel(new DrawnCalls());
        ToolsPane pane = new ToolsPane(model);
        JTable t = table(pane);
        onEdt(() -> model.apply(List.of(started(6, "c1", "bash"))));
        onEdt(() -> model.apply(List.of(result(6, "c1", "bash", "one", 40),
                started(6, "c2", "read_file"))));
        onEdt(() -> model.apply(List.of(result(6, "c2", "read_file", "two", 90),
                started(7, "c3", "grep"))));
        assertEquals(3, t.getRowCount(), "one row per call, whatever its state");
        assertEquals("grep", at(t, 0, TOOL), "newest first, like the requests table");
        assertEquals("read_file", at(t, 1, TOOL));
        assertEquals(7L, at(t, 0, TASK), "listing every task means saying which one ran each call");
        assertEquals(0, t.getSelectedRow(), "a reader at the head is carried to each new call");
    }

    @Test
    void aChosenCallSurvivesTheNextOne() throws Exception {
        ToolCallModel model = new ToolCallModel(new DrawnCalls());
        ToolsPane pane = new ToolsPane(model);
        JTable t = table(pane);
        onEdt(() -> {
            model.apply(List.of(started(6, "c1", "bash"), result(6, "c1", "bash", "one", 40),
                    started(6, "c2", "read_file"), result(6, "c2", "read_file", "two", 90)));
            t.setRowSelectionInterval(1, 1);
        });
        ToolCallModel.Row chosen = model.rows().get(0);
        onEdt(() -> model.apply(List.of(started(6, "c3", "grep"), result(6, "c3", "grep", "three", 10))));
        List<ToolCallModel.Row> newestFirst = new ArrayList<>(model.rows());
        java.util.Collections.reverse(newestFirst);
        onEdt(() -> assertEquals("bash", at(t, t.getSelectedRow(), TOOL),
                "a call the reader opened must not be swapped for the one that just landed"));
        assertSame(chosen, newestFirst.get(t.convertRowIndexToModel(t.getSelectedRow())));
        assertEquals(2, t.getSelectedRow(), "the same row sits one place lower in a newest-first table");
    }

    @Test
    void sortingByDurationBringsTheSlowestFirst() throws Exception {
        ToolCallModel model = new ToolCallModel(new DrawnCalls());
        ToolsPane pane = new ToolsPane(model);
        JTable t = table(pane);
        onEdt(() -> model.apply(List.of(
                result(6, "a", "bash", "fast", 30),
                result(6, "b", "grep", "slow", 4000),
                result(6, "c", "read_file", "middle", 400))));
        assertEquals("read_file", at(t, 0, TOOL), "no sort key means the table's own newest-first order");
        onEdt(() -> {
            @SuppressWarnings("unchecked")
            RowSorter<TableModel> sorter = (RowSorter<TableModel>) t.getRowSorter();
            sorter.setSortKeys(List.of(new RowSorter.SortKey(TOOK, SortOrder.DESCENDING)));
        });
        assertEquals(4000L, at(t, 0, TOOK));
        assertEquals("grep", at(t, 0, TOOL));
        assertEquals("read_file", at(t, 1, TOOL));
        assertEquals("bash", at(t, 2, TOOL));
    }

    @Test
    void oneTaskNarrowsTheTableAndDropsItsOwnColumn() throws Exception {
        ToolCallModel model = new ToolCallModel(new DrawnCalls());
        ToolsPane pane = new ToolsPane(model);
        JTable t = table(pane);
        onEdt(() -> model.apply(List.of(
                result(6, "a", "bash", "one", 30),
                result(6, "b", "grep", "two", 40),
                result(7, "c", "read_file", "three", 50))));
        onEdt(() -> {
            assertEquals(5, t.getColumnCount());
            assertEquals(3, t.getRowCount());
            pane.setTaskFilter(6);
            assertEquals(4, t.getColumnCount(), "a column that holds one value is dead width");
            assertEquals(2, t.getRowCount(), "and the table lists only the selected task's calls");
            pane.setTaskFilter(-1);
            assertEquals(5, t.getColumnCount());
            assertEquals(3, t.getRowCount(), "no task selected is every task, not nothing");
        });
    }

    @Test
    void aDenialReadsAsDenyAndSortsAboveAnOkCall() throws Exception {
        ToolCallModel model = new ToolCallModel(new DrawnCalls());
        ToolsPane pane = new ToolsPane(model);
        JTable t = table(pane);
        // the denial lands first, so the newest-first order puts it second: the row a plain
        // alphabetical sort would leave under "fail" and "ok"
        onEdt(() -> model.apply(List.of(denial(6, "b", "write_file"),
                result(6, "a", "bash", "fine", 30))));
        assertEquals("ok", at(t, 0, STATE));
        assertEquals("deny", at(t, 1, STATE));
        onEdt(() -> {
            @SuppressWarnings("unchecked")
            RowSorter<TableModel> sorter = (RowSorter<TableModel>) t.getRowSorter();
            sorter.setSortKeys(List.of(new RowSorter.SortKey(STATE, SortOrder.ASCENDING)));
        });
        assertEquals("deny", at(t, 0, STATE), "sorting by state is done to find trouble");
        assertEquals("write_file", at(t, 0, TOOL));
    }

    @Test
    void theFilterSearchesTheResultTextNotJustTheToolName() throws Exception {
        ToolCallModel model = new ToolCallModel(new DrawnCalls());
        ToolsPane pane = new ToolsPane(model);
        JTable t = table(pane);
        JTextField box = search(pane);
        onEdt(() -> model.apply(List.of(
                result(6, "a", "bash", "no such file or directory", 30),
                result(6, "b", "grep", "42 matches", 40))));
        onEdt(() -> box.setText("no such file"));
        assertEquals(1, t.getRowCount());
        assertEquals("bash", at(t, 0, TOOL));
        assertEquals(0, t.getSelectedRow(), "narrowing to a call must also open it below");
        onEdt(() -> box.setText("#7"));
        assertEquals(0, t.getRowCount(), "a task with no calls leaves nothing to select");
        assertTrue(t.getSelectedRow() < 0);
        onEdt(() -> box.setText(""));
        assertEquals(2, t.getRowCount());
        assertTrue(t.getSelectedRow() >= 0, "rows exist, so the detail zone has something to show");
    }

    @Test
    void theWorstCaseNumbersStillFitTheirColumns() throws Exception {
        ToolCallModel model = new ToolCallModel(new DrawnCalls());
        ToolsPane pane = new ToolsPane(model);
        JTable t = table(pane);
        onEdt(() -> model.apply(List.of(
                result(6, "a", "bash", "y".repeat(1_400_000), 720_000),
                result(6, "b", "grep", "x".repeat(9_213), 9_999),
                result(6, "c", "read_file", "z".repeat(999_499), 599_999))));
        // newest first: c, b, a
        assertEquals("600s", cell(t, 0, TOOK));
        assertEquals("999k", cell(t, 0, OUT));
        assertEquals("10.0s", cell(t, 1, TOOK));
        assertEquals("9213", cell(t, 1, OUT), "no thousands separator: it is what clipped the cell");
        assertEquals("12m", cell(t, 2, TOOK));
        assertEquals("1.4M", cell(t, 2, OUT));
        for (int row = 0; row < 3; row++) {
            assertTrue(cell(t, row, TOOK).length() <= 5 && cell(t, row, OUT).length() <= 5,
                    "five glyphs is all a 280px rail gives a number");
        }
    }

    @Test
    void aHugeResultPastesOnlyWhatOneFrameCanPaint() throws Exception {
        ToolCallModel model = new ToolCallModel(new DrawnCalls());
        ToolsPane pane = new ToolsPane(model);
        JTable t = table(pane);
        String huge = "x".repeat(150_000);
        onEdt(() -> model.apply(List.of(result(6, "c1", "bash", huge, 40))));
        JTextArea detail = walk(pane).stream().filter(JTextArea.class::isInstance)
                .map(JTextArea.class::cast).findFirst().orElseThrow();
        onEdt(() -> {
            assertEquals(0, t.getSelectedRow());
            assertTrue(detail.getText().length() < huge.length(),
                    "a wrapped megabyte is a freeze, not a paint");
            assertTrue(detail.getText().contains("of 150000 characters"),
                    "the cap has to say how much it hid");
        });
    }

    /** The string one column actually draws, taken from the renderer that draws it. */
    private static String cell(JTable t, int view, int column) {
        return ((JLabel) t.getCellRenderer(view, column).getTableCellRendererComponent(
                t, t.getValueAt(view, column), false, false, view, column)).getText();
    }
}
