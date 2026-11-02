package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.plan.NaruPlanItemSnapshot;
import net.thevpc.naru.api.plan.NaruPlanItemState;
import net.thevpc.naru.api.plan.NaruPlanReader;
import net.thevpc.naru.api.plan.NaruPlanSnapshot;
import net.thevpc.naru.api.plan.NaruPlanState;
import net.thevpc.naru.desktop.model.NaruSessionModel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The plan board: every plan this session holds, with its items and their states, and the controls
 * that change the one the reader is pointing at.
 *
 * <p>It sits in the rail rather than inside Inspect because a plan is what the run is for. The
 * inspector answers "what happened"; this answers "what is supposed to happen next", and a reader who
 * has to open a tab to find the second one stops trusting it.
 *
 * <p>The desktop does not compile against the plan extension — it reads plans through
 * {@link NaruPlanReader}, so a session without that feature shows an honest empty panel instead of a
 * stack trace. Writing is a different matter and goes the only way a host is allowed to write: the
 * board composes {@code /plan} lines and hands them to the session, so the graph's rules — readiness
 * derived from dependencies, completion gated on a validator, a running item's text owned by its
 * task — stay in the extension that enforces them. A click is a statement the transcript also shows.
 */
public final class PlanPane extends JPanel implements NaruSessionModel.Listener {

