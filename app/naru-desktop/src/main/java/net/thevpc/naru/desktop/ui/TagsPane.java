package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.desktop.model.NaruSessionModel;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the selected task is actually offered: every registered tool, its tags, and why it is or
 * is not on the model's tool list.
 *
 * <p>The tag chips live in the composer's strip — this panel deliberately does not repeat them.
 * Its job is the consequence: {@code findTools()} is the engine's single answer, but it only
 * names the survivors. Here every tool of the registry is listed with the gate that stopped it —
 * no granted tag, the prompt mode's veto, the task's own exclusion, its own relevance veto — so
 * "I disabled the tag and the tool is still there" has an on-screen answer.
 *
 * <p>The checkbox is {@code /tools exclude}: a per-task ban by name, the second gate, one that
 * tags can never express because it targets a single tool rather than a capability. Toggling it
 * fires the engine's exclusion event, which re-reads this list and relights the strip's count.
 */
public final class TagsPane extends JPanel implements NaruSessionModel.Listener {

    /** One registry tool with the gate that decides its visibility on the selected task. */
    private static final class Row {
        final NaruTool tool;
        final boolean visible;
        final boolean excluded;
        final String why;

        Row(NaruTool tool, boolean visible, boolean excluded, String why) {
            this.tool = tool;
            this.visible = visible;
            this.excluded = excluded;
            this.why = why;
        }

        /** Visible first, then excluded, then everything the gates stopped; alphabetical inside. */
        int rank() {
            if (visible) {
                return 0;
            }
            if (excluded) {
                return 1;
            }
            return why.startsWith("prompt mode") ? 2 : 3;
        }

        /**
         * The row's full story. The tag set lives here and in the composer's strip, not in the row
         * label: printing it in both places made the same vocabulary read twice on one screen.
         */
        String tip() {
            String tags = tool.tags().isEmpty() ? "none" : String.join(", ", new TreeSet<>(tool.tags()));
            return "<html><b>" + tool.name() + "</b><br>tags: " + tags + "<br>" + why
                    + "<br>" + (excluded ? "click to restore" : "click to exclude") + "</html>";
        }
    }

    private final NaruSessionModel model;
    private final DefaultListModel<Row> rows = new DefaultListModel<>();
    // a JList paints one shared renderer, so the tooltip has to be resolved from the row under the
    // cursor here — a tooltip set on the renderer component is never shown
    private final JList<Row> list = new JList<>(rows) {
        {
            setToolTipText("");
        }

        @Override
        public String getToolTipText(java.awt.event.MouseEvent e) {
            int index = locationToIndex(e.getPoint());
            if (index < 0) {
                return null;
            }
            Rectangle cell = getCellBounds(index, index);
            return cell != null && cell.contains(e.getPoint()) ? rows.get(index).tip() : null;
        }
    };
    private final JLabel note = new JLabel(" ");
    private long taskId = -1;

