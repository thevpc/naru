package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.plan.NaruPlanItemSnapshot;
import net.thevpc.naru.api.plan.NaruPlanItemState;
import net.thevpc.naru.api.plan.NaruPlanListener;
import net.thevpc.naru.api.plan.NaruPlanService;
import net.thevpc.naru.api.plan.NaruPlanSnapshot;
import net.thevpc.naru.api.plan.NaruPlanState;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.render.MarkdownHtml;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The plan board: a list of every plan this session holds, with the selected one opened in place,
 * its description, its items and their states, and the controls that change it.
 *
 * <p>It sits in the rail rather than inside Inspect because a plan is what the run is for. The
 * inspector answers "what happened"; this answers "what is supposed to happen next", and a reader who
 * has to open a tab to find the second one stops trusting it.
 *
 * <p>Every registered plan is listed, even when it is dormant. Exactly one row is open at a time —
 * the active plan when there is one, otherwise the first registered plan — so the panel answers both
 * "which plans exist?" and "what is in the one I am looking at?" without turning a long session into
 * a wall of items.
 *
 * <p>The desktop does not compile against the plan extension — it reads and writes plans through
 * {@link NaruPlanService}, so a session without that feature shows an honest empty panel instead of a
 * stack trace. Nothing here composes a {@code /plan} line and hands it to the composer: a click is one
 * call on the service, which means the same sentences work whether or not the transcript is listening,
 * and the extension stays the only writer of the graph.
 *
 * <p>A plan also changes from underneath: a task reports progress through its tool, a mode switch
 * activates a plan. The board therefore subscribes to {@link NaruPlanListener} as well as reloading on
 * its own events, so an internal update is drawn rather than waiting for the reader to click.
 */
public final class PlanPane extends JPanel implements NaruSessionModel.Listener {

    /** A declaration key: the short handle an item is referred to by in the plan's own text. */
    private static final java.util.regex.Pattern KEY =
            java.util.regex.Pattern.compile("[A-Za-z0-9._-]+");

    /** A plan's prose is a document: the editor is tall enough to hold a page of Markdown. */
    private static final int PROSE_HEIGHT = 240;
    /** An item is one sentence most of the time: the editor gives its rail row back quickly. */
    private static final int ITEM_HEIGHT = 110;

