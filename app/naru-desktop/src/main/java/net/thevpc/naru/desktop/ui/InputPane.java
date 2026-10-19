package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.registry.NaruLineCompletion;
import net.thevpc.naru.desktop.model.InputPrompt;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.util.MsgText;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;

/**
 * Task conversation composer with model/mode selectors, attachments, completion, and input history.
 * <p>
 * The field is disabled with the reason written next to it whenever a submission would be thrown
 * away. That is plan §3.4: the engine drops input silently unless the foreground task is
 * {@code BLOCKED_ON_INPUT}, so a field that looks enabled and does nothing is a bug the REPL has
 * already, and not something to reproduce.
 * <p>
 * When the agent asks a question, its prompt is shown above the field with a button per numbered
 * choice, so answering is a click for the common case and a typed line for everything else.
 */
public final class InputPane extends JPanel implements NaruSessionModel.Listener {

    private final NaruSessionModel session;
    /**
     * The composer itself: multiline, word-wrapped, undoable, and its Ctrl+Space list is built from
     * {@code session.complete(text, caret)} — the engine's own {@link NaruLineCompletion}, so the
     * candidate set and the span to replace are never re-derived here.
     */
    private final ComposerArea area;
    private final ComposerSyntax syntax;
    private final JLabel reason = new JLabel(" ");
    private final JButton send = new JButton("Send");
    private final JComboBox<String> model = new JComboBox<>(new String[]{"Choose model…", "Default model", "Local model"});
    private final JComboBox<String> agentMode = new JComboBox<>(new String[]{"Agent", "Ask", "Plan"});
    private final JButton attach = new JButton("＋ Attach");
    private final JPanel promptPanel = new JPanel(new BorderLayout());
    private final JLabel promptQuestion = new JLabel(" ");
    private final JPanel promptChoices = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private NaruInputRequest shownFor;
    private long selectedTaskId = -1;

