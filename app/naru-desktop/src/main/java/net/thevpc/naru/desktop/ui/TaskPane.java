package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.util.TaskText;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JToggleButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.FlowLayout;
import java.util.List;

/**
 * The task monitor (plan §6: "shows live parallel tasks with interrupt/kill/hold").
 *
 * <p>Rows come from the session model's snapshot, never from {@code session.tasks()} inside this
 * widget: the poll in {@code NaruSessionModel} is the one place that reads the engine, at 2 Hz, and
 * the tree order and indent are computed there too ({@code TaskTree}), so the list cannot disagree
 * with anything else on screen about which tasks exist or who spawned whom.
 *
 * <p>The buttons are the GUI form of the terminal's {@code /task} subcommands and call the same
 * engine methods; nothing here is a desktop-only notion of stopping a task. Each action returns the
 * engine's own failure text if it fails, and the note line shows it — a monitor that silently
 * swallowed a refused kill would be worse than no monitor at all.
 */
public final class TaskPane extends JPanel implements NaruSessionModel.Listener {

    private final NaruSessionModel session;
    private final DefaultListModel<NaruSessionModel.TaskRow> rows = new DefaultListModel<>();
    private final JList<NaruSessionModel.TaskRow> list = new JList<>(rows);
    private final javax.swing.JTree taskTree = new javax.swing.JTree(new javax.swing.tree.DefaultMutableTreeNode("Tasks"));
    private final JPanel taskView = new JPanel(new java.awt.CardLayout());
    private final JTextArea detail = TextAreas.readOnly();
    private final JLabel note = new JLabel(" ");
    private final JLabel actionNote = new JLabel(" ");
    private final JButton holdButton = new JButton("Ⅱ");
    private final JButton interruptButton = new JButton("↻");
    private final JButton killButton = new JButton("■");
    private final JButton fgButton = new JButton("⇄");
    private final JLabel empty = new JLabel("no tasks running");
    private final JToggleButton allTasks = new JToggleButton();
    private final javax.swing.JTextField search = new javax.swing.JTextField(12);
    private boolean interactiveOnly = true;
    private java.util.function.LongConsumer taskSelection = id -> {};

    private long shownId = -1;
    private long detailRequest;
    private boolean detailLoading;

