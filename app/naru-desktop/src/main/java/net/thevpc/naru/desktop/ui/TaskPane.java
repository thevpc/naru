package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.desktop.model.NaruSessionModel;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.util.List;

/**
 * The live task list. Phase 1 shows state only — read from the session model's snapshot, never
 * from {@code session.tasks()} on the EDT of a widget — because interrupt/kill/hold is Phase 2
 * work and this plan does not let a later phase leak into an earlier gate.
 */
public final class TaskPane extends JPanel implements NaruSessionModel.Listener {

    private final NaruSessionModel session;
    private final DefaultListModel<NaruSessionModel.TaskRow> rows = new DefaultListModel<>();
    private final JList<NaruSessionModel.TaskRow> list = new JList<>(rows);
    private final JLabel note = new JLabel(" ");

    public TaskPane(NaruSessionModel session) {
        super(new BorderLayout());
        this.session = session;
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((l, v, ix, sel, f) -> {
            JLabel label = new JLabel(text(v));
            label.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            label.setForeground(color(v, l));
            label.setOpaque(true);
            label.setBackground(sel ? l.getSelectionBackground() : l.getBackground());
            return label;
        });
        note.setFont(note.getFont().deriveFont(Font.PLAIN, 11f));
        note.setForeground(Color.GRAY);
        add(new JScrollPane(list), BorderLayout.CENTER);
        add(note, BorderLayout.SOUTH);
        session.addListener(this);
        sessionModelChanged();
    }

    private static String text(NaruSessionModel.TaskRow t) {
        String base = "#" + t.id() + "  " + t.status()
                + (t.foreground() ? "  [fg]" : "")
                + (t.name() == null || t.name().isBlank() ? "" : "  " + t.name());
        return t.error() == null ? base : base + "  — " + t.error();
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
        // the poll already de-duplicated; a wholesale reload keeps row identity simple
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
        note.setText(current.size() + " task" + (current.size() == 1 ? "" : "s")
                + " · interrupt/kill arrives with Phase 2");
    }
}