    public InputPane(NaruSessionModel session) {
        super(new BorderLayout(6, 4));
        this.session = session;
        this.area = new ComposerArea(session::complete);
        // the engine decides which word is a directive, an option, a path or a comment; this host only
        // decides the hue of each, so the terminal and the composer cannot drift apart
        this.syntax = ComposerSyntax.install(area, session::session);
        setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));

        JScrollPane scroller = new JScrollPane(area);
        scroller.setBorder(BorderFactory.createLineBorder(getBackground().darker()));

        promptPanel.setVisible(false);
        promptQuestion.setFont(promptQuestion.getFont().deriveFont(Font.BOLD));
        promptPanel.add(promptQuestion, BorderLayout.NORTH);
        promptPanel.add(promptChoices, BorderLayout.SOUTH);

        JPanel tools = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        model.setToolTipText("Model selection · provider data will be connected here");
        agentMode.setToolTipText("Conversation mode · mode actions will be connected here");
        attach.setToolTipText("Attach files to this task conversation");
        attach.addActionListener(e -> {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
            chooser.setMultiSelectionEnabled(true);
            if (chooser.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) {
                attach.setText("＋ " + chooser.getSelectedFiles().length + " attached");
            }
        });
        left.add(model);
        left.add(agentMode);
        left.add(attach);
        tools.add(left, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(reason);
        send.addActionListener(this::doSend);
        right.add(send);
        tools.add(right, BorderLayout.EAST);

        add(promptPanel, BorderLayout.NORTH);
        add(scroller, BorderLayout.CENTER);
        add(tools, BorderLayout.SOUTH);

        installKeys();
        session.setInputListener(this::showPrompt);
        session.addListener(this);
        sessionModelChanged();
    }

    /**
     * The widget owns its keys — see {@link ComposerArea} — so what is left here is the two things
     * only the pane knows: what "send" means, and where history lives.
     */
    private void installKeys() {
        area.setOnSend(() -> doSend(null));
        area.setHistory(session::historyPrev, session::historyNext);
    }

    // ── prompt ────────────────────────────────────────────────────────────────

    private void showPrompt(NaruInputRequest request) {
        SwingUtilities.invokeLater(() -> {
            shownFor = request;
            promptChoices.removeAll();
            InputPrompt p = InputPrompt.parse(
                    MsgText.plain(request.prompt() == null ? "" : request.prompt().toString()));
            // the readline parks on every prompt cycle and its "question" is the prompt string
            // ("> " or the model's own question). Only the engine's ask_user tool produces
            // numbered choices, so the panel is a question UI and not a decoration: showing it
            // for the prompt would tell the user the agent is asking when it is only waiting.
            if (p.choices().isEmpty()) {
                shownFor = request;
                clearPrompt();
                return;
            }
            promptQuestion.setText("<html><b>Agent asks:</b> " + escape(p.question()) + "</html>");
            int n = 0;
            for (String choice : p.choices()) {
                n++;
                final int number = n;
                JButton b = new JButton(number + ". " + abbrev(choice));
                b.addActionListener(e -> answer(p.choices().get(number - 1), number));
                promptChoices.add(b);
            }
            if (p.trailingHint() != null) {
                promptChoices.add(new JLabel(
                        "<html><font size=2 color=gray>" + escape(p.trailingHint()) + "</font></html>"));
            }
            JButton skip = new JButton("Cancel");
            skip.setToolTipText("Abandons the question; the task fails rather than parking forever");
            skip.addActionListener(e -> {
                session.cancelPendingInput("cancelled from the desktop");
                clearPrompt();
            });
            promptChoices.add(skip);
            promptPanel.setVisible(true);
            promptPanel.revalidate();
            area.requestFocusInWindow();
            updateEnabledState();
        });
    }

    /** A choice click sends the number, exactly as the tool tells the user to. */
    private void answer(String choice, int number) {
        if (shownFor == null) {
            return;
        }
        NaruSessionModel m = session;
        NaruInputRequest r = shownFor;
        clearPrompt();
        m.echoAndDeliver(String.valueOf(number), r);
    }

    private void clearPrompt() {
        shownFor = null;
        promptPanel.setVisible(false);
        promptChoices.removeAll();
        updateEnabledState();
    }

    private static String abbrev(String s) {
        String x = s.replace('\n', ' ').trim();
        return x.length() > 48 ? x.substring(0, 48) + "…" : x;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ── sending ───────────────────────────────────────────────────────────────

    private void doSend(ActionEvent e) {
        String line = area.composerText();
        NaruInputRequest r = shownFor;
        if (line.isEmpty()) {
            return;
        }
        if (r != null) {
            clearPrompt();
            session.echoAndDeliver(line, r);
        } else {
            session.submit(line);
        }
        area.clearForSend();
        updateEnabledState();
    }

    private void updateEnabledState() {
        String why = session.inputDisabledReason();
        NaruInputRequest pending = session.pendingRequest();
        boolean matchesTask = pending != null && selectedTaskId == pending.task().id();
        boolean can = session.canAcceptInput() && matchesTask;
        area.setEnabled(can);
        send.setEnabled(can);
        if (can) {
            reason.setText(" ");
        } else {
            if (pending != null && !matchesTask) why = "Select task #" + pending.task().id() + " to answer its question";
            reason.setText("<html><i>" + escape(why) + "</i></html>");
            reason.setForeground(new Color(180, 95, 60));
        }
    }

    @Override
    public void sessionModelChanged() {
        updateEnabledState();
    }

    /** Prompt the user to look at the transcript when output stops mid-turn. */
    public void focusInput() {
        area.requestFocusInWindow();
    }

    public void setSelectedTaskId(long taskId) {
        selectedTaskId = taskId;
        NaruInputRequest pending = session.pendingRequest();
        if (pending != null && pending.task().id() == taskId) showPrompt(pending);
        else if (shownFor != null) clearPrompt();
        updateEnabledState();
    }

    public void dispose() {
        session.removeListener(this);
        session.setInputListener(null);
    }

    /**
     * Which grammar is painting the composer: {@code "naru"} for a command line, otherwise the
     * {@code SyntaxConstants} id of the draft's detected language.
     */
    public String composerMode() {
        return syntax.mode();
    }

    /** Lines the engine could not be asked about, and the first reason. Meant for a gate run to read. */
    public String composerSyntaxFailure() {
        return syntax.describeFailure();
    }
}
