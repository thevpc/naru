package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruInputMode;
import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.registry.NaruLineCompletion;
import net.thevpc.naru.desktop.model.InputPrompt;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.util.MsgText;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * Where the user talks to the agent: a multi-line field, the LINE/BLOC switch that replaces
 * {@code /buffer}, completion from E2, and history on the arrow keys.
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
    private final JTextArea area = new JTextArea(3, 40);
    private final JLabel reason = new JLabel(" ");
    private final JButton send = new JButton("Send");
    private final JComboBox<String> mode = new JComboBox<>(new String[]{"LINE", "BLOC"});
    private final JPanel promptPanel = new JPanel(new BorderLayout());
    private final JLabel promptQuestion = new JLabel(" ");
    private final JPanel promptChoices = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JPopupMenu completion = new JPopupMenu();
    private NaruLineCompletion.Result lastResult = NaruLineCompletion.Result.none("", 0, 0);
    private NaruInputRequest shownFor;

    public InputPane(NaruSessionModel session) {
        super(new BorderLayout(6, 4));
        this.session = session;
        setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));

        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JScrollPane scroller = new JScrollPane(area);
        scroller.setBorder(BorderFactory.createLineBorder(getBackground().darker()));

        promptPanel.setVisible(false);
        promptQuestion.setFont(promptQuestion.getFont().deriveFont(Font.BOLD));
        promptPanel.add(promptQuestion, BorderLayout.NORTH);
        promptPanel.add(promptChoices, BorderLayout.SOUTH);

        JPanel tools = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        mode.setToolTipText("LINE sends Enter as submit; BLOC keeps Enter for newlines and sends on Ctrl+Enter");
        mode.addActionListener(e -> session.setInputMode(
                "BLOC".equals(mode.getSelectedItem()) ? NaruInputMode.BLOC : NaruInputMode.LINE));
        left.add(mode);
        left.add(reason);
        tools.add(left, BorderLayout.WEST);
        send.addActionListener(this::doSend);
        tools.add(send, BorderLayout.EAST);

        add(promptPanel, BorderLayout.NORTH);
        add(scroller, BorderLayout.CENTER);
        add(tools, BorderLayout.SOUTH);

        installKeys();
        session.setInputListener(this::showPrompt);
        session.addListener(this);
        sessionModelChanged();
    }

    private void installKeys() {
        area.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE && completion.isVisible()) {
                    completion.setVisible(false);
                    e.consume();
                    return;
                }
                if (e.getKeyCode() == KeyEvent.VK_TAB || (e.getKeyCode() == KeyEvent.VK_SPACE
                        && e.isControlDown())) {
                    e.consume();
                    showCompletion();
                    return;
                }
                boolean enter = e.getKeyCode() == KeyEvent.VK_ENTER;
                if (!enter) {
                    return;
                }
                boolean ctrl = (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0;
                boolean shift = (e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0;
                boolean bloc = session.inputMode() == NaruInputMode.BLOC;
                if (completion.isVisible()) {
                    e.consume();
                    acceptFirstCandidate();
                    return;
                }
                // LINE: Enter submits, Shift+Enter adds a newline. BLOC: the reverse.
                if (bloc ? ctrl : !shift) {
                    e.consume();
                    doSend(null);
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_CONTROL || e.getKeyCode() == KeyEvent.VK_SHIFT) {
                    return;
                }
                updateEnabledState();
            }
        });
        // history on Up/Down, but only when the caret is on the first/last visual line, so
        // editing a multi-line block still moves the caret normally
        area.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "desktop-history-prev");
        area.getActionMap().put("desktop-history-prev", new HistoryAction(true));
        area.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "desktop-history-next");
        area.getActionMap().put("desktop-history-next", new HistoryAction(false));
    }

    private final class HistoryAction extends AbstractTextAction {
        private final boolean prev;

        HistoryAction(boolean prev) {
            super(prev ? "prev" : "next");
            this.prev = prev;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            JTextArea t = (JTextArea) e.getSource();
            if (prev && isOnFirstLine(t)) {
                String s = session.historyPrev();
                if (s != null) {
                    replaceAll(t, s);
                }
            } else if (!prev && isOnLastLine(t)) {
                String s = session.historyNext();
                if (s != null) {
                    replaceAll(t, s);
                }
            }
        }

        private boolean isOnFirstLine(JTextArea t) {
            try {
                return t.getLineOfOffset(t.getCaretPosition()) == 0;
            } catch (BadLocationException e) {
                return false;
            }
        }

        private boolean isOnLastLine(JTextArea t) {
            try {
                return t.getLineOfOffset(t.getCaretPosition()) == t.getLineCount() - 1;
            } catch (BadLocationException e) {
                return false;
            }
        }

        private void replaceAll(JTextArea t, String s) {
            t.setText(s);
            t.setCaretPosition(s.length());
        }
    }

    /** Base so both history actions share undo-ish plumbing without a second key handler. */
    private abstract static class AbstractTextAction extends javax.swing.AbstractAction {
        AbstractTextAction(String name) {
            super(name);
        }
    }

    // ── completion ────────────────────────────────────────────────────────────

    private void showCompletion() {
        String text = area.getText();
        int caret = area.getCaretPosition();
        NaruLineCompletion.Result r = session.complete(text, caret);
        lastResult = r;
        completion.removeAll();
        if (r.isEmpty()) {
            completion.setVisible(false);
            return;
        }
        for (NaruLineCompletion.Candidate c : r.candidates()) {
            JMenuItem item = new JMenuItem(render(c, r.ambiguous()));
            item.addActionListener(e -> insert(c));
            completion.add(item);
        }
        int rows = Math.min(r.candidates().size(), 12);
        java.awt.Rectangle caretRect = caretRect();
        completion.setPreferredSize(new Dimension(Math.max(240, caretRect.width + 120),
                rows * 22 + 8));
        completion.show(area, caretRect.x, caretRect.y + caretRect.height);
    }

    /** Where to hang the popup; a wrapped area has no model position until it is laid out. */
    private java.awt.Rectangle caretRect() {
        try {
            java.awt.Rectangle r = area.modelToView(area.getCaretPosition());
            if (r != null) {
                return r;
            }
        } catch (BadLocationException | IllegalArgumentException ignored) {
            // not laid out yet, or a stale caret: fall back to the top-left of the field
        }
        return new java.awt.Rectangle(0, 0, 240, 18);
    }

    private String render(NaruLineCompletion.Candidate c, boolean ambiguous) {
        String base = c.insert();
        if (c.display() == null) {
            return ambiguous ? base + "  (" + c.kind() + ")" : base;
        }
        return base + " — " + c.display() + (ambiguous ? "  (" + c.kind() + ")" : "");
    }

    private void acceptFirstCandidate() {
        if (!lastResult.candidates().isEmpty()) {
            insert(lastResult.candidates().get(0));
        }
        completion.setVisible(false);
    }

    private void insert(NaruLineCompletion.Candidate c) {
        int start = lastResult.replaceStart();
        int end = lastResult.replaceEnd();
        String text = area.getText();
        if (start < 0 || end > text.length() || start > end) {
            return;
        }
        String next = text.substring(0, start) + c.insert() + text.substring(end);
        area.setText(next);
        area.setCaretPosition(start + c.insert().length());
        completion.setVisible(false);
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
        String line = area.getText().trim();
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
        area.setText("");
        updateEnabledState();
    }

    private void updateEnabledState() {
        String why = session.inputDisabledReason();
        boolean can = session.canAcceptInput();
        area.setEnabled(can);
        send.setEnabled(can);
        if (can) {
            reason.setText(" ");
        } else {
            reason.setText("<html><i>" + escape(why) + "</i></html>");
            reason.setForeground(new Color(180, 95, 60));
        }
    }

    @Override
    public void sessionModelChanged() {
        mode.setSelectedItem(session.inputMode() == NaruInputMode.BLOC ? "BLOC" : "LINE");
        updateEnabledState();
    }

    /** Prompt the user to look at the transcript when output stops mid-turn. */
    public void focusInput() {
        area.requestFocusInWindow();
    }
}
