package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.desktop.model.NaruSessionModel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Font;

/**
 * The status bar: what the REPL has no surface for at all. Model, project, task state, token
 * counts and last save, all read straight off the session model so a widget never asks the
 * engine for anything.
 */
public final class StatusBar extends JPanel implements NaruSessionModel.Listener {

    private final NaruSessionModel session;
    private final JLabel status = new JLabel("not started");
    private final JLabel model = new JLabel();
    private final JLabel project = new JLabel();
    private final JLabel tasks = new JLabel();
    private final JLabel tokens = new JLabel();
    private final JLabel save = new JLabel();

    public StatusBar(NaruSessionModel session) {
        super();
        this.session = session;
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY.darker()),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        Font small = status.getFont().deriveFont(Font.PLAIN, 11f);
        for (JLabel l : new JLabel[]{status, model, project, tasks, tokens, save}) {
            l.setFont(small);
            l.setForeground(Color.GRAY);
        }
        add(status);
        add(sep());
        add(model);
        add(sep());
        add(project);
        add(Box.createHorizontalGlue());
        add(tasks);
        add(sep());
        add(tokens);
        add(sep());
        add(save);
        session.addListener(this);
        sessionModelChanged();
    }

    private static JLabel sep() {
        JLabel l = new JLabel("  ·  ");
        l.setForeground(Color.GRAY);
        return l;
    }

    /** Report a state the session model never reaches, such as a project that failed to open. */
    public void setStatusText(String text) {
        status.setText(text);
    }

    @Override
    public void sessionModelChanged() {
        status.setText(session.statusText());
        model.setText("model " + session.model());
        project.setText(session.projectPath());
        int blocked = 0;
        int active = 0;
        for (NaruSessionModel.TaskRow t : session.tasks()) {
            if (t.status() == NaruTaskStatus.BLOCKED_ON_INPUT) {
                blocked++;
            }
            if (!t.status().isCompleted()) {
                active++;
            }
        }
        tasks.setText(active + " task" + (active == 1 ? "" : "s")
                + (blocked > 0 ? " (" + blocked + " waiting)" : ""));
        tokens.setText(session.tokensLabel());
        save.setText("saved " + session.saveState());
    }
}