    public TagsPane(NaruSessionModel model) {
        super(new BorderLayout(0, 6));
        this.model = model;
        setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new RowRenderer());
        UiFonts.applyMono(list, UiFonts.SECONDARY, Font.PLAIN);
        // the toggle lives on the list, not on the renderer: a renderer component is shared and
        // always wears the last painted row's state, so an action attached to it can fire on the
        // wrong tool. A click resolves the row from its location instead.
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index < 0) {
                    return;
                }
                Rectangle cell = list.getCellBounds(index, index);
                if (cell == null || !cell.contains(e.getPoint())) {
                    return;
                }
                list.setSelectedIndex(index);
                toggle(rows.get(index));
            }
        });

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        JLabel title = new JLabel("Tools offered to the model, by gate");
        top.add(title, BorderLayout.WEST);
        JPanel quick = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        quick.setOpaque(false);
        JButton row = new JButton("↻");
        row.setToolTipText("Re-read the task's tool list and exclusions");
        row.getAccessibleContext().setAccessibleName("Refresh tool list");
        row.setFocusable(false);
        row.addActionListener(e -> refresh());
        ActionButtons.glyphSized(row);
        quick.add(row);
        top.add(quick, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        UiFonts.apply(note, UiFonts.CAPTION, Font.PLAIN);
        note.setForeground(UiPalette.muted());
        note.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(note, BorderLayout.NORTH);
        add(south, BorderLayout.SOUTH);

        add(new javax.swing.JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED), BorderLayout.CENTER);

        model.addListener(this);
        refresh();
    }

    public void setSelectedTaskId(long id) {
        taskId = id;
        refresh();
    }

    @Override
    public void sessionModelChanged() {
        refresh();
    }

    private NaruTask task() {
        NaruSession s = model.session();
        if (s == null || taskId < 0) {
            return null;
        }
        try {
            return s.findTask(taskId).orNull();
        } catch (RuntimeException stopped) {
            return null;
        }
    }

    /**
     * Rebuild the rows from the live task. The visible set is the engine's own
     * {@code findTools()} answer — this panel never re-implements the gate, it only explains the
     * tools the gate dropped, in the order the gate consults them.
     */
    private void refresh() {
        rows.clear();
        NaruTask t = task();
        if (t == null) {
            note.setText("Select a task to read the tools it is offered.");
            return;
        }
        Set<String> visibleNames = new TreeSet<>();
        try {
            t.findTools().forEach(d -> visibleNames.add(d.getName()));
        } catch (RuntimeException ignored) {
            // a task mid-teardown answers nothing; every row then reads as stopped
        }
        Set<String> excluded = t.findToolExclusions();
        Set<String> granted;
        try {
            granted = t.findToolTagNames();
        } catch (RuntimeException ignored) {
            granted = Set.of();
        }
        List<NaruTool> tools = new ArrayList<>();
        try {
            tools.addAll(t.session().registry().tools().values());
        } catch (RuntimeException ignored) {
            note.setText("The registry is not answering; the tool list is empty for now.");
            return;
        }
        List<Row> next = new ArrayList<>();
        for (NaruTool tool : tools) {
            boolean isVisible = visibleNames.contains(tool.name());
            boolean isExcluded = excluded.contains(tool.name());
            next.add(new Row(tool, isVisible, isExcluded, isVisible ? "visible" : why(t, tool, isExcluded, granted)));
        }
        next.sort((a, b) -> a.rank() != b.rank() ? Integer.compare(a.rank(), b.rank())
                : a.tool.name().compareToIgnoreCase(b.tool.name()));
        next.forEach(rows::addElement);
        long shown = next.stream().filter(r -> r.visible).count();
        note.setText("<html>" + shown + " of " + next.size() + " registered tools are offered to this task."
                + " The checkbox is the per-task exclusion gate (/tools exclude): a named ban that"
                + " outranks any tag grant.</html>");
    }

    /** Ban or un-ban one tool by name on the selected task; the engine event redraws the list. */
    private void toggle(Row row) {
        NaruTask t = task();
        if (t == null) {
            return;
        }
        try {
            if (row.excluded) {
                t.removeToolExclusion(row.tool.name());
            } else {
                t.addToolExclusion(row.tool.name());
            }
        } catch (RuntimeException ex) {
            DesktopDialog.showMessage(javax.swing.SwingUtilities.getWindowAncestor(this),
                    "Tool exclusions", String.valueOf(ex.getMessage()));
        }
        refresh();
    }

    /** The first gate that stopped this tool, in the order findTools() consults them. */
    private static String why(NaruTask t, NaruTool tool, boolean isExcluded, Set<String> granted) {
        try {
            if (!t.promptMode().acceptToolTags(tool.tags())) {
                return "prompt mode " + t.promptMode().name() + " vetoes its tags";
            }
        } catch (RuntimeException ignored) {
        }
        try {
            if (!tool.isRelevant(t)) {
                return "the tool's own relevance veto";
            }
        } catch (RuntimeException ignored) {
        }
        if (isExcluded) {
            return "excluded by name on this task";
        }
        Set<String> tt = tool.tags();
        if (tt.isEmpty()) {
            return "tag-less and not declared essential";
        }
        for (String tag : tt) {
            if (granted.contains(tag)) {
                return "stopped by another gate";
            }
        }
        return "no granted tag matches " + tt;
    }

    private final class RowRenderer extends JPanel implements javax.swing.ListCellRenderer<Row> {
        private final JCheckBox check = new JCheckBox();
        private final JLabel label = new JLabel();

        RowRenderer() {
            super(new BorderLayout());
            check.setOpaque(false);
            check.setFocusable(false);
            // the renderer's checkbox is a painted state, never a target: the list's mouse
            // listener owns the click, so a recycled renderer can no longer fire on the wrong row
            check.setCursor(java.awt.Cursor.getDefaultCursor());
            label.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 0));
            add(check, BorderLayout.WEST);
            add(label, BorderLayout.CENTER);
            setOpaque(true);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Row> l, Row value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            check.setSelected(value.excluded);
            label.setText(value.tool.name() + "  ·  " + value.why);
            java.awt.Color ink = value.visible ? UiPalette.LIVE : value.excluded
                    ? UiPalette.PENDING : UiPalette.muted();
            if (isSelected) {
                setBackground(l.getSelectionBackground());
                setForeground(l.getSelectionForeground());
                label.setForeground(l.getSelectionForeground());
            } else {
                setBackground(l.getBackground());
                setForeground(l.getForeground());
                label.setForeground(ink);
            }
            return this;
        }
    }
}
