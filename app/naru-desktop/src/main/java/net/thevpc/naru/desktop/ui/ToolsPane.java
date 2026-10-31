package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.desktop.model.ToolCallModel;
import net.thevpc.naru.desktop.util.PermissionsText;
import net.thevpc.naru.desktop.util.ToolCardText;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Tools tab (plan §5): every tool call this activation ran, newest at the top, with the whole
 * result available on click.
 * <p>
 * The reason this exists instead of "read the transcript" is E1. A console user gets
 * {@code abbreviate(result, 300)} and cannot tell a short result from a truncated one; here the
 * detail zone shows the text the tool returned, in full, and the table states the character count
 * so a collapsed card in the transcript is verifiable rather than mysterious.
 * <p>
 * It is a table, not a list, because the questions worth asking about a run of tool calls are
 * aggregations over columns — which call was slowest, which tool failed, how much text came back —
 * and a list of one prose line per call answers none of them without reading every line. So each
 * column holds one value, right-aligned where the value is a number, and every column sorts.
 * <p>
 * It consumes {@link ToolCallModel}, never a session or an engine type, so it renders headless in a
 * test and a future front end can share the model.
 */
public final class ToolsPane extends JPanel implements ToolCallModel.Listener {

    private static final int COL_TOOL = 0;
    private static final int COL_STATE = 1;
    private static final int COL_TOOK = 2;
    private static final int COL_OUT = 3;
    private static final int COL_TASK = 4;

    /**
     * Widths measured against the monospaced body face at 100 % interface size: the widest value the
     * column can hold, plus cell padding, plus the room a sort arrow takes out of a header. The rail
     * this pane lives in is about 280 pixels wide, so the pinned columns together must leave the tool
     * name something to be read in.
     */
    private static final int WIDTH_TOOL = 170;
    private static final int WIDTH_TOOL_MIN = 66;
    private static final int WIDTH_STATE = 52;
    private static final int WIDTH_TOOK = 64;
    private static final int WIDTH_OUT = 52;
    private static final int WIDTH_TASK = 52;
    /**
     * The rail width from which the task column is worth showing. Below it the four fixed columns
     * would leave the tool name about eight characters, and a name is the one value a reader has to
     * be able to tell apart without hovering.
     */
    private static final int WIDTH_SHOW_TASK_FROM = 330;

    private final ToolCallModel model;
    private final RowTable rows = new RowTable();
    private final JTable table = new JTable(rows);
    private final TableRowSorter<RowTable> sorter = new TableRowSorter<>(rows);
    private final JTextArea detail = TextAreas.readOnly();
    private final JScrollPane tableScroll = new JScrollPane(table);
    private final JLabel note = new JLabel(" ");
    private final JTextField search = new JTextField(12);
    private final JButton resetSort = new JButton();
    private final JButton toPermissions = new JButton();
    private final TableColumn taskColumn;
    /** Opens the Permissions tab on the rule that denied this call, set by the window. */
    private Consumer<String> onJumpToPermissions;
    private long taskFilter = -1;

