package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.desktop.diff.GitChangeSummary;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.model.TurnActivity;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * The live line above the composer while a turn is running: a small spinner, a verb that says what
 * the agent is doing, and a summary of what the turn has changed so far.
 * <p>
 * It reads {@link NaruSessionModel} rather than polling the engine: busy state is the same
 * observation the composer's Stop button uses, and the counters come from {@link TurnActivity},
 * which the session feeds the same transcript batches. The only clock here is a 120 ms animation
 * timer, and it runs only while the bar is visible.
 * <p>
 * The verb is specific when a tool names an action ("Reading notes.txt", "Running bash"); when no
 * tool is active it rotates through short synonyms for the current kind, so a long think does not
 * sit on one frozen word.
 * <p>
 * The text on the right is the working tree's file/line-count summary, read with git's
 * {@code --numstat} while work is running. Hovering it lists each file with its own +/− counts.
 * The internal {@link TurnActivity} counters remain the fallback while git has nothing to report
 * (or the project is not a repository), so the strip can still say what the turn has done.
 */
final class TurnActivityBar extends JPanel
        implements NaruSessionModel.Listener, net.thevpc.naru.desktop.model.TranscriptModel.Listener {

    private static final int TICK_MILLIS = 120;
    private static final long VERB_ROTATE_MILLIS = 2400;
    private static final long CHANGE_POLL_MILLIS = 3_000;
    private static final int CHANGE_DEBOUNCE_MILLIS = 700;

    private final NaruSessionModel session;
    private final ActivitySpinner spinner = new ActivitySpinner();
    private final JLabel primary = new JLabel();
    private final JLabel summary = new JLabel();
    private final Timer timer;
    private final Timer changeDebounce;
    private GitChangeSummary.Snapshot changes = GitChangeSummary.Snapshot.empty();
    private SwingWorker<GitChangeSummary.Snapshot, Void> changeWorker;
    private boolean changeReadRunning;
    private boolean changeReadAgain;
    private long lastChangeReadMillis;
    private int lastToolCalls = -1;
    private int ticks;

    TurnActivityBar(NaruSessionModel session) {
        super(new BorderLayout(8, 0));
        this.session = session;
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UiPalette.border()),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        primary.setForeground(UiPalette.readable(UiPalette.accent()));
        UiFonts.apply(primary, UiFonts.SECONDARY, Font.PLAIN);
        // The summary is a readout, not a footnote: it sits on the same line as the verb and must
        // stay findable without competing with it for the first glance.
        summary.setForeground(UiPalette.readable(UiPalette.muted()));
        UiFonts.apply(summary, UiFonts.SECONDARY, Font.PLAIN);

        JPanel line = new JPanel(new BorderLayout(8, 0));
        line.setOpaque(false);
        line.add(primary, BorderLayout.CENTER);
        line.add(summary, BorderLayout.EAST);
        add(spinner, BorderLayout.WEST);
        add(line, BorderLayout.CENTER);

        timer = new Timer(TICK_MILLIS, e -> tick());
        timer.setRepeats(true);
        changeDebounce = new Timer(CHANGE_DEBOUNCE_MILLIS, e -> requestChangeSummary());
        changeDebounce.setRepeats(false);
        session.addListener(this);
        session.transcript().addListener(this);
        setVisible(false);
        refresh();
    }

    @Override
    public void sessionModelChanged() {
        refresh();
    }

    @Override
    public void transcriptChanged(boolean structural) {
        // The activity model already saw this batch; the timer will read it on its next tick. The
        // listener is only here to reveal the strip the moment a tool starts (a structural batch
        // can arrive while the task poll still says the worker is between ticks).
        if (!isVisible() && session.isActivityVisible()) {
            refresh();
        }
    }

    private void refresh() {
        boolean live = session.isActivityVisible();
        boolean wasLive = isVisible();
        if (live != wasLive) {
            setVisible(live);
            revalidate();
            if (live) {
                // Also read once when the strip appears for a restored session that is already
                // parked on input: the reader still needs to see what the worktree contains.
                lastChangeReadMillis = 0;
                requestChangeSummary();
            }
        }
        if (!live) {
            timer.stop();
            changeDebounce.stop();
            return;
        }
        if (!timer.isRunning()) {
            timer.start();
        }
        updateText();
    }

    private void tick() {
        ticks++;
        spinner.advance();
        // Visibility can change on the 2 Hz task poll alone (a task parks, a tool settles). Check
        // it every tick: the strip is the only place that says the turn ended quietly.
        if (!session.isActivityVisible()) {
            setVisible(false);
            timer.stop();
            changeDebounce.stop();
            revalidate();
            return;
        }
        updateText();
    }

    private void updateText() {
        if (!isVisible()) {
            return;
        }
        TurnActivity.Snapshot activity = session.turnActivity().snapshot();
        // A pending question wins over the task's RUNNING/streaming state. The composer shows Send
        // in exactly this state, so an animated "working" verb beside it would contradict the input
        // control the reader is looking at.
        boolean waitingInput = session.isWaitingForInput();
        boolean waitingEvent = !waitingInput && session.isWaitingOnEvent();
        boolean waiting = waitingInput || waitingEvent;
        boolean working = !waiting && session.isWorking();
        boolean starting = !working && !waiting && session.isStarting();
        spinner.setSpinning(!waiting);

        String verb = verb(activity, waitingInput, waitingEvent, starting);
        String dots = waiting ? "" : ".".repeat(1 + Math.floorMod(ticks / 3, 3));
        String line = verb + dots;
        if (!line.equals(primary.getText())) {
            primary.setText(line);
        }

        updateSummary(activity);
        scheduleChangeRefresh(activity, working || starting);
        setToolTipText(tooltip(activity));
    }

    /** Refresh the git summary on a tool-call settling edge and, more slowly, while work runs. */
    private void scheduleChangeRefresh(TurnActivity.Snapshot activity, boolean busy) {
        if (!busy) {
            lastToolCalls = activity.toolCalls();
            return;
        }
        if (lastToolCalls < 0) {
            lastToolCalls = activity.toolCalls();
        } else if (activity.toolCalls() != lastToolCalls) {
            lastToolCalls = activity.toolCalls();
            // A tool call event arrives before the write has necessarily hit the tree; wait a
            // moment so the summary reports the settled file rather than the previous state.
            changeDebounce.restart();
        }
        long now = System.currentTimeMillis();
        if (lastChangeReadMillis == 0 || now - lastChangeReadMillis >= CHANGE_POLL_MILLIS) {
            requestChangeSummary();
        }
    }

    private void requestChangeSummary() {
        if (!isVisible()) {
            return;
        }
        if (changeReadRunning) {
            changeReadAgain = true;
            return;
        }
        Path project = projectPath();
        if (project == null) {
            return;
        }
        lastChangeReadMillis = System.currentTimeMillis();
        changeReadRunning = true;
        GitChangeSummary.Snapshot previous = changes;
        changeWorker = new SwingWorker<>() {
            @Override
            protected GitChangeSummary.Snapshot doInBackground() {
                return GitChangeSummary.read(project);
            }

            @Override
            protected void done() {
                changeReadRunning = false;
                if (isCancelled()) {
                    return;
                }
                try {
                    changes = get();
                } catch (Exception e) {
                    // A read that failed must not erase the last true counts. The next poll will
                    // try again.
                    changes = previous;
                }
                if (changeReadAgain) {
                    changeReadAgain = false;
                    if (isVisible()) {
                        requestChangeSummary();
                    }
                }
                if (isVisible()) {
                    updateText();
                }
            }
        };
        changeWorker.execute();
    }

    private Path projectPath() {
        try {
            var dir = session.projectDir();
            return dir == null ? null : Paths.get(dir.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String verb(TurnActivity.Snapshot activity, boolean waitingInput,
                               boolean waitingEvent, boolean starting) {
        if (waitingInput) {
            return "Waiting for your reply";
        }
        if (waitingEvent) {
            return "Waiting for an event";
        }
        if (starting) {
            return pick(activity.elapsedMillis(), "Getting ready", "Starting up", "Warming up");
        }
        return switch (activity.kind()) {
            case THINKING -> pick(activity.elapsedMillis(), "Thinking", "Pondering", "Reasoning",
                    "Considering", "Working through it");
            case WRITING -> pick(activity.elapsedMillis(), "Writing", "Composing", "Drafting",
                    "Answering");
            case TOOL -> toolVerb(activity.toolName(), activity.lastFile());
            case WORKING -> pick(activity.elapsedMillis(), "Working", "On it", "Running",
                    "Processing");
            case STARTING -> pick(activity.elapsedMillis(), "Getting ready", "Starting up",
                    "Warming up");
            default -> pick(activity.elapsedMillis(), "Working", "On it", "Processing");
        };
    }

    /**
     * A specific verb for the tool that is running. The file basename, when the arguments named
     * one, is what makes the line useful: "Editing TranscriptPane.java" beats "Running tool".
     */
    private static String toolVerb(String rawTool, String lastFile) {
        String tool = rawTool == null ? "" : rawTool.toLowerCase(Locale.ROOT);
        String action;
        if (any(tool, "read", "cat", "view", "open", "show", "head", "tail")) {
            action = "Reading";
        } else if (any(tool, "write", "edit", "patch", "apply", "create", "delete", "remove",
                "rename", "move", "mkdir", "touch")) {
            action = "Editing";
        } else if (any(tool, "grep", "search", "find", "glob", "list", "ls", "scan", "query")) {
            action = "Searching";
        } else if (any(tool, "bash", "shell", "exec", "run", "command", "terminal")) {
            action = "Running";
        } else if (any(tool, "fetch", "http", "web", "curl", "browse", "download")) {
            action = "Fetching";
        } else if (any(tool, "test", "verify")) {
            action = "Testing";
        } else if (any(tool, "build", "compile", "mvn", "gradle")) {
            action = "Building";
        } else if (tool.contains("git")) {
            action = "Checking git";
        } else if (any(tool, "plan", "todo", "task")) {
            action = "Planning";
        } else {
            action = "Running";
        }
        String file = baseName(lastFile);
        if (!file.isEmpty()) {
            return action + " " + file;
        }
        if (!tool.isBlank()) {
            return action + " " + displayName(tool);
        }
        return action;
    }

    private static boolean any(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String baseName(String path) {
        if (path == null) {
            return "";
        }
        String clean = path.strip();
        // A directory argument often ends with a separator; without this, "Searching src/main/"
        // would render as "Searching" because the basename is empty.
        while (clean.length() > 1 && (clean.endsWith("/") || clean.endsWith("\\"))) {
            clean = clean.substring(0, clean.length() - 1);
        }
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        String name = slash >= 0 ? clean.substring(slash + 1) : clean;
        return name.length() > 34 ? name.substring(0, 33) + "…" : name;
    }

    private static String displayName(String tool) {
        String name = tool.replace('_', ' ').replace('-', ' ').strip();
        return name.length() > 24 ? name.substring(0, 23) + "…" : name;
    }

    private static String pick(long elapsedMillis, String... options) {
        return options[(int) Math.floorMod(elapsedMillis / VERB_ROTATE_MILLIS, options.length)];
    }

    private void updateSummary(TurnActivity.Snapshot activity) {
        String text = summaryText(changes, activity);
        if (!text.equals(summary.getText())) {
            summary.setText(text);
            summary.setVisible(!text.isEmpty());
        }
        String tip = summaryTooltip(changes, activity);
        if (!tip.equals(summary.getToolTipText())) {
            summary.setToolTipText(tip.isEmpty() ? null : tip);
        }
    }

    /**
     * The right-hand readout: the working tree's file and line counts when git has some, otherwise
     * the turn's internal counts. Package-visible so the wording can be tested without a JFrame.
     */
    static String summaryText(GitChangeSummary.Snapshot changes, TurnActivity.Snapshot activity) {
        if (changes != null && changes.hasChanges()) {
            String files = changes.fileCount() + (changes.fileCount() == 1 ? " file" : " files");
            if (changes.added() + changes.deleted() == 0) {
                return files;
            }
            return "<html>" + files
                    + " · <font color='#" + hex(UiPalette.LIVE) + "'>+" + changes.added()
                    + "</font> <font color='#" + hex(UiPalette.ATTENTION) + "'>&#8722;"
                    + changes.deleted() + "</font></html>";
        }
        return internalSummary(activity);
    }

    /**
     * The popup over the summary: one line per changed file with its own +/− counts, then the
     * turn's tool/elapsed facts. Package-visible for the same reason as {@link #summaryText}.
     */
    static String summaryTooltip(GitChangeSummary.Snapshot changes, TurnActivity.Snapshot activity) {
        if (changes == null || !changes.hasChanges()) {
            return tooltip(activity);
        }
        String muted = "#" + hex(UiPalette.muted());
        String addColor = "#" + hex(UiPalette.LIVE);
        String delColor = "#" + hex(UiPalette.ATTENTION);
        StringBuilder html = new StringBuilder("<html><b>")
                .append(changes.fileCount())
                .append(changes.fileCount() == 1 ? " file changed" : " files changed")
                .append("</b>");
        if (changes.added() + changes.deleted() > 0) {
            html.append(" · <font color='").append(addColor).append("'>+")
                    .append(changes.added()).append("</font> <font color='").append(delColor)
                    .append("'>&#8722;").append(changes.deleted()).append("</font>");
        }
        html.append("<br>");
        int listed = 0;
        List<GitChangeSummary.FileChange> files = changes.files();
        for (GitChangeSummary.FileChange file : files) {
            if (listed == 20) {
                html.append("<font color='").append(muted).append("'>… ")
                        .append(files.size() - listed).append(" more</font><br>");
                break;
            }
            html.append("<font face='monospaced'>").append(escape(file.path())).append("</font>");
            if (file.binary()) {
                html.append(" <font color='").append(muted).append("'>binary</font>");
            } else {
                html.append(" <font color='").append(addColor).append("'>+")
                        .append(file.added()).append("</font> <font color='").append(delColor)
                        .append("'>&#8722;").append(file.deleted()).append("</font>");
            }
            html.append("<br>");
            listed++;
        }
        String facts = turnFacts(activity);
        if (!facts.isEmpty()) {
            html.append("<font color='").append(muted).append("'>")
                    .append(escape(facts)).append("</font>");
        }
        return html.append("</html>").toString();
    }

    private static String internalSummary(TurnActivity.Snapshot activity) {
        StringBuilder text = new StringBuilder();
        if (activity.toolCalls() > 0) {
            text.append(activity.toolCalls())
                    .append(activity.toolCalls() == 1 ? " tool" : " tools");
        }
        if (activity.filesTouched() > 0) {
            if (text.length() > 0) text.append(" · ");
            text.append(activity.filesTouched())
                    .append(activity.filesTouched() == 1 ? " file seen" : " files seen");
        }
        if (activity.failedToolCalls() > 0) {
            if (text.length() > 0) text.append(" · ");
            text.append(activity.failedToolCalls()).append(" failed");
        }
        if (activity.elapsedMillis() >= 1000) {
            if (text.length() > 0) text.append(" · ");
            text.append(elapsed(activity.elapsedMillis()));
        }
        return text.toString();
    }

    private static String turnFacts(TurnActivity.Snapshot activity) {
        StringBuilder text = new StringBuilder();
        if (activity.toolCalls() > 0) {
            text.append(activity.toolCalls())
                    .append(activity.toolCalls() == 1 ? " tool" : " tools");
        }
        if (activity.failedToolCalls() > 0) {
            if (text.length() > 0) text.append(" · ");
            text.append(activity.failedToolCalls()).append(" failed");
        }
        if (activity.elapsedMillis() >= 1000) {
            if (text.length() > 0) text.append(" · ");
            text.append(elapsed(activity.elapsedMillis()));
        }
        return text.toString();
    }

    private static String tooltip(TurnActivity.Snapshot activity) {
        StringBuilder text = new StringBuilder("This turn: ");
        text.append(activity.toolCalls()).append(activity.toolCalls() == 1 ? " tool call" : " tool calls");
        if (activity.filesTouched() > 0) {
            text.append(", ").append(activity.filesTouched())
                    .append(activity.filesTouched() == 1 ? " file seen" : " files seen");
        }
        if (activity.failedToolCalls() > 0) {
            text.append(", ").append(activity.failedToolCalls()).append(" failed");
        }
        if (activity.elapsedMillis() >= 1000) {
            text.append(" · ").append(elapsed(activity.elapsedMillis())).append(" elapsed");
        }
        return text.toString();
    }

    private static String hex(Color color) {
        return String.format(Locale.ROOT, "%02x%02x%02x",
                color.getRed(), color.getGreen(), color.getBlue());
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String elapsed(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m " + String.format(Locale.ROOT, "%02ds", seconds % 60);
        }
        return (seconds / 3600) + "h " + String.format(Locale.ROOT, "%02dm", (seconds % 3600) / 60);
    }

    void dispose() {
        timer.stop();
        changeDebounce.stop();
        if (changeWorker != null) {
            changeWorker.cancel(true);
        }
        session.removeListener(this);
        session.transcript().removeListener(this);
    }

    /** The verb line, without animation dots, so the waiting rules can be tested. */
    String activityText() {
        return primary.getText();
    }

    boolean spinnerSpinning() {
        return spinner.isSpinning();
    }

    /** A small ring of dots with a bright head, or a static dot when the turn is waiting. */
    private static final class ActivitySpinner extends JComponent {
        private static final int DOTS = 8;
        private double phase;
        private boolean spinning;

        ActivitySpinner() {
            setOpaque(false);
            setPreferredSize(new Dimension(18, 18));
        }

        void setSpinning(boolean value) {
            if (spinning != value) {
                spinning = value;
                repaint();
            }
        }

        boolean isSpinning() {
            return spinning;
        }

        void advance() {
            if (!spinning) {
                return;
            }
            phase = (phase + 0.55) % (Math.PI * 2);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                int size = Math.min(getWidth(), getHeight());
                int cx = getWidth() / 2;
                int cy = getHeight() / 2;
                Color accent = UiPalette.readable(UiPalette.accent());
                if (!spinning) {
                    // Waiting: a still dot, not a spinner that claims work is happening.
                    int dot = Math.max(3, size / 5);
                    g.setColor(UiPalette.alpha(accent, 150));
                    g.fillOval(cx - dot / 2, cy - dot / 2, dot, dot);
                    return;
                }
                int radius = Math.max(3, size / 2 - 3);
                int dot = Math.max(2, size / 6);
                for (int i = 0; i < DOTS; i++) {
                    double angle = i * (Math.PI * 2 / DOTS);
                    double distance = Math.abs(normalize(angle - phase));
                    int alpha = 45 + (int) Math.round(210 * (1 - distance / Math.PI));
                    g.setColor(UiPalette.alpha(accent, Math.max(30, Math.min(255, alpha))));
                    int x = (int) Math.round(cx + Math.cos(angle) * radius) - dot / 2;
                    int y = (int) Math.round(cy + Math.sin(angle) * radius) - dot / 2;
                    g.fillOval(x, y, dot, dot);
                }
            } finally {
                g.dispose();
            }
        }

        /** Map to [-pi, pi] so a dot just on the other side of the head is not counted. */
        private static double normalize(double angle) {
            double wrapped = angle % (Math.PI * 2);
            if (wrapped > Math.PI) wrapped -= Math.PI * 2;
            if (wrapped < -Math.PI) wrapped += Math.PI * 2;
            return wrapped;
        }
    }
}