    public TaskPane(NaruSessionModel session) {
        super(new BorderLayout());
        this.session = session;
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((l, v, ix, sel, f) -> {
            JLabel label = new JLabel(TaskText.rowLabel(v));
            label.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            label.setForeground(color(v, l));
            label.setOpaque(true);
            label.setBackground(sel ? l.getSelectionBackground() : l.getBackground());
            return label;
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelected();
            }
        });
        taskTree.setRootVisible(false);
        taskTree.setShowsRootHandles(true);
        taskTree.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer() {
            @Override public java.awt.Component getTreeCellRendererComponent(javax.swing.JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
                Object u = value instanceof javax.swing.tree.DefaultMutableTreeNode n ? n.getUserObject() : null;
                if (u instanceof NaruSessionModel.TaskRow r) { setText((r.name()==null || r.name().isBlank()?"Task":r.name()) + "  ·  " + friendlyStatus(r.status())); java.awt.Color selectedColor=javax.swing.UIManager.getColor("Tree.selectionForeground"); setForeground(selected && selectedColor!=null?selectedColor:statusColor(r.status())); }
                return this;
            }
        });
        taskTree.addTreeSelectionListener(e -> showSelected());

        detail.setText("select a task to see its frames");
        JScrollPane listScroll = new JScrollPane(list);
        // the same lesson as the transcript: a JList is as wide as its widest cell, and a long task
        // name or an error string would otherwise push the detail pane and the buttons off screen
        listScroll.setMinimumSize(new java.awt.Dimension(240, 120));
        JScrollPane detailScroll = new JScrollPane(detail);
        detailScroll.setMinimumSize(new java.awt.Dimension(240, 80));
        empty.setHorizontalAlignment(SwingConstants.CENTER);
        empty.setForeground(Color.GRAY);
        taskView.add(listScroll, "list"); taskView.add(new JScrollPane(taskTree), "tree");

        JPanel listHeader = new JPanel(new BorderLayout());
        JLabel sessionTitle = new JLabel("  Tasks");
        sessionTitle.setHorizontalAlignment(SwingConstants.LEFT);
        sessionTitle.setForeground(Color.GRAY);
        allTasks.setIcon(javax.swing.UIManager.getIcon("Tree.openIcon"));
        allTasks.putClientProperty("JButton.buttonType", "toolBarButton");
        allTasks.setToolTipText("Show all tasks as a tree"); allTasks.getAccessibleContext().setAccessibleName("Show all tasks as a tree");
        allTasks.setFocusable(false); allTasks.setMargin(new java.awt.Insets(2,6,2,6));
        allTasks.addActionListener(e -> { interactiveOnly = !allTasks.isSelected(); ((java.awt.CardLayout)taskView.getLayout()).show(taskView, interactiveOnly?"list":"tree"); sessionTitle.setText(interactiveOnly?"Tasks":"All tasks"); sessionModelChanged(); });
        search.putClientProperty("JTextField.placeholderText", "Filter tasks…");
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { sessionModelChanged(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { sessionModelChanged(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { sessionModelChanged(); }
        });
        // The title row and the filter row used to be stacked, which stretched the pane for a
        // control that is a peer of the filter: the tree toggle sits at the filter's own level.
        JPanel heading = new JPanel(new BorderLayout(6, 0));
        heading.add(search, BorderLayout.CENTER);
        heading.add(allTasks, BorderLayout.EAST);
        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.add(sessionTitle, BorderLayout.WEST);
        listHeader.add(titleRow, BorderLayout.NORTH);
        listHeader.add(heading, BorderLayout.SOUTH);
        add(listHeader, BorderLayout.NORTH);
        JPanel upper = new JPanel(new BorderLayout()); upper.add(taskView,BorderLayout.CENTER); upper.add(empty,BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, detailScroll);
        split.setResizeWeight(0.55); split.setDividerLocation(220);
        add(split, BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);

        UiFonts.apply(note, UiFonts.CAPTION, Font.PLAIN);
        note.setForeground(Color.GRAY);
        UiFonts.apply(actionNote, UiFonts.CAPTION, Font.PLAIN);
        actionNote.setForeground(new Color(150, 110, 60));
        session.addListener(this);
        sessionModelChanged();
        updateButtons();
    }

    private JPanel buttons() {
        JPanel panel = new JPanel(new BorderLayout());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        // one button, two actions: the word on it is decided from the row's held flag, so the press
        // has to be decided the same way. Reading only the label would leave a button that says
        // "Resume" and calls hold() — the second half of the defect the §6 gate run found, and the
        // half a test that goes straight to taskAction cannot see.
        holdButton.addActionListener(e -> act(heldSelected()
                ? NaruSessionModel.TaskAction.RESUME : NaruSessionModel.TaskAction.HOLD));
        interruptButton.addActionListener(e -> act(NaruSessionModel.TaskAction.INTERRUPT));
        killButton.addActionListener(e -> act(NaruSessionModel.TaskAction.KILL));
        fgButton.addActionListener(e -> act(foregroundSelected()
                ? NaruSessionModel.TaskAction.BACKGROUND : NaruSessionModel.TaskAction.FOREGROUND));
        for (JButton b : List.of(holdButton, interruptButton, killButton, fgButton)) {
            UiFonts.apply(b, UiFonts.BODY, Font.PLAIN);
            b.setFocusable(false);
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            bar.add(b);
        }
        // one glyph each, so a square taken from the font height always shows it — a width measured
        // from the glyph is not, because it is drawn from a fallback font
        ActionButtons.glyphSized(holdButton, interruptButton, killButton, fgButton);
        holdButton.setToolTipText("Hold / resume selected task");
        // the tooltip is rewritten with the held flag (see updateButtons), so the name a screen
        // reader — or a test — holds on to has to be the stable one
        holdButton.getAccessibleContext().setAccessibleName("Hold / resume selected task");
        interruptButton.setToolTipText("Interrupt selected task");
        killButton.setToolTipText("Stop selected task");
        fgButton.setToolTipText("Toggle foreground task");
        panel.add(bar, BorderLayout.NORTH);
        panel.add(note, BorderLayout.CENTER);
        panel.add(actionNote, BorderLayout.SOUTH);
        return panel;
    }

    private void act(NaruSessionModel.TaskAction action) {
        NaruSessionModel.TaskRow r = selectedRow();
        if (r == null) {
            actionNote.setText("select a task first");
            return;
        }
        // the engine answers the action and the poll shows its effect; the model refreshes straight
        // after, so the row changes on the click rather than up to 500 ms later
        String failure = session.taskAction(r.id(), action);
        actionNote.setText(failure == null ? action.label() + " #" + r.id() : failure);
        showSelected();
    }

    private boolean foregroundSelected() {
        NaruSessionModel.TaskRow r = selectedRow();
        return r != null && r.foreground();
    }

    /** Whether the row under the button is the held one, which is what decides its action. */
    private boolean heldSelected() {
        NaruSessionModel.TaskRow r = selectedRow();
        return r != null && r.held();
    }

    /** Re-read the selected task's detail. Cheap and safe: a gone task renders "no such task". */
    private void showSelected() {
        NaruSessionModel.TaskRow r = selectedRow();
        if (r == null) {
            shownId = -1;
            detail.setText("select a task to see its frames");
            updateButtons();
            taskSelection.accept(-1);
            return;
        }
        shownId = r.id();
        long request = ++detailRequest;
        if (!detailLoading) {
            detailLoading = true;
            detail.setText("Updating task details…");
            new javax.swing.SwingWorker<NaruSessionModel.TaskDetail, Void>() {
                protected NaruSessionModel.TaskDetail doInBackground() { return session.taskDetail(r.id()); }
                protected void done() {
                    detailLoading = false;
                    try { if (request == detailRequest && shownId == r.id()) detail.setText(friendlyDetail(get())); }
                    catch (Exception e) { if (request == detailRequest) detail.setText("Task details are temporarily unavailable."); }
                    if (shownId >= 0 && request != detailRequest) showSelected();
                }
            }.execute();
        }
        taskSelection.accept(r.id());
        updateButtons();
    }

    private NaruSessionModel.TaskRow selectedRow() {
        if (interactiveOnly) return list.getSelectedValue();
        Object node = taskTree.getLastSelectedPathComponent();
        Object value = node instanceof javax.swing.tree.DefaultMutableTreeNode n ? n.getUserObject() : null;
        return value instanceof NaruSessionModel.TaskRow r ? r : null;
    }

    private String friendlyDetail(NaruSessionModel.TaskDetail d) {
        if (d == null) return "This task is no longer available.";
        StringBuilder b = new StringBuilder();
        b.append(d.name() == null || d.name().isBlank() ? "Task" : d.name()).append('\n');
        b.append(d.status() == null ? "Status unavailable" : switch (d.status()) {
            case RUNNING -> "Working";
            case READY -> "Starting";
            case BLOCKED_ON_INPUT -> "Waiting for your reply";
            case BLOCKED_ON_EVENT -> "Waiting for an event";
            case DONE -> "Completed";
            case FAILED -> "Failed";
            case KILLED -> "Stopped";
        }).append('\n');
        if (d.model() != null && !d.model().isBlank()) b.append("Model: ").append(d.model()).append('\n');
        if (d.ran() != null) b.append("Duration: ").append(d.ran()).append('\n');
        if (d.error() != null && !d.error().isBlank()) b.append("\nWhat happened\n").append(d.error()).append('\n');
        if ((d.error() == null || d.error().isBlank()) && d.status() != null && d.status().isCompleted()) b.append("\nThis task has finished.");
        return b.toString();
    }

    public void setTaskSelectionListener(java.util.function.LongConsumer listener) {
        taskSelection = listener == null ? id -> {} : listener;
        NaruSessionModel.TaskRow selected = selectedRow();
        taskSelection.accept(selected == null ? -1 : selected.id());
    }

    /** Unbind from the session model. A closed window must not stay on the poll's listener list. */
    public void dispose() {
        session.removeListener(this);
    }

    public void refreshThemePalette() {
        allTasks.setIcon(javax.swing.UIManager.getIcon("Tree.openIcon"));
        allTasks.updateUI();
        allTasks.setIcon(javax.swing.UIManager.getIcon("Tree.openIcon"));
        repaint();
    }

    private void updateButtons() {
        NaruSessionModel.TaskRow r = selectedRow();
        boolean live = r != null && !r.status().isCompleted();
        // one button, both directions: a held task is the resumable one and a running task is the
        // holdable one, so the enabled state has to follow the label. Gating it on "!held" left a
        // button that read "Resume" and could not be pressed — the monitor's only way back from a
        // hold, found by the §6 gate run rather than by the unit test that clicked through the model.
        holdButton.setEnabled(live);
        holdButton.setText(r != null && r.held() ? "▶" : "Ⅱ");
        holdButton.setToolTipText(r != null && r.held() ? "Resume selected task" : "Hold selected task");
        killButton.setEnabled(live);
        interruptButton.setEnabled(live);
        fgButton.setEnabled(r != null);
        // hold flips its glyph between two shapes of different width, so the square is re-derived
        ActionButtons.fit(holdButton);
    }

    /**
     * The hold button as the user actually sees it — its word and whether it can be pressed. Asked
     * for by the gate run, because "the label flipped" and "the flip is clickable" are two different
     * claims and only the second one is useful.
     */
    public String holdButtonState() {
        return holdButton.getText() + "/" + (holdButton.isEnabled() ? "enabled" : "disabled");
    }

    /**
     * Select one task by id, the way a jump from another pane would.
     *
     * @return false when no row carries that id — a task that finished and deregistered since the
     * click must not leave the monitor showing a detail nobody asked for
     */
    public boolean selectTask(long id) {
        if (interactiveOnly && session.tasks().stream().anyMatch(r -> r.id() == id)) {
            interactiveOnly = false;
            allTasks.setSelected(true);
            ((java.awt.CardLayout)taskView.getLayout()).show(taskView,"tree");
            sessionModelChanged();
        }
        if (!interactiveOnly) { selectTreeTask(id); return selectedById(id)!=null; }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id() == id) {
                list.setSelectedIndex(i);
                showSelected();
                return true;
            }
        }
        return false;
    }

    /** How many rows the monitor is showing. */
    public int taskCount() {
        return rows.size();
    }

    /**
     * The label the renderer produces for one row, asked straight off the renderer: the indent a
     * child gets is the claim, and it is only real once a cell has actually been built.
     */
    public String rowLabelAt(int i) {
        NaruSessionModel.TaskRow r = rows.get(i);
        java.awt.Component c = list.getCellRenderer()
                .getListCellRendererComponent(list, r, i, false, false);
        return c instanceof JLabel l ? l.getText() : "";
    }

    /** The detail block on screen, for a test or a gate harness that has to measure the claim. */
    public String detailText() {
        return detail.getText();
    }

    /**
     * The size the detail area asks for, as {@code widthxheight}. Asked for by the gate run because
     * a {@code JTextArea} inside a scroll pane is laid out at its preferred size on every repaint,
     * and that pass — not the engine read — is what an oversized detail block costs the EDT.
     */
    public String detailPreferredSize() {
        java.awt.Dimension p = detail.getPreferredSize();
        return p.width + "x" + p.height;
    }

    /** The counter line and the last action's result, kept apart so neither overwrites the other. */
    public String monitorNote() {
        return note.getText();
    }

    public String actionNote() {
        return actionNote.getText();
    }

    private static Color color(NaruSessionModel.TaskRow t, JList<?> l) {
        Color base = switch (t.status()) {
            case DONE -> new Color(60, 140, 90);
            case FAILED -> new Color(190, 80, 70);
            case KILLED -> new Color(150, 110, 60);
            case BLOCKED_ON_INPUT, BLOCKED_ON_EVENT -> new Color(200, 150, 50);
            case RUNNING -> new Color(80, 130, 200);
            case READY -> Color.GRAY;
        };
        return dark(l) ? lighten(base) : base;
    }

    private static boolean dark(JList<?> l) {
        Color c = l.getBackground();
        return c.getRed() + c.getGreen() + c.getBlue() < 384;
    }

    private static Color lighten(Color c) {
        return new Color(Math.min(255, c.getRed() + 90), Math.min(255, c.getGreen() + 90),
                Math.min(255, c.getBlue() + 90));
    }

    @Override
    public void sessionModelChanged() {
        String query = search.getText().trim().toLowerCase(java.util.Locale.ROOT);
        List<NaruSessionModel.TaskRow> current = current().stream()
                .filter(r -> query.isEmpty() || (r.name() + " " + r.id() + " " + r.status()).toLowerCase(java.util.Locale.ROOT).contains(query))
                .toList();
        empty.setText(!query.isEmpty() ? "no matching tasks" : interactiveOnly ? "no interactive tasks" : "no tasks in this session");
        // the poll already de-duplicated and arranged; a wholesale reload keeps row identity simple
        boolean same = current.size() == rows.size();
        if (same) {
            for (int i = 0; i < current.size(); i++) {
                if (!current.get(i).equals(rows.get(i))) {
                    same = false;
                    break;
                }
            }
        }
        if (same) {
            // ages move, so an unchanged set of rows still deserves a repaint every tick
            list.repaint(); taskTree.repaint();
            if (shownId >= 0) {
                NaruSessionModel.TaskRow selected = selectedById(shownId);
                if (selected == null) {
                    detail.setText(TaskText.detail(null));
                    shownId = -1;
                } else if (!detailLoading) {
                    // Re-read live detail off the EDT; selected task output can change even when
                    // the list row itself stays the same.
                    showSelected();
                }
            }
            updateNote(current);
            updateButtons();
            return;
        }
        long wasSelected = shownId;
        rows.clear();
        for (NaruSessionModel.TaskRow r : current) {
            rows.addElement(r);
        }
        rebuildTaskTree(current, wasSelected);
        if (interactiveOnly) {
            int restore = indexOf(wasSelected);
            if (restore < 0 && !current.isEmpty()) restore = 0;
            if (restore >= 0) list.setSelectedIndex(restore); else { shownId = -1; showSelected(); }
        } else if (wasSelected < 0 && !current.isEmpty()) {
            selectTreeTask(current.get(0).id());
        } else if (indexOf(wasSelected) >= 0) {
            selectTreeTask(wasSelected);
        } else { shownId = -1; showSelected(); }
        updateNote(current);
        updateButtons();
    }

    private NaruSessionModel.TaskRow selectedById(long id) {
        for (NaruSessionModel.TaskRow r : current()) {
            if (r.id() == id) {
                return r;
            }
        }
        return null;
    }

    private int indexOf(long id) { for(int i=0;i<rows.size();i++) if(rows.get(i).id()==id) return i; return -1; }

    private void rebuildTaskTree(List<NaruSessionModel.TaskRow> items, long selectedId) {
        var root = new javax.swing.tree.DefaultMutableTreeNode("Tasks");
        java.util.Map<Long,javax.swing.tree.DefaultMutableTreeNode> nodes = new java.util.LinkedHashMap<>();
        for (NaruSessionModel.TaskRow r : items) nodes.put(r.id(), new javax.swing.tree.DefaultMutableTreeNode(r));
        for (NaruSessionModel.TaskRow r : items) {
            var node = nodes.get(r.id()); var parent = nodes.get(r.parentId());
            if (parent != null && parent != node) parent.add(node); else root.add(node);
        }
        taskTree.setModel(new javax.swing.tree.DefaultTreeModel(root));
        for(int i=0;i<taskTree.getRowCount();i++) taskTree.expandRow(i);
        if(selectedId>=0) selectTreeTask(selectedId);
    }

    private void selectTreeTask(long id) {
        var root=(javax.swing.tree.DefaultMutableTreeNode)taskTree.getModel().getRoot();
        java.util.Enumeration<?> e=root.depthFirstEnumeration();
        while(e.hasMoreElements()) { Object n=e.nextElement(); if(n instanceof javax.swing.tree.DefaultMutableTreeNode node && node.getUserObject() instanceof NaruSessionModel.TaskRow r && r.id()==id) { taskTree.setSelectionPath(new javax.swing.tree.TreePath(node.getPath())); return; } }
    }

    private List<NaruSessionModel.TaskRow> current() {
        List<NaruSessionModel.TaskRow> all = session.tasks();
        return interactiveOnly
                ? all.stream().filter(r -> r.conversation() || waitingOnUser(r)).toList()
                : all;
    }

    private static String friendlyStatus(NaruTaskStatus s) { return s==null?"Unknown":switch(s){case RUNNING->"Working";case READY->"Starting";case BLOCKED_ON_INPUT->"Needs reply";case BLOCKED_ON_EVENT->"Waiting";case DONE->"Done";case FAILED->"Failed";case KILLED->"Stopped";}; }
    private static Color statusColor(NaruTaskStatus s) { return switch(s){case DONE->new Color(45,145,100);case FAILED->new Color(205,75,70);case KILLED->new Color(175,115,55);case BLOCKED_ON_INPUT,BLOCKED_ON_EVENT->new Color(185,135,35);case RUNNING->new Color(65,125,195);case READY->new Color(115,120,130);}; }

    /**
     * A parked question is shown even when its task is not a conversation. {@code requestInput} does
     * not require INTERACTIVE mode and pending-input registration keys off the status, so a BATCH job
     * can be sitting on a question — and the composer's own rule is that only the *selected* pending
     * task can be answered, so a hidden row would be an unanswerable question.
     * {@code BLOCKED_ON_EVENT} deliberately is not included: that waits on the engine, not on the user.
     */
    private static boolean waitingOnUser(NaruSessionModel.TaskRow r) {
        return r.status() == NaruTaskStatus.BLOCKED_ON_INPUT;
    }

    private void updateNote(List<NaruSessionModel.TaskRow> current) {
        int running = 0;
        int blocked = 0;
        for (NaruSessionModel.TaskRow r : current) {
            NaruTaskStatus s = r.status();
            if (s == NaruTaskStatus.RUNNING || s == NaruTaskStatus.READY) {
                running++;
            } else if (s == NaruTaskStatus.BLOCKED_ON_INPUT || s == NaruTaskStatus.BLOCKED_ON_EVENT) {
                blocked++;
            }
        }
        empty.setVisible(current.isEmpty());
        // the counter is deliberately not the place a refused action is reported: that is
        // actionNote's job, so a "kill failed" line cannot be overwritten by the next poll tick
        String summary = current.size() + " task" + (current.size() == 1 ? "" : "s")
                + " · " + running + " live"
                + (blocked == 0 ? "" : " · " + blocked + " waiting for input");
        if (!summary.equals(note.getText())) {
            note.setText(summary);
        }
    }
}
