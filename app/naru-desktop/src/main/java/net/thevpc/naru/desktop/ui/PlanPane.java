package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.plan.NaruPlanItemSnapshot;
import net.thevpc.naru.api.plan.NaruPlanItemState;
import net.thevpc.naru.api.plan.NaruPlanReader;
import net.thevpc.naru.api.plan.NaruPlanSnapshot;
import net.thevpc.naru.api.plan.NaruPlanState;
import net.thevpc.naru.desktop.model.NaruSessionModel;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * The plan board: every plan this session holds, with its items and their states.
 *
 * <p>It sits in the rail rather than inside Inspect because a plan is what the run is for. The
 * inspector answers "what happened"; this answers "what is supposed to happen next", and a reader who
 * has to open a tab to find the second one stops trusting it.
 *
 * <p>The desktop does not compile against the plan extension — it reads plans through
 * {@link NaruPlanReader}, so a session without that feature shows an honest empty panel instead of a
 * stack trace. Nothing here writes: a plan's shape belongs to {@code /plan} and to the model's own
 * tools, and a panel that could edit it would drift from the rules that keep the graph honest.
 */
public final class PlanPane extends JPanel implements NaruSessionModel.Listener {

    private final NaruSessionModel model;
    private final Column stack = new Column();
    /** Debounced: a streaming turn fires the model listener on every chunk, and a plan is not urgent. */
    private final Timer refresh = new Timer(250, e -> reload());
    private boolean reading;
    /** Whether the column currently shows a real board rather than a placeholder line. */
    private boolean drawn;
    private long generation;

    public PlanPane(NaruSessionModel model) {
        super(new BorderLayout());
        this.model = model;
        JScrollPane scroll = new JScrollPane(stack);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(14);
        add(scroll, BorderLayout.CENTER);
        refresh.setRepeats(false);
        show("No session is open.");
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
        if (model.session() == null) {
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
                NaruPlanReader reader = NaruPlanReader.of(model.session());
                if (reader == null) {
                    return Read.ABSENT;
                }
                try {
                    return reader.allPlans();
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
                    show("The plan extension is not installed in this session.");
                } else if (value instanceof List<?>) {
                    List<NaruPlanSnapshot> plans = cast(value);
                    if (plans.isEmpty()) {
                        show("No plan yet — ask NARU to plan the work, and it appears here.");
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

    @SuppressWarnings("unchecked")
    private static List<NaruPlanSnapshot> cast(Object plans) {
        return (List<NaruPlanSnapshot>) plans;
    }

    private void render(List<NaruPlanSnapshot> plans) {
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
        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel mark = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        mark.setOpaque(false);
        StatusDot dot = new StatusDot();
        dot.setColor(hue(p.state()));
        dot.setPulsing(p.state() == NaruPlanState.OPEN);
        mark.add(dot);
        header.add(mark, BorderLayout.WEST);
        // the tally goes on its own line rather than beside the goal: a long goal pushed it into a
        // wrapped flow row that the reader never saw, and the progress count is the one number on
        // this panel that says how much of the run is left
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel goal = new JLabel(p.goal() == null || p.goal().isBlank() ? "(no goal)" : p.goal());
        UiFonts.apply(goal, UiFonts.BODY, Font.BOLD);
        goal.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(goal);
        String tallyText = p.progressLabel() + " · " + p.state().name().toLowerCase()
                + (p.active() ? " · active" : "");
        JLabel tally = new JLabel(tallyText);
        UiFonts.apply(tally, UiFonts.CAPTION, Font.PLAIN);
        tally.setForeground(p.active() ? UiPalette.accent() : UiPalette.muted());
        tally.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(tally);
        header.add(text, BorderLayout.CENTER);
        header.setToolTipText("Plan " + p.id() + " · " + tallyText);
        stack.add(header);

        JPanel items = new JPanel();
        items.setOpaque(false);
        items.setLayout(new BoxLayout(items, BoxLayout.Y_AXIS));
        items.setBorder(BorderFactory.createEmptyBorder(2, 18, 8, 8));
        items.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (NaruPlanItemSnapshot i : p.items()) {
            items.add(itemRow(i));
        }
        stack.add(items);
        stack.add(Box.createVerticalStrut(6));
    }

    private static Component itemRow(NaruPlanItemSnapshot i) {
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
        // a clipped description is only a clipped description if the reader cannot get at the whole
        row.setToolTipText(i.state().name().toLowerCase() + " · " + i.description()
                + (aside.isEmpty() ? "" : " · " + aside));
        return row;
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