    public ToolsPane(ToolCallModel model) {
        super(new BorderLayout());
        this.model = model;
        taskColumn = buildTable();
        buildBar();
        JSplitPane split = SplitPanes.vertical(tableScroll, new JScrollPane(detail));
        split.setResizeWeight(0.45);
        split.setDividerLocation(0.45);
        add(split, BorderLayout.CENTER);
        tableScroll.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                updateTaskColumn();
            }
        });
        updateTaskColumn();
        UiFonts.apply(note, UiFonts.CAPTION, Font.PLAIN);
        note.setForeground(UiPalette.muted());
        note.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        add(note, BorderLayout.SOUTH);
        model.addListener(this);
        toolCallsChanged();
    }

    private TableColumn buildTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.putClientProperty("JTable.autoStartsEdit", Boolean.FALSE);
        UiFonts.applyMono(table, UiFonts.BODY, Font.PLAIN);
        table.setRowHeight(table.getFontMetrics(table.getFont()).getHeight() + 4);
        // sorting by state is done to find trouble, so the trouble sorts to the top
        sorter.setComparator(COL_STATE, Comparator.comparingInt(ToolsPane::rankState));
        table.setRowSorter(sorter);
        // the header's words are short out of necessity, so each one says what it counts on hover
        JTableHeader header = new JTableHeader(table.getColumnModel()) {
            @Override
            public String getToolTipText(java.awt.event.MouseEvent e) {
                int at = columnAtPoint(e.getPoint());
                return at < 0 ? null : headerTip(table.convertColumnIndexToModel(at));
            }
        };
        header.setReorderingAllowed(false);
        table.setTableHeader(header);
        TableColumnModel columns = table.getColumnModel();
        for (int i = 0; i < columns.getColumnCount(); i++) {
            TableColumn column = columns.getColumn(i);
            column.setPreferredWidth(switch (i) {
                case COL_STATE -> WIDTH_STATE;
                case COL_TOOK -> WIDTH_TOOK;
                case COL_OUT -> WIDTH_OUT;
                case COL_TASK -> WIDTH_TASK;
                default -> WIDTH_TOOL;
            });
            column.setCellRenderer(new Cell(i));
            if (i == COL_TOOL) {
                column.setMinWidth(WIDTH_TOOL_MIN);
            } else {
                // pinned, so the numbers keep their columns and the tool name takes all the slack
                column.setMinWidth(column.getPreferredWidth());
                column.setMaxWidth(column.getPreferredWidth());
            }
        }
        TableColumn task = columns.getColumn(COL_TASK);
        table.getSelectionModel().addListSelectionListener(e -> showDetail());
        sorter.addRowSorterListener(e -> {
            if (e.getType() == javax.swing.event.RowSorterEvent.Type.SORTED) {
                resetSort.setEnabled(!sorter.getSortKeys().isEmpty());
            }
            // a sort moves rows underneath the cursor, so the detail zone must redraw what is
            // selected now rather than keep showing what used to be under that index
            showDetail();
        });
        return task;
    }

    private static String headerTip(int column) {
        return switch (column) {
            case COL_TOOL -> "The tool the model asked for. Select a row to read its arguments and "
                    + "its full result below.";
            case COL_STATE -> "run: dispatched, no answer back yet · wait: an interactive tool is "
                    + "parked until you answer · ok: returned · fail: ended in the engine's error "
                    + "shape · deny: refused by a permission rule";
            case COL_TOOK -> "Wall clock between the call starting and settling, as this desktop "
                    + "measured it; hover a value for the exact milliseconds. Blank until it settles.";
            case COL_OUT -> "Characters of result text returned; hover a value for the exact count. "
                    + "Blank while the call is still running, 0 when it really returned nothing.";
            case COL_TASK -> "The task that ran the call. Hidden while the table is narrowed to one task.";
            default -> null;
        };
    }

    private void buildBar() {
        JPanel bar = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0));
        glyph(resetSort, "↺", "Clear the column sorting — back to newest first",
                "Clear column sorting");
        resetSort.setEnabled(false);
        resetSort.addActionListener(e -> {
            sorter.setSortKeys(null);
            // sorting by a column moves the head out of sight; coming back is worth a scroll
            followHead();
        });
        left.add(resetSort);
        // the jump plan §6 promises: a denial row leads to the rule that caused it, so the fix is
        // one click away instead of a search through /permission output
        glyph(toPermissions, "→",
                "Open the Permissions tab on the rule that produced this denial",
                "Open the matching permission rule");
        toPermissions.setEnabled(false);
        toPermissions.addActionListener(e -> {
            if (onJumpToPermissions == null) {
                return;
            }
            ToolCallModel.Row r = selected();
            onJumpToPermissions.accept(r == null ? null : PermissionsText.matchedRuleId(r.result()));
        });
        left.add(toPermissions);
        bar.add(left, BorderLayout.WEST);
        search.putClientProperty("JTextField.placeholderText", "Filter tool, state, #task, text…");
        search.setToolTipText("Match the tool name, a task id like #2, a state word, "
                + "or words from the arguments or the result");
        UiFonts.apply(search, UiFonts.SECONDARY, Font.PLAIN);
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });
        bar.add(search, BorderLayout.CENTER);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        add(bar, BorderLayout.NORTH);
    }

    /**
     * A control that carries a mark, not a word: the words go in the tooltip, and the accessible name
     * keeps it findable by a test or a screen reader once the tooltip has been replaced by state text.
     */
    private static void glyph(JButton button, String mark, String tip, String accessibleName) {
        button.setText(mark);
        button.setToolTipText(tip);
        button.getAccessibleContext().setAccessibleName(accessibleName);
        button.setFocusable(false);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setForeground(UiPalette.muted());
        ActionButtons.glyphSized(button);
    }

    private void applyFilter() {
        String needle = search.getText().trim().toLowerCase(java.util.Locale.ROOT);
        long scoped = taskFilter;
        sorter.setRowFilter(needle.isEmpty() && scoped < 0 ? null : new RowFilter<RowTable, Integer>() {
            @Override
            public boolean include(Entry<? extends RowTable, ? extends Integer> entry) {
                ToolCallModel.Row row = entry.getModel().row(entry.getIdentifier());
                if (row == null) {
                    return false;
                }
                // the selected task narrows the table on its own; the filter box adds to it, so a
                // reader inside one task still finds the call that printed the error they are chasing
                return (scoped < 0 || row.taskId() == scoped)
                        && (needle.isEmpty() || searchable(row, needle));
            }
        });
        // the detail zone shows whatever is selected, so a filter that leaves rows must leave one of
        // them chosen: narrowing to the call you were hunting for is the whole point of the box
        if (table.getRowCount() > 0 && table.getSelectedRow() < 0) {
            followHead();
        }
    }

    /**
     * What one row can be found by. The result text is capped: matching a five-megabyte tool output
     * on every keystroke costs more than the keystroke is worth, and the first kilobytes carry the
     * error line anyone would search for.
     */
    private static boolean searchable(ToolCallModel.Row r, String needle) {
        if (r == null) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        sb.append('#').append(r.taskId()).append(' ')
                .append(r.toolName() == null ? "" : r.toolName()).append(' ')
                .append(stateCode(r)).append(' ');
        String d = r.measuredDuration();
        sb.append(d == null ? "" : d).append(' ');
        if (r.result() != null) {
            sb.append(r.resultChars()).append(" chars ");
            sb.append(r.result(), 0, Math.min(r.result().length(), 2000));
        }
        if (PermissionsText.isDenial(r.result())) {
            sb.append(" denied permission");
        }
        sb.append(' ').append(ToolCardText.arguments(r.arguments()));
        return sb.toString().toLowerCase(java.util.Locale.ROOT).contains(needle);
    }

    @Override
    public void toolCallsChanged() {
        List<ToolCallModel.Row> current = new ArrayList<>(model.rows());
        java.util.Collections.reverse(current);
        // rows are records replaced in place, so the object the user selected is the identity to
        // re-find after a reload — a view index would move to another call when a trim shifts the front
        ToolCallModel.Row wasSelected = selected();
        boolean atHead = table.getSelectedRow() == 0;
        rows.setRows(current);
        applyFilter();
        if (wasSelected == null || atHead) {
            // either nobody has chosen a row, or the reader is sitting on the head and wants the run
            // to keep moving under them; restoring identity here would pin them to one settled call
            followHead();
        } else {
            int at = current.indexOf(wasSelected);
            // the row may have been evicted by the caps or filtered away: fall back to the head
            // rather than leave nothing selected while the note says calls exist
            if (at < 0 || !selectModelRow(at)) {
                followHead();
            }
        }
        int shown = table.getRowCount();
        long failed = current.stream().filter(r -> r.status() == ToolCallModel.Status.FAILED).count();
        long denied = current.stream().filter(r -> PermissionsText.isDenial(r.result())).count();
        StringBuilder summary = new StringBuilder();
        String noun = current.size() == 1 ? " call" : " calls";
        summary.append(shown == current.size()
                ? current.size() + noun : shown + " of " + current.size() + noun);
        if (failed > 0) {
            summary.append(" · ").append(failed).append(" failed");
        }
        if (denied > 0) {
            summary.append(" · ").append(denied).append(" denied");
        }
        if (model.droppedRows() > 0) {
            summary.append(" · ").append(model.droppedRows()).append(" older calls dropped");
        }
        if (current.size() >= ToolCallModel.MAX_ROWS) {
            summary.append(" · list capped at ").append(ToolCallModel.MAX_ROWS);
        }
        note.setText(summary.toString());
        showDetail();
    }

    /** Point the table at the head of its own order — while a run is going, that is the live row. */
    private void followHead() {
        if (table.getRowCount() == 0) {
            table.getSelectionModel().clearSelection();
            return;
        }
        table.getSelectionModel().setSelectionInterval(0, 0);
        table.scrollRectToVisible(table.getCellRect(0, 0, true));
    }

    /**
     * Select a row by its index in the table model, or clear the selection for {@code -1}. False when
     * the active filter hides it.
     */
    private boolean selectModelRow(int modelRow) {
        if (modelRow < 0) {
            table.getSelectionModel().clearSelection();
            return false;
        }
        int view = table.convertRowIndexToView(modelRow);
        if (view < 0) {
            return false;
        }
        table.getSelectionModel().setSelectionInterval(view, view);
        return true;
    }

    private ToolCallModel.Row selected() {
        int view = table.getSelectedRow();
        // a filter that empties the table fires the sorter while the selection model still reports the
        // row it used to hold, and converting that index throws — the selection is simply gone
        if (view < 0 || view >= table.getRowCount()) {
            return null;
        }
        return rows.row(table.convertRowIndexToModel(view));
    }

    /**
     * What the detail zone pastes. The model retains up to 4 MB of a result so the columns, the
     * filter and this count all see the whole call, but a wrapped document that size is not a paint
     * — RSyntaxTextArea spends minutes laying out its wrapped lines and the window stops answering
     * clicks while it does, and past 1 MB it throws out of the caret repaint. The cap is far above
     * anything a reader scrolls through and low enough to render in one frame.
     */
    private static final int MAX_DETAIL_CHARS = 100_000;

    /** The result, up to what one frame can paint; the rest is counted, not dropped silently. */
    private void showDetail() {
        ToolCallModel.Row r = selected();
        if (r == null) {
            detail.setText(rows.getRowCount() == 0
                    ? "No tool calls yet."
                    : "Select a call to see its arguments and its full result.");
            detail.setCaretPosition(0);
            toPermissions.setEnabled(false);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(r.toolName() == null ? "(unnamed tool)" : r.toolName());
        String d = r.measuredDuration();
        if (d != null) {
            sb.append("  · ").append(d);
        }
        sb.append("  · ").append(stateCode(r)).append('\n');
        sb.append("task #").append(r.taskId());
        if (r.callId() != null && !r.callId().isBlank()) {
            sb.append("  · call ").append(r.callId());
        }
        sb.append('\n');
        sb.append("arguments  ").append(ToolCardText.arguments(r.arguments())).append("\n\n");
        boolean truncated = r.result() != null && r.result().length() > MAX_DETAIL_CHARS;
        if (r.result() == null) {
            sb.append("(no result yet — the call is still running)");
        } else if (truncated) {
            sb.append(r.result(), 0, MAX_DETAIL_CHARS);
        } else {
            sb.append(r.result());
        }
        if (PermissionsText.isDenial(r.result())) {
            // say which rule fired in the header too: the engine puts it at the end of a long result,
            // and the whole point of §3.3 is that a denial is legible without reading the tail
            String id = PermissionsText.matchedRuleId(r.result());
            sb.append("\n\n── denied by ")
                    .append(id == null ? "the default effect (no rule matched)" : "rule " + id)
                    .append(" — the Permissions tab edits it");
            toPermissions.setEnabled(onJumpToPermissions != null);
            toPermissions.setToolTipText(id == null
                    ? "Open the Permissions tab — this denial came from the default effect"
                    : "Open the Permissions tab on rule " + id);
        } else {
            toPermissions.setEnabled(false);
        }
        // last, so the line that says what to do is never the one the cap pushed off the end
        if (truncated) {
            sb.append("\n\n── showing the first ").append(MAX_DETAIL_CHARS).append(" of ")
                    .append(r.result().length()).append(" characters");
        }
        detail.setText(sb.toString());
        detail.setCaretPosition(0);
    }

    public void dispose() {
        model.removeListener(this);
    }

    /**
     * Install the window's jump: select a permission denial in the table, press the button, and the
     * Permissions tab opens on the rule that caused it. The id may be null — a denial that came from
     * the default effect has no rule — and the window still switches tabs.
     */
    public void setJumpToPermissions(Consumer<String> jump) {
        this.onJumpToPermissions = jump;
        showDetail();
    }

    /** {@code -1} lists every task; anything else narrows the table to that one task. */
    public void setTaskFilter(long taskId) {
        if (taskFilter == taskId) {
            return;
        }
        taskFilter = taskId;
        updateTaskColumn();
        toolCallsChanged();
    }

    /**
     * The task column shows when it can pay for itself: it distinguishes rows only while several tasks
     * are listed, and when the rail is narrow its fifty pixels are worth more to the tool name. A pane
     * that has not been laid out yet is not yet narrow, so it keeps the column until it is measured.
     */
    private void updateTaskColumn() {
        boolean cramped = tableScroll.getWidth() > 0 && tableScroll.getWidth() < WIDTH_SHOW_TASK_FROM;
        boolean wanted = taskFilter < 0 && !cramped;
        if (wanted == taskColumnShown()) {
            return;
        }
        if (wanted) {
            // task is the right-most column, so appending it restores the layout it was removed from
            table.getColumnModel().addColumn(taskColumn);
        } else {
            table.getColumnModel().removeColumn(taskColumn);
        }
    }

    /** Whether the task column is in the view, decided by membership and not by its model index. */
    private boolean taskColumnShown() {
        TableColumnModel columns = table.getColumnModel();
        for (int i = 0; i < columns.getColumnCount(); i++) {
            if (columns.getColumn(i) == taskColumn) {
                return true;
            }
        }
        return false;
    }

    private static String stateCode(ToolCallModel.Row r) {
        if (PermissionsText.isDenial(r.result())) {
            return "deny";
        }
        return switch (r.status()) {
            case OK -> "ok";
            case FAILED -> "fail";
            case WAITING -> "wait";
            case RUNNING -> "run";
        };
    }

    private static int rankState(String code) {
        return switch (code) {
            case "deny" -> 0;
            case "fail" -> 1;
            case "wait" -> 2;
            case "run" -> 3;
            default -> 4;
        };
    }

    /**
     * The hue a state is drawn in, or null when the state is the quiet one: a call that simply
     * returned needs no colour, so {@code ok} keeps the look-and-feel's ink.
     */
    private static Color stateColour(String code) {
        return switch (code) {
            case "deny", "fail" -> UiPalette.ATTENTION;
            case "wait" -> UiPalette.PENDING;
            case "run" -> UiPalette.LIVE;
            default -> null;
        };
    }

    /** The table's rows, newest first, with one value per column so every column can sort. */
    private static final class RowTable extends AbstractTableModel {
        private final String[] titles = {"tool", "state", "took", "out", "task"};
        private List<ToolCallModel.Row> rows = List.of();

        void setRows(List<ToolCallModel.Row> next) {
            rows = next;
            fireTableDataChanged();
        }

        ToolCallModel.Row row(int modelRow) {
            return modelRow >= 0 && modelRow < rows.size() ? rows.get(modelRow) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return titles.length; }
        @Override public String getColumnName(int c) { return titles[c]; }

        @Override
        public Class<?> getColumnClass(int c) {
            return switch (c) {
                case COL_TOOL, COL_STATE -> String.class;
                case COL_OUT -> Integer.class;
                default -> Long.class;
            };
        }

        @Override
        public Object getValueAt(int r, int c) {
            ToolCallModel.Row row = rows.get(r);
            return switch (c) {
                case COL_TOOL -> row.toolName() == null || row.toolName().isBlank()
                        ? "(unnamed)" : row.toolName();
                case COL_STATE -> stateCode(row);
                case COL_TOOK -> millis(row);
                case COL_OUT -> row.result() == null ? null : row.resultChars();
                default -> row.taskId();
            };
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }

        /** null means nothing was measured: an unsettled call, or one the engine gave no time for. */
        private static Long millis(ToolCallModel.Row row) {
            if (row.durationMillis() >= 0) {
                return row.durationMillis();
            }
            if (row.startedAt() == null || row.settledAt() == null) {
                return null;
            }
            return java.time.Duration.between(row.startedAt(), row.settledAt()).toMillis();
        }
    }

    /** Draws one value: formatted, aligned, and coloured only where the value is a state. */
    private static final class Cell extends DefaultTableCellRenderer {
        private final int column;

        Cell(int column) {
            this.column = column;
            setHorizontalAlignment(column == COL_TOOL || column == COL_STATE
                    ? SwingConstants.LEFT : SwingConstants.RIGHT);
            setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                                                       boolean focus, int row, int column) {
            super.getTableCellRendererComponent(t, text(value), selected, focus, row, column);
            // a renderer is not a child of the table, so it wears its own font unless it is told
            setFont(t.getFont());
            setToolTipText(tip(column, value));
            if (column == COL_STATE && !selected) {
                Color hue = stateColour((String) value);
                if (hue != null) {
                    setForeground(UiPalette.readable(hue));
                }
            }
            return this;
        }

        /** What the column had to shorten: the name in full, the count exactly. */
        private static String tip(int column, Object value) {
            if (column == COL_OUT && value instanceof Integer chars) {
                return chars + " characters";
            }
            if (column == COL_TOOL && value instanceof String name) {
                return name;
            }
            // the cell trades precision for width, so the number it rounded is what hovers
            if (column == COL_TOOK && value instanceof Long millis) {
                return millis + " ms";
            }
            return null;
        }

        private String text(Object value) {
            if (value == null) {
                return "";
            }
            return switch (column) {
                case COL_TOOK -> shortDuration((Long) value);
                case COL_OUT -> shortChars((Integer) value);
                default -> String.valueOf(value);
            };
        }

        /**
         * The table's own duration format: five characters is all the column gets in a 280px rail,
         * and the space in {@link ToolCardText#duration} alone pushes "300 ms" past it. The full
         * spelling belongs to the detail zone, where there is room for it.
         */
        private static String shortDuration(long millis) {
            if (millis < 1_000) {
                return millis + "ms";
            }
            if (millis < 10_000) {
                return String.format(java.util.Locale.ROOT, "%.1fs", millis / 1000.0);
            }
            if (millis < 600_000) {
                return Math.round(millis / 1000.0) + "s";
            }
            long minutes = Math.round(millis / 60_000.0);
            return minutes < 100 ? minutes + "m" : Math.round(minutes / 60.0) + "h";
        }

        /** Never more than four glyphs; {@link #tip} carries the exact count. */
        private static String shortChars(int chars) {
            if (chars < 10_000) {
                return Integer.toString(chars);
            }
            if (chars < 999_500) {
                return Math.round(chars / 1000.0) + "k";
            }
            return String.format(java.util.Locale.ROOT, "%.1fM", chars / 1_000_000.0);
        }
    }
}
