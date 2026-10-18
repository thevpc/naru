package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.desktop.model.ToolCallModel;
import net.thevpc.naru.desktop.util.PermissionsText;
import net.thevpc.naru.desktop.util.ToolCardText;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;

/**
 * The Tools tab (plan §5): every tool call this activation ran, newest at the bottom, with the
 * whole result available on click.
 * <p>
 * The reason this exists instead of "read the transcript" is E1. A console user gets
 * {@code abbreviate(result, 300)} and cannot tell a short result from a truncated one; here the
 * detail pane shows the text the tool returned, in full, and the list line states the character
 * count so a collapsed card in the transcript is verifiable rather than mysterious.
 * <p>
 * It consumes {@link ToolCallModel}, never a session or an engine type, so it renders headless in
 * a test and a future front end can share the model.
 */
public final class ToolsPane extends JPanel implements ToolCallModel.Listener {

    private final ToolCallModel model;
    private final DefaultListModel<ToolCallModel.Row> rows = new DefaultListModel<>();
    private final JList<ToolCallModel.Row> list = new JList<>(rows);
    private final JTextArea detail = TextAreas.readOnly();
    private final JLabel note = new JLabel(" ");
    private final javax.swing.JButton toPermissions = new javax.swing.JButton("Permission rule…");
    /** Opens the Permissions tab on the rule that denied this call, set by the window. */
    private java.util.function.Consumer<String> onJumpToPermissions;
    /** So a new call can follow the tail without stealing a selection the user is reading. */
    private int previousRows;
    private long taskFilter = -1;

    public ToolsPane(ToolCallModel model) {
        super(new BorderLayout());
        this.model = model;
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new RowRenderer());
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        list.addListSelectionListener(e -> showDetail());
        detail.setText("Select a call to see its arguments and its full result.");
        note.setFont(note.getFont().deriveFont(Font.PLAIN, 11f));
        note.setForeground(Color.GRAY);
        note.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

        // the jump plan §6 promises: a denial row leads to the rule that caused it, so the fix is
        // one click away instead of a search through /permission output
        toPermissions.setToolTipText("Open the Permissions tab on the rule that produced this denial");
        toPermissions.setEnabled(false);
        toPermissions.addActionListener(e -> {
            if (onJumpToPermissions == null) {
                return;
            }
            ToolCallModel.Row r = list.getSelectedValue();
            onJumpToPermissions.accept(r == null ? null : PermissionsText.matchedRuleId(r.result()));
        });
        JPanel bar = new JPanel(new BorderLayout());
        bar.add(toPermissions, BorderLayout.WEST);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        add(bar, BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new javax.swing.JScrollPane(list), new javax.swing.JScrollPane(detail));
        split.setResizeWeight(0.45);
        add(split, BorderLayout.CENTER);
        add(note, BorderLayout.SOUTH);
        model.addListener(this);
        toolCallsChanged();
    }

    @Override
    public void toolCallsChanged() {
        java.util.List<ToolCallModel.Row> current = model.rows().stream()
                .filter(r -> taskFilter >= 0 && r.taskId() == taskFilter).toList();
        // rows are records replaced in place, so the object the user selected is the identity to
        // re-find after a reload — an index would jump to another call when a trim shifts the front
        ToolCallModel.Row wasSelected = list.getSelectedValue();
        rows.clear();
        for (ToolCallModel.Row r : current) {
            rows.addElement(r);
        }
        if (wasSelected != null) {
            int idx = rows.indexOf(wasSelected);
            if (idx >= 0) {
                list.setSelectedIndex(idx);
                // the row the user is reading may have just settled: redraw what it holds now
                showDetail();
            }
        } else if (rows.size() > previousRows && !rows.isEmpty()) {
            list.ensureIndexIsVisible(rows.size() - 1);
        }
        previousRows = rows.size();
        int n = current.size();
        note.setText(n + " call" + (n == 1 ? "" : "s")
                + (model.droppedRows() > 0 ? " · " + model.droppedRows() + " older calls dropped" : "")
                + (n >= ToolCallModel.MAX_ROWS ? " · list capped at " + ToolCallModel.MAX_ROWS : ""));
        if (list.getSelectedIndex() < 0) {
            showDetail();
        }
    }

    /** The whole result, uncapped — this pane is where "untruncated" is actually delivered. */
    private void showDetail() {
        ToolCallModel.Row r = list.getSelectedValue();
        if (r == null) {
            detail.setText(rows.isEmpty()
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
        sb.append("  · ").append(r.status()).append('\n');
        sb.append("task #").append(r.taskId());
        if (r.callId() != null && !r.callId().isBlank()) {
            sb.append("  · call ").append(r.callId());
        }
        sb.append('\n');
        sb.append("arguments  ").append(ToolCardText.arguments(r.arguments())).append("\n\n");
        if (r.result() == null) {
            sb.append("(no result yet — the call is still running)");
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
            toPermissions.setText(id == null ? "Open Permissions" : "Rule " + id + "…");
        } else {
            toPermissions.setEnabled(false);
        }
        detail.setText(sb.toString());
        detail.setCaretPosition(0);
    }

    public void dispose() {
        model.removeListener(this);
    }

    /**
     * Install the window's jump: select a permission denial in the list, press the button, and the
     * Permissions tab opens on the rule that caused it. The id may be null — a denial that came from
     * the default effect has no rule — and the window still switches tabs.
     */
    public void setJumpToPermissions(java.util.function.Consumer<String> jump) {
        this.onJumpToPermissions = jump;
        showDetail();
    }

    public void setTaskFilter(long taskId) {
        taskFilter = taskId;
        toolCallsChanged();
    }

    private static String line(ToolCallModel.Row r) {
        String name = r.toolName() == null || r.toolName().isBlank() ? "(unnamed)" : r.toolName();
        String d = r.measuredDuration();
        return "#" + r.taskId() + "  " + name
                + (d != null ? "  · " + d : "")
                + "  · " + r.status()
                + (PermissionsText.isDenial(r.result()) ? " (denied)" : "")
                + (r.result() == null ? "" : "  · " + r.resultChars() + " chars");
    }

    private static final class RowRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                      boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(l, line((ToolCallModel.Row) value),
                    index, selected, focus);
            label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            label.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
            if (!selected) {
                label.setForeground(colour((ToolCallModel.Row) value, l));
            }
            return label;
        }

        private Color colour(ToolCallModel.Row r, JList<?> l) {
            Color base = switch (r.status()) {
                case OK -> new Color(60, 140, 90);
                case FAILED -> new Color(190, 80, 70);
                case WAITING -> new Color(200, 150, 50);
                case RUNNING -> new Color(80, 130, 200);
            };
            return dark(l) ? lighten(base) : base;
        }

        private boolean dark(JList<?> l) {
            Color c = l.getBackground();
            return c.getRed() + c.getGreen() + c.getBlue() < 384;
        }

        private Color lighten(Color c) {
            return new Color(Math.min(255, c.getRed() + 90), Math.min(255, c.getGreen() + 90),
                    Math.min(255, c.getBlue() + 90));
        }
    }
}