    private final NaruSessionModel model;
    /** The snapshots, read the way a session reads them; a test installs its own board. */
    private final Supplier<List<NaruPlanSnapshot>> source;
    /** The one write path: a directive line, handed to the engine. */
    private final Director director;
    private final Column stack = new Column();
    /** Debounced: a streaming turn fires the model listener on every chunk, and a plan is not urgent. */
    private final Timer refresh = new Timer(250, e -> reload());
    private boolean reading;
    /** Whether the column currently shows a real board rather than a placeholder line. */
    private boolean drawn;
    private long generation;
    /** The board as it was last drawn, so closing an editor can put it back without a fresh read. */
    private List<NaruPlanSnapshot> last = List.of();
    /** The row an editor has replaced: a repaint while the reader is typing would swallow the text. */
    private JComponent openEditor;
    /** Whether the mode a write would run under carries out work; an active plan is live only then. */
    private boolean executing;
    /** The always-present "new plan" control, above the board. */
    private final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));

    public PlanPane(NaruSessionModel model) {
        this(model, null, null);
    }

    PlanPane(NaruSessionModel model, Supplier<List<NaruPlanSnapshot>> source, Director director) {
        super(new BorderLayout());
        this.model = model;
        this.source = source;
        this.director = director == null ? this::sendToSession : director;
        bar.setOpaque(false);
        JButton newPlan = new JButton("＋ New plan");
        newPlan.setToolTipText("Create a plan holding only its goal; it stays dormant until you activate it");
        newPlan.setFocusable(false);
        newPlan.addActionListener(e -> openCreateEditor());
        bar.add(newPlan);
        add(bar, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(stack);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(14);
        add(scroll, BorderLayout.CENTER);
        refresh.setRepeats(false);
        showEmpty();
        model.addListener(this);
        // The model only announces a change, so a pane built after the last announcement — which is
        // what happens when the rail is rebuilt for a session that is already open — would keep the
        // placeholder it was constructed with. Becoming visible is the one event that always precedes
        // a reader looking at the board.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                refresh.restart();
            }
        });
    }

    @Override
    public void sessionModelChanged() {
        refresh.restart();
    }

    /** Stop listening; the window calls this before dropping the pane. */
    public void dispose() {
        refresh.stop();
        generation++;
        model.removeListener(this);
    }

    /**
     * One read of the board. A read that lands while a task is rewriting the graph is a retry, not a
     * fact about the plan, so it must not be drawn.
     */
    private enum Read {ABSENT, RETRY}

    private void reload() {
        if (openEditor != null) {
            // the reader is mid-sentence in a field; repainting now would replace the row under them
            refresh.restart();
            return;
        }
        if (source == null && model.session() == null) {
            bar.setVisible(false);
            show("No session is open.");
            return;
        }
        bar.setVisible(true);
        if (reading) {
            refresh.restart();
            return;
        }
        long mine = ++generation;
        reading = true;
        new SwingWorker<Object, Void>() {
            @Override
            protected Object doInBackground() {
                try {
                    List<NaruPlanSnapshot> plans = source == null ? readSession() : source.get();
                    return plans == null ? Read.ABSENT : (Object) plans;
                } catch (RuntimeException e) {
                    // the graph is a live structure the running task mutates; the next debounce
                    // gets a consistent one, so say nothing rather than show a half-read plan
                    return Read.RETRY;
                }
            }

            @Override
            protected void done() {
                reading = false;
                if (mine != generation) {
                    return;
                }
                Object value;
                try {
                    value = get();
                } catch (Exception e) {
                    value = Read.RETRY;
                }
                if (value == Read.ABSENT) {
                    bar.setVisible(false);
                    show("The plan extension is not installed in this session.");
                } else if (value instanceof List<?>) {
                    List<NaruPlanSnapshot> plans = cast(value);
                    executing = model.isExecutingMode();
                    if (plans.isEmpty()) {
                        showEmpty();
                    } else {
                        render(plans);
                    }
                } else {
                    // only a board that was never drawn has to admit it is blank; a stale one keeps
                    // showing what it knew while the retry comes round
                    if (!drawn) {
                        show("Reading the plan…");
                    }
                    refresh.restart();
                }
            }
        }.execute();
    }

    /** The session's own board; null when no feature reads plans for this session. */
    private List<NaruPlanSnapshot> readSession() {
        NaruPlanReader reader = NaruPlanReader.of(model.session());
        return reader == null ? null : reader.allPlans();
    }

    @SuppressWarnings("unchecked")
    private static List<NaruPlanSnapshot> cast(Object plans) {
        return (List<NaruPlanSnapshot>) plans;
    }

    private void render(List<NaruPlanSnapshot> plans) {
        last = plans;
        stack.removeAll();
        for (NaruPlanSnapshot p : plans) {
            addPlan(p);
        }
        stack.add(Box.createVerticalGlue());
        drawn = true;
        stack.revalidate();
        stack.repaint();
    }

    private void addPlan(NaruPlanSnapshot p) {
        // An activated plan is only actually being worked through in an executing mode: everywhere
        // else "active" means "chosen", not "running", and the board says so rather than showing a
        // green dot for a plan nothing is dispatching.
        boolean live = p.active() && executing;
        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel mark = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        mark.setOpaque(false);
        StatusDot dot = new StatusDot();
        dot.setColor(hue(p.state()));
        dot.setPulsing(live && p.state() == NaruPlanState.OPEN);
        mark.add(dot);
        header.add(mark, BorderLayout.WEST);
        // the tally goes on its own line rather than beside the goal: a long goal pushed it into a
        // wrapped flow row that the reader never saw, and the progress count is the one number on
        // this panel that says how much of the run is left
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        String goalText = p.goal() == null || p.goal().isBlank() ? "(no goal)" : p.goal();
        JLabel goal = new JLabel(goalText);
        UiFonts.apply(goal, UiFonts.BODY, Font.BOLD);
        goal.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (p.active() && !live) {
            goal.setForeground(UiPalette.muted());
        }
        text.add(goal);
        String tallyText = p.progressLabel() + " · " + p.state().name().toLowerCase();
        if (p.active()) {
            tallyText += live ? " · active" : " · active, dormant — switch to an executing mode to run";
        }
        JLabel tally = new JLabel(tallyText);
        UiFonts.apply(tally, UiFonts.CAPTION, Font.PLAIN);
        tally.setForeground(live ? UiPalette.accent() : UiPalette.muted());
        tally.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(tally);
        header.add(text, BorderLayout.CENTER);
        header.setToolTipText("Plan " + p.id() + " · " + tallyText);
        // Every plan is editable: the directive takes --plan=<id>, so a pencil on a dormant plan
        // sends a line the engine accepts without activating it.
        clickToEdit(goal, "click to restate what this plan is for", () ->
                editor(p.goal() == null ? "" : p.goal(), PlanPane::isNotBlank, text1 -> {
                    String line = PlanDirectives.goal(p.id(), text1);
                    if (line != null) {
                        director.send(line);
                    }
                }));
        header.add(planActions(p, live), BorderLayout.EAST);
        stack.add(header);

        JPanel items = new JPanel();
        items.setOpaque(false);
        items.setLayout(new BoxLayout(items, BoxLayout.Y_AXIS));
        items.setBorder(BorderFactory.createEmptyBorder(2, 18, 8, 8));
        items.setAlignmentX(Component.LEFT_ALIGNMENT);
        // the header's "＋" finds this panel by the plan it belongs to; the board is rebuilt whole
        items.putClientProperty("planItems", p.id());
        for (NaruPlanItemSnapshot i : p.items()) {
            items.add(itemRow(i, p.id()));
        }
        // the hint is a prompt, not a footer of the plan: it is only worth a row where the reader
        // is already working, so it marks the active plan and the "＋" control covers the rest
        if (p.active()) {
            items.add(addItemHint(items, p.id()));
        }
        stack.add(items);
        stack.add(Box.createVerticalStrut(6));
    }

    private JComponent planActions(NaruPlanSnapshot p, boolean live) {
        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        east.setOpaque(false);
        if (p.active()) {
            east.add(action("⏸", live ? "Stop dispatching this plan" : "Deactivate this plan",
                    () -> director.send(PlanDirectives.deactivate())));
        } else {
            east.add(action("▶", "Activate this plan; its ready items start dispatching",
                    () -> director.send(PlanDirectives.activate(p.id()))));
        }
        east.add(action("＋", "Add an item to this plan",
                () -> appendItemEditor(null, p.id())));
        return east;
    }

    /** A quiet row that becomes the field, so "add an item" is not a dialog nobody asked for. */
    private JComponent addItemHint(JPanel items, String planId) {
        JLabel hint = new JLabel("＋ add an item, or type /plan add in the composer");
        UiFonts.apply(hint, UiFonts.CAPTION, Font.PLAIN);
        hint.setForeground(UiPalette.muted());
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        // marks the row a new item is inserted above, so it never lands below the prompt
        hint.putClientProperty("planAddHint", Boolean.TRUE);
        hint.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                appendItemEditor(items, planId);
            }
        });
        return hint;
    }

    /**
     * Insert a blank item field into {@code items}. {@code items} is null when the header's "＋"
     * fired and the panel has to be found; the plan always has one by the time the field opens.
     */
    private void appendItemEditor(JPanel items, String planId) {
        JPanel target = items == null ? itemsPanelFor(planId) : items;
        if (target == null) {
            return;
        }
        JPanel row = editor("", PlanPane::isNotBlank, text -> {
            String line = PlanDirectives.addItem(planId, text);
            if (line != null) {
                director.send(line);
            }
        });
        // the hint is the last row, and a new item belongs above it rather than below it — the
        // hint is a prompt, not a footer of the plan
        int at = target.getComponentCount();
        if (at > 0 && target.getComponent(at - 1) instanceof JComponent last1
                && Boolean.TRUE.equals(last1.getClientProperty("planAddHint"))) {
            at = at - 1;
        }
        target.add(row, at);
        openAndFocus(row);
    }

    /**
     * The items panel of a plan by id, for when the "＋" control in the header fires rather than
     * the hint row inside the panel. The plan is on the board already, so the panel is present.
     */
    private JPanel itemsPanelFor(String planId) {
        for (Component c : stack.getComponents()) {
            if (c instanceof JPanel panel && planId.equals(panel.getClientProperty("planItems"))) {
                return panel;
            }
        }
        return null;
    }

    private static boolean isNotBlank(String text) {
        return !PlanDirectives.flatten(text).isEmpty();
    }

    private JComponent itemRow(NaruPlanItemSnapshot i, String planId) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel mark = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        mark.setOpaque(false);
        StatusDot dot = new StatusDot();
        dot.setColor(hue(i.state()));
        dot.setPulsing(i.state() == NaruPlanItemState.RUNNING || i.state() == NaruPlanItemState.VALIDATING);
        mark.add(dot);
        JLabel key = new JLabel(i.key());
        UiFonts.applyMono(key, UiFonts.CAPTION, Font.PLAIN);
        key.setForeground(UiPalette.muted());
        mark.add(key);
        row.add(mark, BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel line = new JLabel(i.description());
        UiFonts.apply(line, UiFonts.BODY, Font.PLAIN);
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(line);
        String aside = aside(i);
        if (!aside.isEmpty()) {
            JLabel note = new JLabel(aside);
            UiFonts.apply(note, UiFonts.CAPTION, Font.PLAIN);
            note.setForeground(UiPalette.muted());
            note.setAlignmentX(Component.LEFT_ALIGNMENT);
            text.add(note);
        }
        row.add(text, BorderLayout.CENTER);
        row.add(itemActions(i, planId, row, line), BorderLayout.EAST);
        // a clipped description is only a clipped description if the reader cannot get at the whole
        row.setToolTipText(i.state().name().toLowerCase() + " · " + i.description()
                + (aside.isEmpty() ? "" : " · " + aside));
        return row;
    }

    private JComponent itemActions(NaruPlanItemSnapshot i, String planId, JPanel row, JLabel label) {
        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        east.setOpaque(false);
        boolean inSomeonesHands = i.state() == NaruPlanItemState.RUNNING
                || i.state() == NaruPlanItemState.VALIDATING;
        if (inSomeonesHands) {
            JLabel busy = new JLabel("…");
            UiFonts.apply(busy, UiFonts.CAPTION, Font.PLAIN);
            busy.setForeground(UiPalette.muted());
            busy.setToolTipText(i.state().name().toLowerCase()
                    + " — a task is working from this text, so the plan will not let it change");
            east.add(busy);
            return east;
        }
        clickToEdit(label, "click to rewrite this item", () ->
                editor(i.description(), PlanPane::isNotBlank, text -> {
                    String line = PlanDirectives.editItem(planId, i.id(), text);
                    if (line != null) {
                        director.send(line);
                    }
                }));
        east.add(action("×", "Remove this item from the plan",
                () -> director.send(PlanDirectives.removeItem(planId, i.id()))));
        return east;
    }

    /**
     * A field with commit and cancel, replacing whatever it was editing. Blank text is refused
     * here — an empty description is an error the extension would answer, and the reader should not
     * have to see a transcript card to learn that the Enter key did nothing.
     */
    private JPanel editor(String initial, java.util.function.Predicate<String> accept, Consumer<String> commit) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JTextField field = new JTextField(initial);
        UiFonts.apply(field, UiFonts.BODY, Font.PLAIN);
        field.putClientProperty("JTextField.placeholderText", "the whole sentence, not a fragment");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        buttons.setOpaque(false);
        Runnable finish = () -> {
            String text = field.getText();
            closeEditor();
            if (accept.test(text)) {
                commit.accept(text);
            }
        };
        field.addActionListener(e -> finish.run());
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    closeEditor();
                }
            }
        });
        buttons.add(action("✓", "Send this change", finish::run));
        buttons.add(action("×", "Cancel — nothing is sent", this::closeEditor));
        row.add(field, BorderLayout.CENTER);
        row.add(buttons, BorderLayout.EAST);
        return row;
    }

    /** The "New plan" control: a field at the top of the board for the goal of the plan being made. */
    private void openCreateEditor() {
        if (openEditor != null) {
            return;
        }
        JPanel row = editor("", PlanPane::isNotBlank, text -> {
            String line = PlanDirectives.create(text);
            if (line != null) {
                director.send(line);
            }
        });
        stack.add(row, 0);
        openAndFocus(row);
    }

    private void closeEditor() {
        openEditor = null;
        if (last.isEmpty()) {
            showEmpty();
        } else {
            render(last);
        }
    }

    private static JTextField findField(JComponent root) {
        for (Component c : ((Container) root).getComponents()) {
            if (c instanceof JTextField f) {
                return f;
            }
            if (c instanceof JComponent jc) {
                JTextField found = findField(jc);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void clickToEdit(JLabel label, String hint, Supplier<JComponent> build) {
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        String existing = label.getToolTipText();
        label.setToolTipText(existing == null ? hint : existing + " · " + hint);
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                JComponent editor = build.get();
                Container parent = label.getParent();
                int index = indexOf(parent, label);
                if (index < 0) {
                    return;
                }
                parent.remove(index);
                parent.add(editor, index);
                if (parent instanceof JComponent jc) {
                    jc.revalidate();
                }
                openAndFocus(editor);
            }
        });
    }

    private void openAndFocus(JComponent row) {
        openEditor = row;
        row.revalidate();
        SwingUtilities.invokeLater(() -> {
            JTextField f = findField(row);
            if (f != null) {
                f.requestFocusInWindow();
                f.selectAll();
            }
        });
    }

    private static int indexOf(Container parent, Component child) {
        if (parent == null) {
            return -1;
        }
        for (int i = 0; i < parent.getComponentCount(); i++) {
            if (parent.getComponent(i) == child) {
                return i;
            }
        }
        return -1;
    }

    private JButton action(String glyph, String tip, Runnable onClick) {
        JButton b = GlyphButton.of(glyph, tip);
        b.addActionListener(e -> onClick.run());
        return b;
    }

    /** The line goes to the engine the way the composer sends it: as a directive, on the EDT. */
    private void sendToSession(String line) {
        String rejection = model.submitDirective(line, failure -> {
            if (failure != null) {
                DesktopDialog.showMessage(SwingUtilities.getWindowAncestor(this), "Plan", failure);
            }
        });
        if (rejection != null) {
            DesktopDialog.showMessage(SwingUtilities.getWindowAncestor(this), "Plan", rejection);
        }
    }

    /** The small truth beside the item: what gates it, what it is waiting on, who holds it. */
    private static String aside(NaruPlanItemSnapshot i) {
        StringBuilder sb = new StringBuilder();
        if (!i.dependsOnKeys().isEmpty()) {
            sb.append("after ").append(String.join(", ", i.dependsOnKeys()));
        }
        if (i.state() == NaruPlanItemState.READY) {
            add(sb, "ready");
        }
        if (i.taskId() != null) {
            add(sb, "task #" + i.taskId());
        }
        if (i.attempts() > 1) {
            add(sb, i.attempts() + " attempts");
        }
        String notes = i.notes();
        if (notes != null && !notes.isBlank()) {
            add(sb, notes.strip());
        }
        return sb.toString();
    }

    private static void add(StringBuilder sb, String part) {
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(part);
    }

    /**
     * The hue of a state, from the four the cockpit owns.
     *
     * <p>Green means work is happening or landed, amber that an item is ready for whoever picks it up,
     * grey that it is still gated, red that the reader has to look. The word is always there too: a
     * hue is the fast scan, never the only reading.
     */
    private static Color hue(NaruPlanItemState state) {
        switch (state) {
            case DONE:
            case RUNNING:
            case VALIDATING:
                return UiPalette.LIVE;
            case READY:
                return UiPalette.PENDING;
            case BLOCKED:
            case FAILED:
                return UiPalette.ATTENTION;
            case PENDING:
            default:
                return UiPalette.IDLE;
        }
    }

    private static Color hue(NaruPlanState state) {
        switch (state) {
            case OPEN:
            case COMPLETED:
                return UiPalette.LIVE;
            case BLOCKED:
                return UiPalette.ATTENTION;
            case PENDING:
            default:
                return UiPalette.IDLE;
        }
    }

    /**
     * The honest empty state: the board exists and can be written to, it just has nothing on it.
     * {@link #openCreateEditor} is reachable from the toolbar, so the words point at it.
     */
    private void showEmpty() {
        show("No plan yet — use ＋ New plan above, or ask NARU to plan the work.");
    }

    private void show(String message) {
        stack.removeAll();
        JLabel label = new JLabel(message);
        UiFonts.apply(label, UiFonts.BODY, Font.PLAIN);
        label.setForeground(UiPalette.muted());
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        stack.add(label);
        stack.add(Box.createVerticalGlue());
        drawn = false;
        stack.revalidate();
        stack.repaint();
    }

    /** What the board does when a reader acts: hand the engine a {@code /plan} line. */
    interface Director {
        void send(String line);
    }

    /**
     * The column always takes the viewport's width, whatever the rows' natural width is, so a long
     * description clips at the panel edge instead of stretching the board into a horizontal scrollbar.
     */
    private static final class Column extends JPanel implements Scrollable {
        Column() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(16, (orientation == SwingConstants.VERTICAL
                    ? visibleRect.height : visibleRect.width) - 16);
        }
    }
}
