package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.util.TaskText;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
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
    private final JTextArea detail = new JTextArea();
    private final JLabel note = new JLabel(" ");
    private final JLabel actionNote = new JLabel(" ");
    private final JButton holdButton = new JButton("Hold");
    private final JButton interruptButton = new JButton("Interrupt");
    private final JButton killButton = new JButton("Kill");
    private final JButton fgButton = new JButton("Foreground");
    private final JLabel empty = new JLabel("no tasks running");

    private long shownId = -1;

    public TaskPane(NaruSessionModel session) {
        super(new BorderLayout());
        this.session = session;
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((l, v, ix, sel, f) -> {
            JLabel label = new JLabel(TaskText.rowLabel(v));
            label.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
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

        detail.setEditable(false);
        detail.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        detail.setText("select a task to see its frames");
        JScrollPane listScroll = new JScrollPane(list);
        // the same lesson as the transcript: a JList is as wide as its widest cell, and a long task
        // name or an error string would otherwise push the detail pane and the buttons off screen
        listScroll.setMinimumSize(new java.awt.Dimension(240, 120));
        JScrollPane detailScroll = new JScrollPane(detail);
        detailScroll.setMinimumSize(new java.awt.Dimension(240, 80));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, listScroll, detailScroll);
        split.setResizeWeight(0.55);
        split.setDividerLocation(220);

        add(empty, BorderLayout.NORTH);
        empty.setHorizontalAlignment(SwingConstants.CENTER);
        empty.setForeground(Color.GRAY);
        add(split, BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);

        note.setFont(note.getFont().deriveFont(Font.PLAIN, 11f));
        note.setForeground(Color.GRAY);
        actionNote.setFont(actionNote.getFont().deriveFont(Font.PLAIN, 11f));
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
            b.setFont(b.getFont().deriveFont(Font.PLAIN, 11f));
            bar.add(b);
        }
        panel.add(bar, BorderLayout.NORTH);
        panel.add(note, BorderLayout.CENTER);
        panel.add(actionNote, BorderLayout.SOUTH);
        return panel;
    }

    private void act(NaruSessionModel.TaskAction action) {
        NaruSessionModel.TaskRow r = list.getSelectedValue();
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
        NaruSessionModel.TaskRow r = list.getSelectedValue();
        return r != null && r.foreground();
    }

    /** Whether the row under the button is the held one, which is what decides its action. */
    private boolean heldSelected() {
        NaruSessionModel.TaskRow r = list.getSelectedValue();
        return r != null && r.held();
    }

    /** Re-read the selected task's detail. Cheap and safe: a gone task renders "no such task". */
    private void showSelected() {
        NaruSessionModel.TaskRow r = list.getSelectedValue();
        if (r == null) {
            shownId = -1;
            detail.setText("select a task to see its frames");
            updateButtons();
            return;
        }
        shownId = r.id();
        detail.setText(TaskText.detail(session.taskDetail(r.id())));
        updateButtons();
    }

    /** Unbind from the session model. A closed window must not stay on the poll's listener list. */
    public void dispose() {
        session.removeListener(this);
    }

    private void updateButtons() {
        NaruSessionModel.TaskRow r = list.getSelectedValue();
        boolean live = r != null && !r.status().isCompleted();
        // one button, both directions: a held task is the resumable one and a running task is the
        // holdable one, so the enabled state has to follow the label. Gating it on "!held" left a
        // button that read "Resume" and could not be pressed — the monitor's only way back from a
        // hold, found by the §6 gate run rather than by the unit test that clicked through the model.
        holdButton.setEnabled(live);
        holdButton.setText(r != null && r.held() ? "Resume" : "Hold");
        killButton.setEnabled(live);
        interruptButton.setEnabled(live);
        fgButton.setEnabled(r != null);
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
        List<NaruSessionModel.TaskRow> current = session.tasks();
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
            list.repaint();
            if (shownId >= 0) {
                NaruSessionModel.TaskRow selected = selectedById(shownId);
                if (selected == null) {
                    detail.setText(TaskText.detail(null));
                    shownId = -1;
                } else if (selected.status().isCompleted()
                        || session.isTaskModelLabelPending(shownId)) {
                    // a completed row is re-read for its last words, and a row whose model label is
                    // still being resolved off the EDT is re-read so the answer replaces the marker
                    showSelected();
                }
            }
            updateNote(current);
            updateButtons();
            return;
        }
        int wasSelected = list.getSelectedIndex();
        rows.clear();
        for (NaruSessionModel.TaskRow r : current) {
            rows.addElement(r);
        }
        if (wasSelected >= 0 && wasSelected < rows.size()) {
            list.setSelectedIndex(wasSelected);
        }
        if (list.getSelectedIndex() < 0 && !current.isEmpty()) {
            list.setSelectedIndex(0);
        }
        if (list.getSelectedIndex() >= 0) {
            showSelected();
        } else {
            shownId = -1;
        }
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

    private List<NaruSessionModel.TaskRow> current() {
        return session.tasks();
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
