package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.registry.NaruLineCompletion;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.desktop.model.InputPrompt;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.util.MsgText;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.DefaultListModel;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JCheckBox;
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
    private final JButton send = new JButton("➤");
    private final JButton queued = new JButton("⏳ 0");
    private final JButton model = new JButton("⚡");
    private final JComboBox<String> agentMode = new JComboBox<>();
    /** True while {@link #refreshModes()} is writing the combo, so its listener can tell that from a click. */
    private boolean modeSyncRunning;
    private final JCheckBox thinking = new JCheckBox("Thinking", true);
    private final JButton attach = new JButton("📎");
    private final JPanel promptPanel = new JPanel(new BorderLayout());
    private final JLabel promptQuestion = new JLabel(" ");
    private final JPanel promptChoices = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private NaruInputRequest shownFor;
    private long selectedTaskId = -1;
    private java.util.List<net.thevpc.naru.api.model.NaruModelInfo> modelInfos = java.util.List.of();
    private net.thevpc.naru.api.agent.NaruSession modelCatalogSession;
    private boolean modelCatalogLoading;
    private enum ComposerAction { RUN, SEND, ENQUEUE, STOP, DISABLED }
    private ComposerAction composerAction = ComposerAction.DISABLED;
    /** Fallback border for the composer card when the LAF defines no component border colour. */
    private static final Color COMPOSER_IDLE_BORDER = new Color(200, 205, 215);
    /** Accent drawn around the composer card while the text area owns the keyboard focus. */
    private Color composerFocusBorder;
    private JPanel composerCard;
    private javax.swing.Timer focusBorderTimer;

    public InputPane(NaruSessionModel session) {
        super(new BorderLayout(6, 4));
        this.session = session;
        this.area = new ComposerArea(session::complete);
        this.syntax = ComposerSyntax.install(area, session::session);
        setBorder(BorderFactory.createEmptyBorder(6, 10, 8, 10));

        UiFonts.apply(send, UiFonts.BODY, Font.BOLD);
        send.putClientProperty("JButton.buttonType", "roundRect");
        send.setMargin(new java.awt.Insets(4, 10, 4, 10));
        send.getAccessibleContext().setAccessibleName("Send");

        queued.setMargin(new java.awt.Insets(2, 6, 2, 6));
        UiFonts.apply(queued, UiFonts.CAPTION, Font.PLAIN);
        queued.putClientProperty("JButton.buttonType", "toolBarButton");
        queued.setVisible(false);

        model.putClientProperty("JButton.buttonType", "roundRect");
        model.setMargin(new java.awt.Insets(2, 8, 2, 8));
        attach.putClientProperty("JButton.buttonType", "toolBarButton");
        attach.setMargin(new java.awt.Insets(2, 6, 2, 6));
        attach.setToolTipText("Attach a file to the message");
        attach.getAccessibleContext().setAccessibleName("Attach");

        JPanel composerCard = new JPanel(new BorderLayout(0, 4)) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = javax.swing.UIManager.getColor("TextArea.background");
                if (bg == null) bg = getBackground();
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                // the border says where the keyboard is: focus draws the same accent a focused
                // text field uses, so the composer reads as selected like every other component
                Color borderColor = composerFocusBorder != null
                        ? composerFocusBorder
                        : javax.swing.UIManager.getColor("Component.borderColor");
                if (borderColor == null) borderColor = COMPOSER_IDLE_BORDER;
                g2.setColor(borderColor);
                g2.setStroke(new java.awt.BasicStroke(composerFocusBorder != null ? 2f : 1f));
                g2.drawRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 16, 16);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        composerCard.setOpaque(false);
        composerCard.setBorder(BorderFactory.createEmptyBorder(6, 10, 8, 10));
        this.composerCard = composerCard;
        // focus returns to the composer after Send and after a Stop click, but Swing delivers
        // that asynchronously; a short one-shot timer re-reads the real focus so the border cannot
        // stay lit on a composer that no longer has it (or stay dark on one that does).
        focusBorderTimer = new javax.swing.Timer(120, e -> refreshComposerFocus());
        focusBorderTimer.setRepeats(false);

        JScrollPane scroller = new JScrollPane(area);
        scroller.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        scroller.setOpaque(false);
        scroller.getViewport().setOpaque(false);

        promptPanel.setVisible(false);
        promptPanel.setOpaque(false);
        promptQuestion.setFont(promptQuestion.getFont().deriveFont(Font.BOLD));
        promptChoices.setOpaque(false);
        promptPanel.add(promptQuestion, BorderLayout.NORTH);
        promptPanel.add(promptChoices, BorderLayout.SOUTH);

        JPanel tools = new JPanel(new BorderLayout());
        tools.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        model.setToolTipText("Choose the model for the selected task");
        agentMode.setToolTipText("Prompt mode: the engine's own list, for the task in the header");
        agentMode.addActionListener(e -> {
            if (modeSyncRunning) return;
            NaruTask task = selectedTask();
            String name = String.valueOf(agentMode.getSelectedItem());
            if (task == null || name.isBlank() || task.session() == null) return;
            var mode = task.session().registry().mode(name).orNull();
            if (mode == null) return;
            try {
                task.promptMode(mode);
            } catch (RuntimeException ex) {
                DesktopDialog.showMessage(SwingUtilities.getWindowAncestor(this), "Mode",
                        String.valueOf(ex.getMessage()));
                refreshModes();
            }
        });
        attach.setToolTipText("Attach files to this task conversation");
        thinking.setToolTipText("Enable or disable thinking for the selected task");
        thinking.setOpaque(false);
        thinking.addActionListener(e -> {
            NaruTask task = selectedTask();
            if (task != null) task.setTaskEnv(net.thevpc.naru.api.model.NaruThinkingConfig.THINKING_KEY,
                    thinking.isSelected());
        });
        attach.addActionListener(e -> {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
            chooser.setMultiSelectionEnabled(true);
            if (chooser.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) {
                attach.setText("📎 " + chooser.getSelectedFiles().length + " attached");
            }
        });
        left.add(model);
        left.add(agentMode);
        left.add(thinking);
        left.add(attach);
        left.add(queued);
        tools.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(reason);
        queued.addActionListener(e -> editQueue());
        send.addActionListener(this::doSend);
        model.addActionListener(e -> chooseModel());
        right.add(send);
        tools.add(right, BorderLayout.EAST);

        composerCard.add(promptPanel, BorderLayout.NORTH);
        composerCard.add(scroller, BorderLayout.CENTER);
        composerCard.add(tools, BorderLayout.SOUTH);
        add(composerCard, BorderLayout.CENTER);

        installKeys();
        installFocusBorder();
        area.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateEnabledState(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateEnabledState(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateEnabledState(); }
        });
        session.setInputListener(this::showPrompt);
        session.addListener(this);
        sessionModelChanged();
        refreshThemePalette();
    }

    /**
     * The widget owns its keys — see {@link ComposerArea} — so what is left here is the two things
     * only the pane knows: what "send" means, and where history lives.
     */
    private void installKeys() {
        area.setOnSend(() -> doSend(null));
        area.setHistory(session::historyPrev, session::historyNext);
    }

    /**
     * Track whether the composer's text area owns the keyboard focus, so the card can draw the
     * focus ring. A {@code FocusListener} answers the direct case; the timer covers the code
     * paths that move focus programmatically (Send, a Stop click, or a prompt answer).
     */
    private void installFocusBorder() {
        java.awt.event.FocusAdapter focus = new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) { scheduleRefreshComposerFocus(); }
            @Override public void focusLost(java.awt.event.FocusEvent e) { scheduleRefreshComposerFocus(); }
        };
        area.addFocusListener(focus);
    }

    void scheduleRefreshComposerFocus() {
        if (focusBorderTimer != null) focusBorderTimer.restart();
        else refreshComposerFocus();
    }

    private void refreshComposerFocus() {
        boolean focused = area != null && area.isFocusOwner();
        Color next = focused ? focusAccent() : null;
        boolean changed = (next == null) != (composerFocusBorder == null)
                || (next != null && !next.equals(composerFocusBorder));
        composerFocusBorder = next;
        if (changed && composerCard != null) {
            composerCard.repaint();
        }
    }

    /** The accent a focused component draws: the LAF's own, or the one the Send button uses. */
    private Color focusAccent() {
        Color accent = javax.swing.UIManager.getColor("Component.focusColor");
        if (accent == null) accent = javax.swing.UIManager.getColor("Component.accentColor");
        if (accent == null) accent = new Color(45, 115, 205);
        return accent;
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
                        "<html><div style='font-size:" + UiFonts.pointSize(UiFonts.CAPTION)
                                + "pt;color:" + UiPalette.css(UiPalette.muted()) + "'>"
                                + escape(p.trailingHint()) + "</div></html>"));
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
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ── sending ───────────────────────────────────────────────────────────────

    private void doSend(ActionEvent e) {
        if (composerAction == ComposerAction.STOP) {
            String failure = session.interruptForegroundStatement();
            if (failure != null) DesktopDialog.showMessage(SwingUtilities.getWindowAncestor(this), "Stop statement", failure);
            updateEnabledState();
            area.requestFocusInWindow();
            return;
        }
        String line = area.composerText();
        NaruInputRequest r = shownFor;
        if (line.isEmpty()) {
            return;
        }
        // A directive is not a message. Route it to the engine as a statement now — even while
        // the agent is mid-turn — instead of letting it fall through to the queue, where it would
        // be delivered as a prompt and answered by the model (the "/model read as a prompt" bug).
        if (NaruSessionModel.isDirective(line)) {
            // The engine call runs on a worker, so the result — and any failure dialog — only
            // arrives later. The draft is cleared now because the line has been handed over.
            String rejection = session.submitDirective(line, failure -> {
                if (failure != null) {
                    DesktopDialog.showMessage(SwingUtilities.getWindowAncestor(this), "Directive", failure);
                }
                updateEnabledState();
            });
            if (rejection != null) {
                DesktopDialog.showMessage(SwingUtilities.getWindowAncestor(this), "Directive", rejection);
            } else {
                area.clearForSend();
            }
            updateEnabledState();
            SwingUtilities.invokeLater(() -> {
                if (area.isShowing()) area.requestFocusInWindow();
                scheduleRefreshComposerFocus();
            });
            return;
        }
        boolean accepted;
        // ask submit() where the line belongs: a restored session parks its conversation task on
        // input without ever handing us a request, and a queue nobody drains looks like a dropped
        // message. answerTargetTaskId() covers both the request and that park.
        long answerTarget = session.answerTargetTaskId();
        if (r != null) {
            clearPrompt();
            session.echoAndDeliver(line, r);
            accepted = true;
        } else if (session.canAcceptInput()
                && (answerTarget < 0 || selectedTaskId <= 0 || selectedTaskId == answerTarget)) {
            session.submit(line);
            accepted = true;
        } else {
            accepted = queueDraft();
        }
        if (accepted) {
            area.clearForSend();
        } else {
            // Never clear a message that no task accepted. The old path dropped it on the
            // floor and looked exactly like a successful send.
            String why = session.inputDisabledReason();
            if (why == null) why = "no task is available to receive this message";
            reason.setText("<html><i>" + escape(why) + "</i></html>");
            reason.setForeground(new Color(180, 95, 60));
        }
        updateEnabledState();
        // Submission changes task state asynchronously; return focus after Swing has applied
        // the enabled-state update so the next keystroke always goes back to the composer.
        SwingUtilities.invokeLater(() -> {
            if (area.isShowing()) area.requestFocusInWindow();
            scheduleRefreshComposerFocus();
        });
    }

    private boolean queueDraft() {
        String draft = area.composerText();
        NaruTask task = queueTargetTask();
        if (draft.isBlank() || task == null) return false;
        try {
            task.queueMessage(draft);
            // The queued line belongs in the conversation immediately. It will be delivered
            // when the task next asks for input; noteQueued() also records the echo so the
            // delivered copy is not drawn a second time.
            session.noteQueued(draft, task.id());
            updateEnabledState();
            area.requestFocusInWindow();
            return true;
        } catch (RuntimeException ex) {
            DesktopDialog.showMessage(SwingUtilities.getWindowAncestor(this), "Message queue", String.valueOf(ex.getMessage()));
            return false;
        }
    }

    private void editQueue() {
        NaruTask task = selectedTask();
        if (task == null) return;
        DefaultListModel<String> rows = new DefaultListModel<>();
        task.queuedMessages().forEach(rows::addElement);
        JList<String> list = new JList<>(rows);
        list.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setVisibleRowCount(8);
        JButton edit = new JButton("Edit");
        JButton remove = new JButton("Delete");
        JButton merge = new JButton("Merge selected");
        JDialog dialog = DesktopDialog.create(SwingUtilities.getWindowAncestor(this), "Queued messages");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(edit); actions.add(merge); actions.add(remove);
        edit.addActionListener(e -> {
            int i = list.getSelectedIndex(); if (i < 0) return;
            String value = DesktopDialog.promptText(SwingUtilities.getWindowAncestor(this), "Edit queued message", "Message", rows.get(i));
            if (value != null && !value.isBlank()) { task.editQueuedMessage(i, value); rows.set(i, value); updateEnabledState(); }
        });
        remove.addActionListener(e -> {
            int[] selected = list.getSelectedIndices();
            for (int i = selected.length - 1; i >= 0; i--) { task.deleteQueuedMessage(selected[i]); rows.remove(selected[i]); }
            updateEnabledState();
        });
        merge.addActionListener(e -> {
            int[] selected = list.getSelectedIndices();
            if (selected.length < 2) return;
            for (int i=1;i<selected.length;i++) if (selected[i] != selected[0] + i) {
                DesktopDialog.showMessage(SwingUtilities.getWindowAncestor(this), "Message queue", "Select adjacent messages to merge."); return;
            }
            task.mergeQueuedMessages(selected[0], selected[selected.length - 1]);
            String merged = String.join("\n\n", java.util.Arrays.stream(selected).mapToObj(rows::get).toList());
            for (int i=selected.length-1;i>=selected[0];i--) rows.remove(i);
            rows.add(selected[0], merged); list.setSelectedIndex(selected[0]); updateEnabledState();
        });
        JPanel body=new JPanel(new BorderLayout(8,8)); body.setBorder(BorderFactory.createEmptyBorder(12,14,12,14));
        JLabel hint=new JLabel("Queued messages are sent in order when this task next asks for input.");
        body.add(hint,BorderLayout.NORTH);body.add(new JScrollPane(list),BorderLayout.CENTER);body.add(actions,BorderLayout.SOUTH);
        DesktopDialog.setBody(dialog,body); DesktopDialog.showCentered(dialog,SwingUtilities.getWindowAncestor(this),560,360);
    }

    private NaruTask selectedTask() {
        if (selectedTaskId >= 0 && session.session() != null) {
            NaruTask t = session.session().findTask(selectedTaskId).orNull();
            if (t != null) return t;
        }
        NaruInputRequest pending = session.pendingRequest();
        if (pending != null) {
            return pending.task();
        }
        return foregroundTask();
    }

    private NaruTask foregroundTask() {
        var current = session.session();
        if (current == null) return null;
        try { return current.findTask(current.foregroundTaskId()).orNull(); }
        catch (RuntimeException ignored) { return null; }
    }

    private NaruTask queueTargetTask() {
        NaruTask selected = selectedTask();
        return selected == null || selected.isCompleted() ? foregroundTask() : selected;
    }

    private void updateEnabledState() {
        String why = session.inputDisabledReason();
        NaruInputRequest pending = session.pendingRequest();
        boolean matchesTask = pending != null && (selectedTaskId <= 0 || selectedTaskId == pending.task().id());
        boolean can = session.canAcceptInput() && matchesTask;
        NaruTask task = queueTargetTask();
        NaruTask foreground = foregroundTask();
        boolean canQueue = session.isRunning() && task != null && !task.isCompleted();
        area.setEnabled(session.isRunning());
        boolean hasText = !area.composerText().isBlank();
        boolean directive = hasText && NaruSessionModel.isDirective(area.composerText());
        boolean busy = false;
        try { busy = foreground != null && !foreground.isCompleted()
                && foreground.status() == net.thevpc.naru.api.scheduler.NaruTaskStatus.RUNNING; }
        catch (RuntimeException ignored) { }
        // The engine's task state is polled, and a turn that is streaming tokens can sit in a
        // state the poll never reads as RUNNING — the worker parks between ticks, and the
        // foreground pointer is not always the task doing the work. The desktop sees the output
        // itself, so live output counts as busy too: that is what turns Send into Stop while
        // the agent is still writing.
        if (!busy && canQueue && session.isStreamingOutput()) busy = true;
        // A directive runs against the conversation whether or not it is busy, so it gets its own
        // action rather than being mistaken for something to enqueue.
        if (directive && session.isRunning()) composerAction = ComposerAction.RUN;
        else if (can) composerAction = ComposerAction.SEND;
        else if (busy && !hasText) composerAction = ComposerAction.STOP;
        else if (canQueue && hasText) composerAction = ComposerAction.ENQUEUE;
        else composerAction = ComposerAction.DISABLED;
        send.setEnabled(composerAction != ComposerAction.DISABLED);
        // Glyph only, never a word: the tools row has to survive an interface size that doubles the
        // text width, and a button elided to "S…" says less than an icon with a tooltip does. The
        // tooltip below is what carries the meaning, so the two switches must stay in step.
        send.setText(switch (composerAction) {
            case RUN -> "▶";
            case SEND -> "➤";
            case ENQUEUE -> "⤵";
            case STOP -> "■";
            case DISABLED -> "➤";
        });
        send.getAccessibleContext().setAccessibleName(switch (composerAction) {
            case RUN -> "Run directive";
            case SEND -> "Send";
            case ENQUEUE -> "Enqueue";
            case STOP -> "Stop";
            case DISABLED -> "Send";
        });
        send.setToolTipText(switch (composerAction) {
            case RUN -> "Run this directive on the conversation now";
            case SEND -> "Send to the task waiting for input";
            case ENQUEUE -> "Queue this message for the task's next prompt";
            case STOP -> "Stop the current statement and return to interactive mode";
            case DISABLED -> "Select a task that can accept or queue input";
        });
        send.setForeground(composerAction == ComposerAction.STOP
                ? new Color(255, 255, 255) : null);
        if (composerAction == ComposerAction.STOP) send.setBackground(new Color(190, 68, 68));
        else refreshThemePalette();
        int queueSize = task == null ? 0 : task.queuedMessages().size();
        // the count is the whole message; the words are what an interface size grows past first
        queued.setText("⏳ " + queueSize);
        queued.setToolTipText(queueSize + " queued message(s) — click to review them");
        queued.setVisible(queueSize > 0);
        queued.setEnabled(task != null);
        if (can) {
            reason.setText(" ");
        } else {
            if (canQueue) why = hasText ? "Agent is working. This message will be enqueued." : "Agent is working. Stop is available.";
            else if (pending != null && !matchesTask) why = "Select task #" + pending.task().id() + " to answer its question";
            reason.setText("<html><i>" + escape(why) + "</i></html>");
            reason.setForeground(canQueue ? javax.swing.UIManager.getColor("Label.disabledForeground") : new Color(180, 95, 60));
        }
    }

    private void applySelectedModel() {
        var key = (net.thevpc.naru.api.model.NaruModelKey) model.getClientProperty("selectedModel");
        if (key == null || selectedTaskId < 0 || session.session() == null) return;
        var task = session.session().findTask(selectedTaskId).orNull();
        if (task == null) return;
        try {
            task.setModel(new net.thevpc.naru.api.model.NaruModelConfig(key));
            // setModel is silent: the status bar's model label only knows what a finished call
            // reported, so the picker has to ask for a re-read.
            session.refreshModelLabel();
        } catch (RuntimeException ex) {
            DesktopDialog.showMessage(SwingUtilities.getWindowAncestor(this), "Model", String.valueOf(ex.getMessage()));
        }
    }

    @Override
    public void sessionModelChanged() {
        refreshModels();
        refreshModes();
        updateEnabledState();
    }

    private void refreshModels() {
        if (session.session() == null) return;
        var task = selectedTaskId < 0 ? null : session.session().findTask(selectedTaskId).orNull();
        var current = task == null || task.model() == null ? null : task.model().key();
        if (modelCatalogSession != session.session()) {
            if (!modelCatalogLoading) {
                modelCatalogLoading = true;
                model.setEnabled(false); model.setText(ModelIcons.glyph(null) + " loading…");
                model.setToolTipText("Loading the model list…");
                var owner = session.session();
                new javax.swing.SwingWorker<java.util.List<net.thevpc.naru.api.model.NaruModelInfo>, Void>() {
                    protected java.util.List<net.thevpc.naru.api.model.NaruModelInfo> doInBackground() {
                        return java.util.List.copyOf(owner.registry().modelsInfos(owner));
                    }
                    protected void done() {
                        modelCatalogLoading = false;
                        if (session.session() != owner) return;
                        try { modelInfos = get(); modelCatalogSession = owner; }
                        catch (Exception ignored) { modelInfos = java.util.List.of(); modelCatalogSession = owner; }
                        model.setEnabled(true); refreshModels();
                    }
                }.execute();
            }
            return;
        }
        model.setEnabled(true);
        model.putClientProperty("selectedModel", current);
        // The button names the model it will change. The header used to, and then the header went
        // away; a bare glyph left the reader to open the picker just to ask which model is live. The
        // key stays in the tooltip because a provider/model:tag is the longest text this row holds.
        model.setText(ModelIcons.buttonLabel(current == null ? null : current.provider(),
                current == null ? null : current.model(), 22));
        String absent = absentProvidersSummary();
        model.setToolTipText((current == null ? "No model selected" : current.toString())
                + " · " + modelInfos.size() + " models exposed by providers registered for this session"
                + (absent == null ? "" : " · " + absent));
    }

    /**
     * Fill the mode combo from the registry and show the selected task's mode.
     *
     * <p>The list is the engine's own — modes are declared by extensions and by the project, so a
     * hard-coded trio of names silently disagreed with any session that had others. Refilling only
     * when the set changed keeps the popup from collapsing under the reader's cursor.
     */
    private void refreshModes() {
        NaruTask task = null;
        try {
            task = selectedTask();
        } catch (RuntimeException stopped) {
            task = null;
        }
        String current = null;
        try {
            current = task == null || task.promptMode() == null ? null : task.promptMode().name();
        } catch (RuntimeException stopped) {
            current = null;
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        var owner = session.session();
        if (owner != null) {
            try {
                names.addAll(owner.registry().modeNames());
            } catch (RuntimeException ignored) {
                // a registry that cannot answer keeps whatever the combo already holds
            }
        }
        if (names.isEmpty()) {
            names.add(net.thevpc.naru.api.mode.NaruPromptMode.DEFAULT);
        }
        modeSyncRunning = true;
        try {
            java.util.List<String> shown = new java.util.ArrayList<>();
            for (int i = 0; i < agentMode.getItemCount(); i++) {
                shown.add(agentMode.getItemAt(i));
            }
            if (!shown.equals(names)) {
                agentMode.removeAllItems();
                for (String n : names) {
                    agentMode.addItem(n);
                }
            }
            if (current != null && names.contains(current)) {
                agentMode.setSelectedItem(current);
            } else if (agentMode.getSelectedItem() == null) {
                agentMode.setSelectedIndex(0);
            }
        } finally {
            modeSyncRunning = false;
        }
        // A mode belongs to a task; with none selected there is nothing for the choice to mean.
        agentMode.setEnabled(task != null && owner != null);
    }

    private void chooseModel() {
        if (modelCatalogLoading) return;
        DefaultListModel<net.thevpc.naru.api.model.NaruModelInfo> rows = new DefaultListModel<>();
        DefaultListModel<net.thevpc.naru.api.model.NaruModelInfo> all = new DefaultListModel<>();
        modelInfos.forEach(info -> { rows.addElement(info); all.addElement(info); });
        JList<net.thevpc.naru.api.model.NaruModelInfo> list = new JList<>(rows);
        list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((l, value, index, selected, focus) -> {
            var cap = value.capabilities();
            String features = cap == null ? "" : String.join(" · ", java.util.stream.Stream.of(cap.isVision() ? "Vision" : null, cap.isTools() ? "Tools" : null, cap.isThinking() ? "Reasoning" : null).filter(java.util.Objects::nonNull).toList());
            String context = cap == null || cap.contextLength() <= 0 ? "Context unknown" : String.format(java.util.Locale.ROOT, "%,d tokens", cap.contextLength());
            String meta = String.join(" · ", java.util.stream.Stream.of(value.quantization(), value.parameterSize(), context, features, rateStatus(value.provider())).filter(s -> s != null && !s.isBlank()).toList());
            JLabel label = new JLabel("<html><b>" + ModelIcons.glyph(value.provider()) + "  "
                    + value.provider() + "/" + value.model() + "</b><br><small>" + meta + "</small></html>"); label.setOpaque(true);
            label.setBackground(selected ? l.getSelectionBackground() : l.getBackground());
            label.setForeground(selected ? l.getSelectionForeground() : l.getForeground());
            label.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8)); return label;
        });
        javax.swing.JTextField filter = new javax.swing.JTextField();
        filter.putClientProperty("JTextField.placeholderText", "Filter models available in this session…");
        javax.swing.JComboBox<String> provider = new javax.swing.JComboBox<>(); provider.addItem("All providers");
        modelInfos.stream().map(net.thevpc.naru.api.model.NaruModelInfo::provider).filter(java.util.Objects::nonNull).distinct().sorted(String.CASE_INSENSITIVE_ORDER).forEach(provider::addItem);
        javax.swing.JComboBox<String> sort = new javax.swing.JComboBox<>(new String[]{"Name", "Context size"});
        javax.swing.JComboBox<String> source = new javax.swing.JComboBox<>(new String[]{"All sources", "Local", "Remote", "Free (declared)"});
        javax.swing.JComboBox<String> capability = new javax.swing.JComboBox<>(new String[]{"All capabilities", "Vision", "Tools", "Reasoning", "Embeddings"});
        JDialog dialog = DesktopDialog.create(SwingUtilities.getWindowAncestor(this), "Choose model");
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void apply() {
                String q = filter.getText().trim().toLowerCase(java.util.Locale.ROOT); rows.clear();
                java.util.List<net.thevpc.naru.api.model.NaruModelInfo> matches = new java.util.ArrayList<>();
                for (int i=0;i<all.size();i++) { var x=all.get(i); String hay=x.toString()+" "+x.quantization()+" "+x.parameterSize()+" "+x.family()+" "+(x.capabilities()==null?"":x.capabilities().keys()); boolean providerMatch="All providers".equals(provider.getSelectedItem()) || java.util.Objects.equals(provider.getSelectedItem(),x.provider()); boolean sourceMatch=switch(String.valueOf(source.getSelectedItem())){case "Local"->isLocal(x);case "Remote"->!isLocal(x);case "Free (declared)"->isDeclaredFree(x);default->true;}; String cap=String.valueOf(capability.getSelectedItem()); boolean capMatch=switch(cap){case "Vision"->x.capabilities()!=null&&x.capabilities().isVision();case "Tools"->x.capabilities()!=null&&x.capabilities().isTools();case "Reasoning"->x.capabilities()!=null&&x.capabilities().isThinking();case "Embeddings"->x.capabilities()!=null&&x.capabilities().isEmbedding();default->true;}; if(providerMatch&&sourceMatch&&capMatch&&hay.toLowerCase(java.util.Locale.ROOT).contains(q)) matches.add(x); }
                if ("Context size".equals(sort.getSelectedItem())) matches.sort(java.util.Comparator.comparingLong((net.thevpc.naru.api.model.NaruModelInfo x)->x.capabilities()==null?0:x.capabilities().contextLength()).reversed()); else matches.sort(java.util.Comparator.comparing(x->x.provider()+"/"+x.model(),String.CASE_INSENSITIVE_ORDER));
                matches.forEach(rows::addElement);
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { apply(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { apply(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { apply(); }
        });
        JButton select = new JButton("Use model");
        select.addActionListener(e -> {
            var value = list.getSelectedValue();
            if (value != null) { model.putClientProperty("selectedModel", value.key()); applySelectedModel(); }
            dialog.dispose();
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() { public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2) select.doClick(); } });
        JPanel body = new JPanel(new BorderLayout(6, 6)); body.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel top = new JPanel(new BorderLayout(0, 6));
        JPanel filters = new JPanel(new BorderLayout(5,5)); filters.add(filter,BorderLayout.NORTH);
        JPanel facets=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0)); facets.add(provider);facets.add(source);facets.add(capability);filters.add(facets,BorderLayout.SOUTH);
        JPanel titleAndSort = new JPanel(new BorderLayout(5,0)); titleAndSort.add(new JLabel("Models available in this session"),BorderLayout.CENTER); titleAndSort.add(sort,BorderLayout.EAST);
        top.add(titleAndSort, BorderLayout.NORTH);
        String absent = absentProvidersSummary();
        if (absent != null) {
            // A provider with an empty catalog is simply not in the list above, so a user whose
            // remote models are gone cannot tell "no key" from "not installed". Say which
            // providers are registered but contributed nothing, and why.
            JLabel note = new JLabel("<html><small>" + absent + "</small></html>");
            note.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
            note.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            JPanel north = new JPanel(new BorderLayout());
            north.add(titleAndSort, BorderLayout.NORTH);
            north.add(note, BorderLayout.SOUTH);
            top.add(north, BorderLayout.NORTH);
        }
        top.add(filters, BorderLayout.SOUTH);
        java.awt.event.ActionListener refilter=e->{String q=filter.getText();filter.setText(q+" ");filter.setText(q);};
        provider.addActionListener(refilter);source.addActionListener(refilter);capability.addActionListener(refilter);sort.addActionListener(refilter);
        body.add(top, BorderLayout.NORTH); body.add(new JScrollPane(list), BorderLayout.CENTER); body.add(select, BorderLayout.SOUTH);
        DesktopDialog.setBody(dialog, body);
        DesktopDialog.showCentered(dialog, SwingUtilities.getWindowAncestor(this), 560, 480);
    }

    private boolean isDeclaredFree(net.thevpc.naru.api.model.NaruModelInfo info) {
        var registration=registration(info.provider());
        try { return registration!=null && Boolean.TRUE.equals(registration.booleanValue("free").orNull()); }
        catch(RuntimeException ignored) { return false; }
    }

    /**
     * One line naming the providers that are registered but contributed no model to the catalog,
     * with the reason each gives. Null when every provider either listed models or has nothing to
     * report, so the common case adds no noise.
     */
    private String absentProvidersSummary() {
        var current = session.session();
        if (current == null) return null;
        java.util.List<String> listed = new java.util.ArrayList<>();
        for (var info : modelInfos) {
            if (info.provider() != null) listed.add(info.provider().toLowerCase(java.util.Locale.ROOT));
        }
        java.util.List<String> notes = new java.util.ArrayList<>();
        try {
            for (var p : current.registry().modelProviders().values()) {
                if (listed.contains(p.name().toLowerCase(java.util.Locale.ROOT))) continue;
                if (!p.isEnabled()) {
                    notes.add(p.name() + ": disabled");
                    continue;
                }
                String why = p.statusMessage(current);
                if (why != null && !why.isBlank()) {
                    notes.add(p.name() + ": " + why);
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        if (notes.isEmpty()) return null;
        return "No models from — " + String.join("; ", notes);
    }

    private boolean isLocal(net.thevpc.naru.api.model.NaruModelInfo info) {
        String p=String.valueOf(info.provider()).toLowerCase(java.util.Locale.ROOT);
        if(p.contains("ollama")||p.contains("local")||p.contains("lmstudio")||p.contains("llamacpp")) return true;
        var r=registration(info.provider()); if(r==null) return false;
        try { if(Boolean.TRUE.equals(r.booleanValue("local").orNull())) return true; } catch(RuntimeException ignored) {}
        for(String key:new String[]{"url","baseUrl","endpoint","host"}) try {
            String v=r.stringValue(key).orNull(); if(v!=null){String x=v.toLowerCase(java.util.Locale.ROOT);if(x.contains("localhost")||x.contains("127.0.0.1")||x.contains("::1"))return true;}
        } catch(RuntimeException ignored) {}
        return false;
    }

    private net.thevpc.naru.api.model.NaruModelRegistration registration(String provider) {
        try { return session.session()==null?null:session.session().registrations().get(provider); }
        catch(RuntimeException ignored) { return null; }
    }

    private String rateStatus(String provider) {
        for(var info:session.providerRateLimits()) if(java.util.Objects.equals(info.providerName(),provider)) {
            for(var bucket:info.tokenBuckets()) {
                Integer limit=bucket.getLimit().orNull(), remaining=bucket.getRemaining().orNull();
                if(limit!=null&&remaining!=null&&limit>0) {
                    if(remaining==0) return "Rate limited";
                    int used=100-(int)Math.round(100d*remaining/limit);
                    return "Quota "+used+"% used";
                }
            }
            var retry=info.retryAfter().orNull(); if(retry!=null) return "Retry after "+retry;
        }
        return "";
    }

    /** Prompt the user to look at the transcript when output stops mid-turn. */
    public void focusInput() {
        area.requestFocusInWindow();
    }

    public void refreshThemePalette() {
        java.awt.Color accent = javax.swing.UIManager.getColor("Component.accentColor");
        if (accent == null) accent = new java.awt.Color(45, 115, 205);
        send.setBackground(accent); send.setForeground(contrast(accent)); send.setOpaque(true);
        send.putClientProperty("JButton.buttonType", "roundRect");
        for (JButton b : new JButton[]{queued, attach}) b.putClientProperty("JButton.buttonType", "toolBarButton");
        refreshComposerFocus();
        revalidate(); repaint();
    }

    /** Whether the composer card is drawing its focus ring, for a gate run that has to see it. */
    public boolean composerFocusRingVisible() { return composerFocusBorder != null; }

    @Override public void addNotify() {
        super.addNotify();
        SwingUtilities.invokeLater(() -> { javax.swing.JRootPane root=SwingUtilities.getRootPane(this); if(root!=null) root.setDefaultButton(send); scheduleRefreshComposerFocus(); });
    }

    private static java.awt.Color contrast(java.awt.Color c) {
        return c.getRed()*299+c.getGreen()*587+c.getBlue()*114 > 145000 ? java.awt.Color.BLACK : java.awt.Color.WHITE;
    }

    public ComposerArea composer() { return area; }

    public void setSelectedTaskId(long taskId) {
        selectedTaskId = taskId;
        NaruTask task = selectedTask();
        thinking.setEnabled(task != null);
        thinking.setSelected(task == null || net.thevpc.naru.api.model.NaruThinkingConfig.find(task)
                .orElse(Boolean.TRUE));
        refreshModels();
        refreshModes();
        NaruInputRequest pending = session.pendingRequest();
        if (pending != null && (taskId <= 0 || pending.task().id() == taskId)) showPrompt(pending);
        else if (shownFor != null) clearPrompt();
        updateEnabledState();
    }

    public void dispose() {
        session.removeListener(this);
        session.setInputListener(null);
        if (focusBorderTimer != null) focusBorderTimer.stop();
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