    private final NaruSessionModel model;
    /** The service as the session exposes it; a test installs its own board. */
    private final Supplier<NaruPlanService> source;
    /** Where a write runs; null means a background {@link SwingWorker}, the production default. */
    private final java.util.concurrent.Executor writes;
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
    /** The plan whose items are open beneath its row; one at a time, so the list stays a list. */
    private String selectedPlanId;
    /** Whether the mode a write would run under carries out work; an active plan is live only then. */
    private boolean executing;
    /** The board this pane is subscribed to, so a session switch can unsubscribe from the old one. */
    private NaruPlanService subscribed;
    /** The extension announces writes by tasks and directives on its own thread; the debounce coalesces. */
    private final NaruPlanListener plansAnnounced = () -> SwingUtilities.invokeLater(refresh::restart);
    /** The always-present "new plan" control, above the board. */
    private final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));

    public PlanPane(NaruSessionModel model) {
        this(model, null, null);
    }

    PlanPane(NaruSessionModel model, Supplier<NaruPlanService> source) {
        this(model, source, null);
    }

    PlanPane(NaruSessionModel model, Supplier<NaruPlanService> source,
             java.util.concurrent.Executor writes) {
        super(new BorderLayout());
        this.model = model;
        this.source = source;
        this.writes = writes;
        bar.setOpaque(false);
        JButton newPlan = GlyphButton.of("＋", "New plan — name its goal, then add items to it");
        bar.add(newPlan);
        newPlan.addActionListener(e -> openCreateEditor());
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
        subscribe(null);
        model.removeListener(this);
    }

    /**
     * Hold exactly one subscription, on the board that was just read. The extension keys its listener
     * table by the listener object, so handing back the same {@code plansAnnounced} is what makes
     * removal work; a fresh lambda each time would leak a subscriber onto every session switch.
     */
    private void subscribe(NaruPlanService service) {
        if (subscribed == service) {
            return;
        }
        if (subscribed != null) {
            try {
                subscribed.removePlanListener(plansAnnounced);
            } catch (RuntimeException ignored) {
                // a board that cannot unsubscribe still has to let go of it
            }
        }
        subscribed = service;
        if (service != null) {
            try {
                service.addPlanListener(plansAnnounced);
            } catch (RuntimeException ignored) {
                // the default method is a no-op on an older extension: polling still draws the board
            }
        }
    }

    /**
     * One read of the board. A read that lands while a task is rewriting the graph is a retry, not a
     * fact about the plan, so it must not be drawn.
     */
    private enum Read {ABSENT, RETRY}

    /** A board, and the service it came from, so the subscription follows the session's own instance. */
    private record Board(NaruPlanService service, List<NaruPlanSnapshot> plans) {
    }

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
                    NaruPlanService service = plans();
                    return service == null ? Read.ABSENT : (Object) new Board(service, service.allPlans());
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
                    subscribe(null);
                    show("The plan extension is not installed in this session.");
                } else if (value instanceof Board board) {
                    bar.setVisible(true);
                    subscribe(board.service);
                    executing = model.isExecutingMode();
                    if (board.plans.isEmpty()) {
                        showEmpty();
                    } else {
                        render(board.plans);
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

    /** The session's own service; null when no feature reads or writes plans for this session. */
    private NaruPlanService plans() {
        try {
            return source == null ? NaruPlanService.of(model.session()) : source.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Draw the list and the one open plan. The active plan is listed first and is opened when the
     * reader has not chosen another one, so "the plan the run is for" never starts below the fold.
     */
    private void render(List<NaruPlanSnapshot> plans) {
        List<NaruPlanSnapshot> ordered = new ArrayList<>(plans);
        // stable: the active plan first, everything else in creation order
        ordered.sort((a, b) -> Boolean.compare(b.active(), a.active()));
        last = ordered;
        selectedPlanId = selectionFor(ordered);

        stack.removeAll();
        stack.add(sectionLabel("Registered plans · " + ordered.size()));
        for (NaruPlanSnapshot p : ordered) {
            boolean selected = p.id().equals(selectedPlanId);
            stack.add(planHeader(p, selected));
            if (selected) {
                stack.add(planBody(p));
            }
            stack.add(Box.createVerticalStrut(4));
        }
        stack.add(Box.createVerticalGlue());
        drawn = true;
        stack.revalidate();
        stack.repaint();
    }

    /** Keep the open plan across a reload; fall back to the active one, then the first one. */
    private String selectionFor(List<NaruPlanSnapshot> plans) {
        if (selectedPlanId != null) {
            for (NaruPlanSnapshot p : plans) {
                if (p.id().equals(selectedPlanId)) {
                    return selectedPlanId;
                }
            }
        }
        for (NaruPlanSnapshot p : plans) {
            if (p.active()) {
                return p.id();
            }
        }
        return plans.isEmpty() ? null : plans.get(0).id();
    }

    private static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        UiFonts.apply(label, UiFonts.CAPTION, Font.BOLD);
        label.setForeground(UiPalette.muted());
        label.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    /** One registered plan: its goal, state and the controls that act on the plan itself. */
    private JComponent planHeader(NaruPlanSnapshot p, boolean selected) {
        // An activated plan is only actually being worked through in an executing mode: everywhere
        // else "active" means "chosen", not "running", and the board says so rather than showing a
        // green dot for a plan nothing is dispatching.
        boolean live = p.active() && executing;
        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        // selecting a plan is a view change, not a write, so it happens immediately; the tint is
        // the only thing that says which body below belongs to which row
        if (selected) {
            header.setOpaque(true);
            // An opaque component has to paint an opaque colour. A translucent background is
            // composited over whatever pixels are already in the window, so repainting a child —
            // hovering one of the row's buttons — darkens that child's slice of the wash while the
            // rest of the row keeps the old one. Resolve the wash against the surface beneath it.
            header.setBackground(UiPalette.mix(header.getBackground(), UiPalette.accent(),
                    (UiPalette.dark() ? 48 : 22) / 255f));
            header.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 2, 0, 0, UiPalette.accent()),
                    BorderFactory.createEmptyBorder(2, 2, 2, 2)));
        } else {
            header.setOpaque(false);
            header.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 2));
        }

        JPanel mark = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        mark.setOpaque(false);
        JLabel caret = new JLabel(selected ? "▾" : "▸");
        UiFonts.apply(caret, UiFonts.CAPTION, Font.PLAIN);
        caret.setForeground(UiPalette.muted());
        mark.add(caret);
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
        goal.setToolTipText(p.goal());
        shrinkable(goal);
        if (p.active() && !live) {
            goal.setForeground(UiPalette.muted());
        }
        text.add(goal);
        String tallyText = p.progressLabel() + " · " + p.state().name().toLowerCase();
        if (p.active()) {
            // the row is narrow and four controls live on its right, so the sentence has to be short;
            // the whole reason a plan is dormant belongs to the tooltip and not to the line
            tallyText += live ? " · active" : " · active, dormant in this mode";
        }
        JLabel tally = new JLabel(tallyText);
        UiFonts.apply(tally, UiFonts.CAPTION, Font.PLAIN);
        tally.setForeground(live ? UiPalette.accent() : UiPalette.muted());
        tally.setAlignmentX(Component.LEFT_ALIGNMENT);
        tally.setToolTipText(tallyText);
        shrinkable(tally);
        text.add(tally);
        header.add(text, BorderLayout.CENTER);
        header.setToolTipText("Plan " + p.id() + " · " + tallyText
                + (selected ? "" : " · click to open this plan"));
        header.putClientProperty("planId", p.id());
        header.putClientProperty("planSelected", selected);

        // Only the open plan is editable: a click on a closed row selects it, so the same gesture
        // cannot sometimes edit and sometimes select.
        if (selected) {
            clickToEdit(goal, "click to restate what this plan is for", () -> planEditor(p),
                    editor -> openInCenter(header, text, editor));
        } else {
            clickToSelect(goal, p.id());
        }
        clickToSelect(tally, p.id());
        clickToSelect(header, p.id());
        clickToSelect(mark, p.id());
        clickToSelect(caret, p.id());
        clickToSelect(dot, p.id());

        header.add(planActions(p, live, header, text), BorderLayout.EAST);
        return header;
    }

    /**
     * The field that restates a plan: its goal on one line and its description as Markdown. Both
     * fields commit together, so a reader who changed only the prose cannot leave the plan holding a
     * goal they meant to replace.
     */
    private JComponent planEditor(NaruPlanSnapshot p) {
        return form(p.goal(), p.description(), false, "what is this plan for?", PROSE_HEIGHT,
                (head, body) -> !flatten(head).isEmpty(),
                (head, body) -> write(s -> {
                    String goal = flatten(head);
                    if (!goal.equals(p.goal())) {
                        s.setGoal(p.id(), goal);
                    }
                    if (!nullToEmpty(body).equals(p.description())) {
                        s.setDescription(p.id(), body);
                    }
                    return null;
                }, null));
    }

    /** The items of the open plan, its description, plus the rows that start a new one of each. */
    private JComponent planBody(NaruPlanSnapshot p) {
        JPanel items = new JPanel();
        items.setOpaque(false);
        items.setLayout(new BoxLayout(items, BoxLayout.Y_AXIS));
        items.setBorder(BorderFactory.createEmptyBorder(2, 22, 10, 8));
        items.setAlignmentX(Component.LEFT_ALIGNMENT);
        // the header's "＋" finds this panel by the plan it belongs to; the board is rebuilt whole
        items.putClientProperty("planItems", p.id());
        items.add(descriptionBlock(p));
        List<NaruPlanItemSnapshot> declared = p.items();
        for (int index = 0; index < declared.size(); index++) {
            items.add(itemRow(declared.get(index), p, index));
        }
        if (declared.isEmpty()) {
            JLabel empty = new JLabel("No items yet.");
            UiFonts.apply(empty, UiFonts.CAPTION, Font.PLAIN);
            empty.setForeground(UiPalette.muted());
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            items.add(empty);
        }
        // the hint is a prompt, not a footer of the plan: it is only worth a row where the reader
        // is already working, and this body only exists for the plan they opened
        items.add(addItemHint(items, p.id()));
        return items;
    }

    /**
     * The plan's own prose, rendered, with the row that writes it when there is none yet. A plan is
     * the one thing in the rail a reader explains to a model, so the text it carries has to survive
     * being read — a single clipped label would make the description a thing only the model sees.
     */
    private JComponent descriptionBlock(NaruPlanSnapshot p) {
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        JComponent shown;
        String hint;
        if (isNotBlank(p.description())) {
            shown = markdown(p.description(), UiFonts.SECONDARY);
            hint = "click to rewrite what this plan is about";
        } else {
            JLabel prompt = new JLabel("＋ add a description — why this plan exists, in Markdown");
            UiFonts.apply(prompt, UiFonts.CAPTION, Font.PLAIN);
            prompt.setForeground(UiPalette.muted());
            prompt.setAlignmentX(Component.LEFT_ALIGNMENT);
            shown = prompt;
            hint = "click to write what this plan is about";
        }
        box.add(shown);
        // the same two-field form the header's ✎ opens: a prose editor that shows the goal but wrote
        // only the description would silently drop a sentence the reader typed into the top field
        clickToEdit(shown, hint, () -> planEditor(p), editor -> open(editor, box, shown));
        return box;
    }

    /** A read-only, wrapping Markdown body at the width the rail gives it. */
    private static JEditorPane markdown(String text, float ratio) {
        JEditorPane body = HtmlPanes.create(MarkdownHtml.toHtml(text), ratio, 2);
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        // BoxLayout clamps a child to its maximum size, and a measured body's preferred width is the
        // width it was last laid out at; without this the pane cannot take the column's width.
        body.setMinimumSize(new Dimension(0, 20));
        body.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return body;
    }

    private JComponent planActions(NaruPlanSnapshot p, boolean live, JPanel header, JPanel text) {
        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        east.setOpaque(false);
        if (p.active()) {
            east.add(action("⏸", live ? "Stop dispatching this plan" : "Deactivate this plan",
                    () -> write(s -> {
                        s.deactivate();
                        return null;
                    }, null)));
        } else {
            east.add(action("▶", "Activate this plan; its ready items start dispatching",
                    () -> write(s -> s.activate(p.id()), null)));
        }
        east.add(action("✎", "Rename this plan or rewrite its description",
                () -> openInCenter(header, text, planEditor(p))));
        east.add(action("＋", "Add an item to this plan",
                () -> addItemFor(p.id())));
        // A plan is the one thing here that cannot be rebuilt from the transcript, so the tip says
        // so; the click still goes through the extension rather than a second write path.
        east.add(action("🗑", "Delete this plan and every item in it; this cannot be undone",
                () -> deletePlan(p.id())));
        return east;
    }

    /** A quiet row that becomes the field, so "add an item" is not a dialog nobody asked for. */
    private JComponent addItemHint(JPanel items, String planId) {
        JLabel hint = new JLabel("＋ add an item");
        UiFonts.apply(hint, UiFonts.CAPTION, Font.PLAIN);
        hint.setForeground(UiPalette.muted());
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        hint.setToolTipText("One sentence for what has to be done; Markdown in it is kept");
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
     * fired and the panel has to be found; the plan is always open by the time the field opens.
     */
    private void appendItemEditor(JPanel items, String planId) {
        JPanel target = items == null ? itemsPanelFor(planId) : items;
        if (target == null) {
            return;
        }
        JPanel row = form("", "", true, "key (optional)", ITEM_HEIGHT,
                (head, body) -> isNotBlank(body) && (head.isBlank() || KEY.matcher(head).matches()),
                (head, body) -> write(s -> {
                    NaruPlanSnapshot after = s.addItem(planId, body);
                    String key = flatten(head);
                    if (!key.isEmpty() && !after.items().isEmpty()) {
                        // addItem appends, so the item just made is the last one
                        s.renameItem(planId, after.items().get(after.items().size() - 1).id(), key);
                    }
                    return null;
                }, null));
        // the hint is the last row, and a new item belongs above it rather than below it — the
        // hint is a prompt, not a footer of the plan
        int at = target.getComponentCount();
        if (at > 0 && target.getComponent(at - 1) instanceof JComponent prev
                && Boolean.TRUE.equals(prev.getClientProperty("planAddHint"))) {
            at = at - 1;
        }
        target.add(row, at);
        openAndFocus(row);
    }

    /**
     * The items panel of a plan by id, for when the "＋" control in the header fires rather than
     * the hint row inside the panel. Only the open plan has a body, so the header first opens it.
     */
    private JPanel itemsPanelFor(String planId) {
        for (Component c : stack.getComponents()) {
            if (c instanceof JPanel panel && planId.equals(panel.getClientProperty("planItems"))) {
                return panel;
            }
        }
        return null;
    }

    /** Open one plan's row and start a new item there, selecting the plan first when it is closed. */
    private void addItemFor(String planId) {
        if (openEditor != null) {
            return;
        }
        if (!planId.equals(selectedPlanId)) {
            selectedPlanId = planId;
            render(last);
        }
        appendItemEditor(null, planId);
    }

    /** Delete one plan; it is the only control here that discards the graph rather than a line of it. */
    private void deletePlan(String planId) {
        if (planId.equals(selectedPlanId)) {
            selectedPlanId = null;
        }
        write(s -> {
            s.deletePlan(planId);
            return null;
        }, null);
    }

    private static boolean isNotBlank(String text) {
        return text != null && !text.isBlank();
    }

    /**
     * One item: its handle and state on the left, its text — first line as a headline, the rest as
     * Markdown — in the middle, and the controls that move, rewrite or drop it on the right.
     */
    private JComponent itemRow(NaruPlanItemSnapshot i, NaruPlanSnapshot p, int index) {
        String planId = p.id();
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
        JLabel line = new JLabel(firstLine(i.description()));
        UiFonts.apply(line, UiFonts.BODY, Font.PLAIN);
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        line.setToolTipText(i.description());
        shrinkable(line);
        text.add(line);
        String body = restOf(i.description());
        if (isNotBlank(body)) {
            // the whole text of an item is its contract with the task that runs it, so it is shown,
            // not clipped into a tooltip
            text.add(markdown(body, UiFonts.SECONDARY));
        }
        String aside = aside(i);
        if (!aside.isEmpty()) {
            JLabel note = new JLabel(aside);
            UiFonts.apply(note, UiFonts.CAPTION, Font.PLAIN);
            note.setForeground(UiPalette.muted());
            note.setAlignmentX(Component.LEFT_ALIGNMENT);
            note.setToolTipText(aside);
            shrinkable(note);
            text.add(note);
        }
        row.add(text, BorderLayout.CENTER);
        row.add(itemActions(i, planId, row, text, line, index, p.items().size()), BorderLayout.EAST);
        row.setToolTipText(i.state().name().toLowerCase() + " · " + i.description()
                + (aside.isEmpty() ? "" : " · " + aside));
        return row;
    }

    private JComponent itemActions(NaruPlanItemSnapshot i, String planId, JPanel row, JPanel text,
                                   JLabel line, int index, int count) {
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
        // a control that cannot do anything at this position is not drawn at all
        if (index > 0) {
            east.add(action("↑", "Move this item earlier; a ready item that comes first is offered first",
                    () -> write(s -> s.moveItem(planId, i.id(), index - 1), null)));
        }
        if (index < count - 1) {
            east.add(action("↓", "Move this item later",
                    () -> write(s -> s.moveItem(planId, i.id(), index + 1), null)));
        }
        Supplier<JComponent> build = () -> form(i.key(), i.description(), true, "key", ITEM_HEIGHT,
                (head, body) -> isNotBlank(body) && (head.isBlank() || KEY.matcher(head).matches()),
                (head, body) -> write(s -> {
                    String key = flatten(head);
                    if (!key.isEmpty() && !key.equals(i.key())) {
                        s.renameItem(planId, i.id(), key);
                    }
                    if (!nullToEmpty(body).equals(i.description())) {
                        s.editItem(planId, i.id(), body);
                    }
                    return null;
                }, null));
        east.add(action("✎", "Rewrite this item, or rename its key",
                () -> openInCenter(row, text, build.get())));
        clickToEdit(line, "click to rewrite this item", build,
                editor -> openInCenter(row, text, editor));
        east.add(action("×", "Remove this item from the plan",
                () -> write(s -> s.removeItem(planId, i.id()), null)));
        return east;
    }

    /**
     * The two-field row every edit uses: a one-line head — the plan's goal or an item's key — over a
     * Markdown body. The head is flattened because both are single-line facts the plan refers to by
     * name; the body is kept verbatim because it is prose, and a paragraph flattened into a line is
     * how a checklist loses its bullets.
     */
    private JPanel form(String head, String body, boolean monospaceHead, String headPlaceholder,
                        int bodyHeight, BiPredicate<String, String> accept,
                        BiConsumer<String, String> commit) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

        JTextField headField = new JTextField(head);
        UiFonts.apply(headField, UiFonts.BODY, monospaceHead ? Font.PLAIN : Font.BOLD);
        headField.putClientProperty("JTextField.placeholderText", headPlaceholder);
        // an RSyntaxTextArea is a JTextArea, so every call site below keeps its meaning; the swap
        // buys Markdown colouring and undo for a description that is prose, not a sentence
        RSyntaxTextArea area = TextAreas.plain(8, 30);
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
        UiFonts.apply(area, UiFonts.BODY, Font.PLAIN);
        MarkdownSyntax.install(area);
        area.setText(nullToEmpty(body));
        area.setCaretPosition(0);
        JScrollPane bodyScroll = new JScrollPane(area,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        bodyScroll.setBorder(BorderFactory.createLineBorder(UiPalette.border()));
        bodyScroll.setAlignmentX(Component.LEFT_ALIGNMENT);

        Runnable finish = () -> {
            String h = headField.getText();
            String b = area.getText();
            closeEditor();
            if (accept.test(h, b)) {
                commit.accept(h, b);
            }
        };
        Runnable cancel = this::closeEditor;
        // Enter writes, because a plan edit is one sentence most of the time. It only steps into the
        // prose when there is already prose to reach for, so the common case is one keystroke.
        headField.addActionListener(e -> {
            if (area.getText().isBlank()) {
                finish.run();
            } else {
                area.requestFocusInWindow();
                area.setCaretPosition(0);
            }
        });
        headField.addKeyListener(shortcut(cancel, finish, false));
        area.addKeyListener(shortcut(cancel, finish, true));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        buttons.setOpaque(false);
        buttons.add(action("✓", "Save this change", finish));
        buttons.add(action("×", "Cancel — nothing is sent", cancel));
        JLabel keys = new JLabel("Enter saves · Ctrl+Enter saves a paragraph · Esc cancels");
        UiFonts.apply(keys, UiFonts.CAPTION, Font.PLAIN);
        keys.setForeground(UiPalette.muted());
        // the buttons keep their corner and the hint gives up its tail: a reader who loses the save
        // control has lost the edit, and one who loses the last word of a hint has lost nothing
        JPanel actions = new JPanel(new BorderLayout(4, 0));
        actions.setOpaque(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.add(keys, BorderLayout.CENTER);
        actions.add(buttons, BorderLayout.EAST);

        stretch(headField, headField.getPreferredSize().height);
        stretch(bodyScroll, bodyHeight);
        stretch(actions, actions.getPreferredSize().height);
        row.add(headField);
        row.add(Box.createVerticalStrut(3));
        row.add(bodyScroll);
        row.add(Box.createVerticalStrut(2));
        row.add(actions);
        // a vertical BoxLayout already lets its panel grow to the widest sibling, but stating it here
        // keeps that true if this row's layout or children ever change
        stretch(row, row.getPreferredSize().height);
        return row;
    }

    /**
     * Escape always abandons the draft. Enter saves unless the reader is inside a body that has more
     * paragraphs to write — {@code inBody} — where a bare Enter is a newline and Ctrl+Enter is the
     * save. A blank body is no draft, so Enter there is the save rather than a line nobody asked for.
     */
    private KeyAdapter shortcut(Runnable cancel, Runnable finish, boolean inBody) {
        return new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() != KeyEvent.VK_ENTER && e.getKeyCode() != KeyEvent.VK_ESCAPE) {
                    return;
                }
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    cancel.run();
                    return;
                }
                boolean control = e.isControlDown();
                if (!inBody || control || ((JTextArea) e.getSource()).getText().isBlank()) {
                    e.consume();
                    finish.run();
                }
            }
        };
    }

    /** Put an editor where a component sat, so the row the reader clicked is what changes. */
    private void open(JComponent editor, JComponent parent, JComponent replace) {
        if (openEditor != null) {
            return;
        }
        int index = indexOf(parent, replace);
        if (index < 0) {
            return;
        }
        parent.remove(index);
        parent.add(editor, index);
        parent.revalidate();
        parent.repaint();
        openAndFocus(editor);
    }

    /**
     * Put an editor in the middle of a row, taking everything that was there. A plan's goal and
     * tally, or an item's headline and body, are one column of text; replacing only the line the
     * reader clicked would leave the rest of that column stranded under the field.
     */
    private void openInCenter(JPanel host, JComponent center, JComponent editor) {
        if (openEditor != null) {
            return;
        }
        host.remove(center);
        // The row's controls use the EAST column, and the editor used to take only CENTER: a page
        // of prose was held to the width left over by a column of glyphs whose own rows ended above
        // it, and the rest of that column was empty space. The editor owns the row while it is
        // open — save and cancel are part of the form — so the action column goes away and the
        // description gets the whole width back.
        if (host.getLayout() instanceof BorderLayout layout) {
            Component actions = layout.getLayoutComponent(BorderLayout.EAST);
            if (actions != null) {
                host.remove(actions);
            }
        }
        host.add(editor, BorderLayout.CENTER);
        host.revalidate();
        host.repaint();
        openAndFocus(editor);
    }

    /**
     * A field with commit and cancel for the plan being made. Creating a plan is the one write that
     * has no row to edit yet, so it opens at the top of the board instead of replacing anything.
     */
    private void openCreateEditor() {
        if (openEditor != null) {
            return;
        }
        JPanel row = form("", "", false, "what is this plan for?", PROSE_HEIGHT,
                (head, body) -> !flatten(head).isEmpty(),
                (head, body) -> write(s -> s.createPlan(flatten(head), body), created -> {
                    // open the plan that was just made, so creating one never looks like nothing
                    // happened because an older active plan stayed at the top of the list
                    if (created != null) {
                        selectedPlanId = created.id();
                    }
                }));
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

    private void clickToEdit(JComponent shown, String hint, Supplier<JComponent> build,
                             Consumer<JComponent> place) {
        shown.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        String existing = shown.getToolTipText();
        shown.setToolTipText(existing == null ? hint : existing + " · " + hint);
        shown.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (openEditor == null) {
                    place.accept(build.get());
                }
            }
        });
    }

    /**
     * A click that only opens a plan. It never writes and never opens a field: a closed plan is a
     * row to select, not a row to edit by accident.
     */
    private void clickToSelect(JComponent component, String planId) {
        component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                selectPlan(planId);
            }
        });
    }

    /** Open one plan's body and close the previous one; the reader's draft is never repainted over. */
    private void selectPlan(String planId) {
        if (openEditor != null || planId == null || planId.equals(selectedPlanId)) {
            return;
        }
        selectedPlanId = planId;
        render(last);
    }

    private void openAndFocus(JComponent row) {
        openEditor = row;
        row.revalidate();
        SwingUtilities.invokeLater(() -> {
            JTextComponent field = firstField(row);
            if (field != null) {
                field.requestFocusInWindow();
                if (field instanceof JTextField tf) {
                    tf.selectAll();
                } else {
                    field.setCaretPosition(0);
                }
            }
        });
    }

    /**
     * The deepest editable field an editor holds, in reading order. A goal field is found before the
     * prose under it because that is where the reader's sentence starts; a bare body still gets focus
     * when the head was already right.
     */
    private static JTextComponent firstField(JComponent root) {
        JTextComponent head = null;
        JTextComponent body = null;
        for (Component c : root.getComponents()) {
            if (c instanceof JTextField f && head == null) {
                head = f;
            } else if (c instanceof JTextArea a && body == null) {
                body = a;
            } else if (c instanceof JComponent jc) {
                JTextComponent found = firstField(jc);
                if (found instanceof JTextField f && head == null) {
                    head = f;
                } else if (found != null && body == null) {
                    body = found;
                }
            }
        }
        return head != null ? head : body;
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

    /**
     * Let a component take the column's width, and no more. BoxLayout clamps a child between its
     * minimum and maximum, and both default to the component's preferred width — so a field sized
     * for its own text would hold the editor wider than the rail is.
     */
    private static void stretch(JComponent c, int height) {
        c.setMinimumSize(new Dimension(0, height));
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    }

    /**
     * Let a line of text give up its width. A row's controls live to the right of its text, and a
     * label's minimum size is its full preferred width by default, so a long goal held the centre
     * open and pushed delete off the panel — a control the reader cannot reach is worse than a
     * sentence they cannot fully read, and Swing ends the line with an ellipsis the moment it is
     * allowed to shrink.
     */
    private static void shrinkable(JLabel label) {
        label.setMinimumSize(new Dimension(0, label.getPreferredSize().height));
    }

    private JButton action(String glyph, String tip, Runnable onClick) {
        JButton b = GlyphButton.of(glyph, tip);
        b.addActionListener(e -> onClick.run());
        return b;
    }

    /**
     * Run one write against the extension's service. Production runs it off the EDT, because the
     * graph is live state a task may be touching; a test can hand in a direct executor. Whatever the
     * result, the board reloads once the call returns, and a refusal is shown as the extension's own
     * sentence rather than a widget's guess at what went wrong.
     */
    private <T> void write(Function<NaruPlanService, T> action, Consumer<T> onSuccess) {
        NaruPlanService service = plans();
        if (service == null) {
            DesktopDialog.showMessage(SwingUtilities.getWindowAncestor(this), "Plan",
                    "The plan extension is not installed in this session.");
            return;
        }
        Runnable task = () -> {
            try {
                T result = action.apply(service);
                SwingUtilities.invokeLater(() -> {
                    if (onSuccess != null) {
                        onSuccess.accept(result);
                    }
                    refresh.restart();
                });
            } catch (RuntimeException e) {
                SwingUtilities.invokeLater(() -> {
                    String message = e.getMessage();
                    if (message == null || message.isBlank()) {
                        message = e.getClass().getSimpleName();
                    }
                    DesktopDialog.showMessage(SwingUtilities.getWindowAncestor(this), "Plan", message);
                });
            }
        };
        if (writes == null) {
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() {
                    task.run();
                    return null;
                }
            }.execute();
        } else {
            writes.execute(task);
        }
    }

    /**
     * Newlines and tabs to single spaces, trimmed. A goal and a key are one line long and are used to
     * name the plan or item elsewhere, so a pasted paragraph would otherwise arrive as a handle with
     * the line breaks nobody meant to keep. Descriptions are not flattened — see {@link #form}.
     */
    private static String flatten(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    /** The headline of an item: its first line, so a multi-line body still scans as a list. */
    private static String firstLine(String text) {
        String t = nullToEmpty(text);
        int nl = t.indexOf('\n');
        return (nl < 0 ? t : t.substring(0, nl)).trim();
    }

    /** Everything after the headline, which is the part that needs Markdown to be readable. */
    private static String restOf(String text) {
        String t = nullToEmpty(text);
        int nl = t.indexOf('\n');
        return nl < 0 ? "" : t.substring(nl + 1).strip();
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
     * {@link #openCreateEditor()} is reachable from the toolbar, so the words point at it.
     */
    private void showEmpty() {
        selectedPlanId = null;
        show("No plan yet — use the ＋ above, or ask NARU to plan the work.");
    }

    private void show(String message) {
        stack.removeAll();
        JLabel label = new JLabel(message);
        UiFonts.apply(label, UiFonts.BODY, Font.PLAIN);
        label.setForeground(UiPalette.muted());
        // every other child of the column is left-aligned, and BoxLayout glues a mixed row of
        // alignments onto one axis: a centred placeholder would shove an open editor off to the right
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        stack.add(label);
        stack.add(Box.createVerticalGlue());
        drawn = false;
        stack.revalidate();
        stack.repaint();
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
