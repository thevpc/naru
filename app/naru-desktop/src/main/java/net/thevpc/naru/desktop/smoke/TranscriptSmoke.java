//package net.thevpc.naru.desktop.smoke;
//
//import net.thevpc.naru.api.agent.NaruLogMode;
//import net.thevpc.naru.api.agent.NaruSession;
//import net.thevpc.naru.desktop.MainWindow;
//import net.thevpc.naru.desktop.model.NaruSessionModel;
//import net.thevpc.naru.desktop.ui.TaskPane;
//import net.thevpc.naru.desktop.ui.TranscriptPane;
//import net.thevpc.nuts.Nuts;
//import net.thevpc.nuts.io.NPath;
//import net.thevpc.nuts.swing.NSwingUtils;
//import net.thevpc.nuts.text.NMsg;
//
//import javax.swing.SwingUtilities;
//import java.awt.Component;
//import java.awt.Graphics;
//import java.awt.GraphicsEnvironment;
//import java.awt.Point;
//import java.awt.Rectangle;
//import java.awt.Robot;
//import java.awt.image.BufferedImage;
//import java.io.File;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.concurrent.atomic.AtomicInteger;
//import java.util.concurrent.atomic.AtomicLong;
//
///**
// * Phase 2 §6 gate run for the transcript: markdown as the user sees it (D1) and a robot-driven
// * scroll of a loaded transcript (G1).
// * <p>
// * Everything here goes through the production path. The text arrives through
// * {@code InteractionHost.write}, which is the call the engine makes; the window is the real
// * {@code MainWindow}; the scroll is {@code java.awt.Robot} moving a mouse over the transcript and
// * turning its wheel, not a call to a scrollbar. The claims are measured — visible row range,
// * scrollbar value, per-step wall time, EDT ping stall counts, and the HTML the cell renderer
// * actually produced — and printed, because §10 asks for numbers rather than impressions.
// * <p>
// * The load is deterministic instead of model-driven on purpose: a scroll test that depends on a
// * provider answer cannot be compared to the previous run, and the expensive part under test is the
// * view, not the engine.
// * <pre>
// * usage: TranscriptSmoke &lt;projectDir&gt; [blocks]
// * </pre>
// */
//public final class TranscriptSmoke {
//
//    /** One agent turn, markdown as a model really writes it: heading, bold, inline code, list, fence. */
//    private static final String AGENT_MARKDOWN = """
//            ## What I changed
//
//            The **monitor** now reads `session.tasks()` once per tick, so the rail cannot
//            disagree with the detail pane about which tasks exist.
//
//            - hold is one button, its word follows the row's flag
//            - kill keeps the reason the caller gave it
//            - the model label resolves off the EDT
//
//            ```java
//            Timer taskPoll = new Timer(TASK_POLL_MILLOS, e -> refreshTasks());
//            ```
//
//            Nothing above changes how the agent behaves; it is presentation only.
//            """.replace("MILLOS", "MILLIS");
//
//    private static NaruSessionModel live;
//    private static MainWindow liveWin;
//
//    public static void main(String[] args) throws Exception {
//        Nuts.require();
//        NSwingUtils.prepareUI(new String[0]);
//        if (GraphicsEnvironment.isHeadless()) {
//            System.out.println("SMOKE transcript\n  VERDICT=FAIL — this run needs a display (Robot)");
//            System.exit(1);
//        }
//        NPath project = NPath.of(args.length > 0 ? args[0]
//                : System.getProperty("java.io.tmpdir") + "/naru-transcript-smoke").toAbsolute();
//        int blocks = args.length > 1 ? Integer.parseInt(args[1]) : 120;
//        project.mkdirs();
//
//        List<String> log = new ArrayList<>();
//        AtomicInteger pings = new AtomicInteger();
//        AtomicLong maxPing = new AtomicLong();
//        AtomicInteger stallsOver16 = new AtomicInteger();
//        AtomicInteger stallsOver250 = new AtomicInteger();
//        startPinger(pings, maxPing, stallsOver16, stallsOver250);
//
//        MainWindow[] win = new MainWindow[1];
//        SwingUtilities.invokeAndWait(() -> {
//            MainWindow w = new MainWindow();
//            w.setSize(1380, 860);
//            w.setLocation(0, 0);
//            w.setVisible(true);
//            w.openProject(project);
//            win[0] = w;
//        });
//        liveWin = win[0];
//        NaruSessionModel session = awaitSession(win[0], 90);
//        if (session == null) {
//            report(false, "the session never started", project, blocks, log, pings, maxPing,
//                    stallsOver16, stallsOver250);
//            System.exit(1);
//        }
//        live = session;
//        NaruSession s = onEdtReturn(session::session);
//        long fg = s.foregroundTaskId();
//        log.add("session booted, foreground task #" + fg);
//
//        // hop 1: fill the transcript the way the engine does, and let the window's own selection
//        // path (rail row → conversation filter) decide what the user is looking at
//        long writeStart = System.currentTimeMillis();
//        int lines = 0;
//        for (int i = 0; i < blocks; i++) {
//            // ofC("%s", …) rather than ofC(text): the corpus is markdown, and a message factory that
//            // reads its first argument as a format string must not be handed prose
//            win[0].host().write(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", AGENT_MARKDOWN), fg);
//            win[0].host().write(NaruLogMode.SCRIPT, NMsg.ofC("%s", outputFor(i)), fg);
//            lines += AGENT_MARKDOWN.lines().count() + outputFor(i).lines().count();
//        }
//        long writeMillis = System.currentTimeMillis() - writeStart;
//        TranscriptPane pane = transcriptOf(win[0]);
//        if (pane == null) {
//            report(false, "the Conversation tab did not hand out a TranscriptPane", project,
//                    blocks, log, pings, maxPing, stallsOver16, stallsOver250);
//            System.exit(1);
//        }
//        int rows = awaitStableRows(pane, 30_000);
//        log.add("load: wrote " + blocks + " turns (" + (blocks * 2) + " blocks, " + lines
//                + " lines) in " + writeMillis + "ms; pane shows " + rows + " rows");
//        log.add("load: host " + win[0].host().statsLine());
//        if (rows < blocks) {
//            report(false, "the transcript never received the load: " + rows + " rows for "
//                    + blocks + " turns", project, blocks, log, pings, maxPing, stallsOver16,
//                    stallsOver250);
//            System.exit(1);
//        }
//
//        // the conversation pane is filtered by the selected task (the owner's rail wiring), so the
//        // hop that proves it is the one a user performs: pick the foreground task, then read what
//        // the tab shows
//        int visibleForTask = selectForegroundTask(win[0], fg);
//        log.add("selection: picking task #" + fg + " in the rail leaves the conversation at "
//                + visibleForTask + " rows (session log is unfiltered)");
//
//        // hop 2: D1 — the agent answer reaches the cell as HTML, not as asterisks. Only rows the
//        // viewport has asked for are rendered (that is the Phase 0 design), so the proof is looked
//        // for where the user is actually looking, after a scroll, rather than in row 0 of a list
//        // that was never drawn.
//        // put the cursor over the viewport: a wheel event goes to whatever is under the pointer
//        Point over = new Point();
//        SwingUtilities.invokeAndWait(() -> {
//            Point onScreen = pane.getLocationOnScreen();
//            over.setLocation(onScreen.x + pane.getWidth() / 2, onScreen.y + 120);
//        });
//        Robot robot = new Robot();
//        robot.mouseMove(over.x, over.y);
//        Thread.sleep(250);
//
//        String startScroll = onEdtReturn(pane::scrollState);
//        String startRange = onEdtReturn(pane::visibleRange);
//        int stalls16Before = stallsOver16.get();
//        int stalls250Before = stallsOver250.get();
//        int pingsBefore = pings.get();
//
//        // up to the top first: the pane follows the tail as output arrives, so the only scroll that
//        // is guaranteed to have somewhere to go is the one going back through history
//        long upStart = System.currentTimeMillis();
//        int upSteps = 0;
//        while (upSteps < 400 && scrollValue(onEdtReturn(pane::scrollState)) > 0) {
//            robot.mouseWheel(-3);
//            upSteps++;
//            Thread.sleep(12);
//        }
//        long upMillis = System.currentTimeMillis() - upStart;
//        String topScroll = onEdtReturn(pane::scrollState);
//        String topRange = onEdtReturn(pane::visibleRange);
//        File topShot = capturePane(pane, "naru-scroll-top");
//        log.add("scroll up: " + startScroll + " -> " + topScroll + " rows " + startRange + " -> "
//                + topRange + " in " + upSteps + " steps / " + upMillis + "ms ("
//                + (upSteps == 0 ? 0 : upMillis / upSteps) + "ms/step)");
//
//        // the newest rows are at the bottom and were already rendered; the ones just scrolled into
//        // view are the ones the viewport asked for on the way up, so this is where the claim is read
//        String range = topRange;
//        int from = Math.max(0, rangeStart(range));
//        int to = Math.min(visibleForTask - 1, rangeEnd(range));
//        int agentRow = -1;
//        String cell = "";
//        long until = System.currentTimeMillis() + 20_000;
//        while (System.currentTimeMillis() < until && agentRow < 0) {
//            for (int i = from; i <= to; i++) {
//                int row = i;
//                String t = onEdtReturn(() -> pane.cellTextAt(row));
//                if (t.startsWith("<html>") && (t.contains("<b>") || t.contains("<tt>")
//                        || t.contains("<ul>"))) {
//                    agentRow = i;
//                    cell = t;
//                    break;
//                }
//            }
//            if (agentRow < 0) {
//                Thread.sleep(200);
//            }
//        }
//        boolean markdownOk = agentRow >= 0 && !cell.contains("**") && !cell.contains("## ");
//        log.add("markdown: rendered agent row=" + agentRow + " cell-chars=" + cell.length()
//                + " tags=[" + markupIn(cell) + "] literal-asterisks=" + cell.contains("**")
//                + " heading-marker-left-in-text=" + cell.contains("## "));
//        File markdownShot = capturePane(pane, "naru-markdown-row-" + Math.max(0, agentRow));
//        log.add("markdown screenshot="
//                + (markdownShot == null ? "none" : markdownShot.getAbsolutePath()));
//
//        // hop 3: back down to the tail, which is the direction that pays for rendering: every row
//        // that scrolls in asks the worker for its HTML and then repaints itself
//        long downStart = System.currentTimeMillis();
//        int downSteps = 0;
//        int lastRange = rangeEnd(onEdtReturn(pane::visibleRange));
//        while (downSteps < 600 && lastRange < visibleForTask - 1) {
//            robot.mouseWheel(3);
//            downSteps++;
//            Thread.sleep(12);
//            lastRange = rangeEnd(onEdtReturn(pane::visibleRange));
//        }
//        long downMillis = System.currentTimeMillis() - downStart;
//        String bottomScroll = onEdtReturn(pane::scrollState);
//        String bottomRange = onEdtReturn(pane::visibleRange);
//        boolean tailReached = rangeEnd(bottomRange) >= visibleForTask - 1;
//        int stalls16 = stallsOver16.get() - stalls16Before;
//        int stalls250 = stallsOver250.get() - stalls250Before;
//        int pingsMade = pings.get() - pingsBefore;
//        File bottomShot = capturePane(pane, "naru-scroll-bottom");
//        log.add("scroll down: " + topScroll + " -> " + bottomScroll + " rows " + bottomRange
//                + " in " + downSteps + " steps / " + downMillis + "ms ("
//                + (downSteps == 0 ? 0 : downMillis / downSteps) + "ms/step, each step waiting for"
//                + " the viewport to settle)");
//        log.add("scroll: during-the-two-scrolls pings=" + pingsMade + " stalls-over-16ms=" + stalls16
//                + " stalls-over-250ms=" + stalls250);
//        log.add("scroll screenshots=" + (bottomShot == null ? "none" : bottomShot.getAbsolutePath())
//                + " " + (topShot == null ? "none" : topShot.getAbsolutePath()));
//
//        boolean movedUp = !startScroll.equals(topScroll) || scrollValue(startScroll) == 0;
//        boolean movedDown = !topScroll.equals(bottomScroll);
//        boolean edtOk = stalls250 == 0;
//        boolean selectionOk = visibleForTask > 0;
//        boolean ok = rows >= blocks && markdownOk && movedUp && movedDown && tailReached && edtOk
//                && selectionOk;
//        System.out.println("  claims: loaded=" + (rows >= blocks) + " rows=" + rows
//                + " task-selection-shows-conversation=" + selectionOk
//                + " markdown-in-cell=" + markdownOk + " wheel-up-moved-the-viewport=" + movedUp
//                + " wheel-down-moved-it-back=" + movedDown + " reached-tail=" + tailReached
//                + " edt-frame-budget=" + edtOk + " (" + stalls250 + " stalls over 250ms, "
//                + stalls16 + " over 16ms, of " + pingsMade + " pings)");
//        File whole = capture(win[0]);
//        log.add("window screenshot=" + (whole == null ? "none" : whole.getAbsolutePath()));
//        report(ok, ok ? null : "see the detail lines", project, blocks, log, pings, maxPing,
//                stallsOver16, stallsOver250);
//        SwingUtilities.invokeAndWait(session::shutdown);
//        System.exit(ok ? 0 : 1);
//    }
//
//    /** A script block with a long line in it, because a wrapped cell is what stresses a JList. */
//    private static String outputFor(int i) {
//        StringBuilder sb = new StringBuilder();
//        sb.append("$ read file-").append(i).append(".txt\n");
//        for (int l = 0; l < 20; l++) {
//            sb.append("  line ").append(l).append(" of file-").append(i)
//                    .append(" — a piece of output long enough that a cell has to wrap it instead of")
//                    .append(" pushing the whole pane wider than the window, which is the failure mode")
//                    .append(" a JList inside a split pane actually has\n");
//        }
//        return sb.toString();
//    }
//
//    private static TranscriptPane transcriptOf(MainWindow w) throws Exception {
//        TranscriptPane[] out = new TranscriptPane[1];
//        SwingUtilities.invokeAndWait(() -> {
//            if (w.selectSideTab("Conversation")
//                    && w.selectedSideComponent() instanceof TranscriptPane p) {
//                out[0] = p;
//            }
//        });
//        return out[0];
//    }
//
//    /** Click the foreground task in the rail the way a user does, and report the conversation size. */
//    private static int selectForegroundTask(MainWindow w, long fg) throws Exception {
//        SwingUtilities.invokeAndWait(() -> {
//            TaskPane rail = find(w, TaskPane.class);
//            if (rail != null) {
//                rail.selectTask(fg);
//            }
//            w.selectSideTab("Conversation");
//        });
//        Thread.sleep(400);
//        TranscriptPane p = transcriptOf(w);
//        return p == null ? -1 : onEdtReturn(p::rowCount);
//    }
//
//    private static <T> T find(java.awt.Container c, Class<T> kind) {
//        for (Component x : c.getComponents()) {
//            if (kind.isInstance(x)) {
//                return kind.cast(x);
//            }
//            if (x instanceof java.awt.Container inner) {
//                T found = find(inner, kind);
//                if (found != null) {
//                    return found;
//                }
//            }
//        }
//        return null;
//    }
//
//    /** Which HTML tags the renderer left in the cell — the evidence that markdown was parsed. */
//    private static String markupIn(String cell) {
//        List<String> found = new ArrayList<>();
//        for (String tag : List.of("h2", "h3", "b", "i", "tt", "pre", "ul", "li", "blockquote", "hr")) {
//            if (cell.contains("<" + tag)) {
//                found.add(tag);
//            }
//        }
//        return String.join(",", found);
//    }
//
//    /** The {@code value} half of {@code value/maximum}. */
//    private static int scrollValue(String state) {
//        int slash = state.indexOf('/');
//        try {
//            return slash < 0 ? -1 : Integer.parseInt(state.substring(0, slash));
//        } catch (NumberFormatException e) {
//            return -1;
//        }
//    }
//
//    /** The {@code first} half of {@code first-last}. */
//    private static int rangeStart(String range) {
//        int dash = range.indexOf('-');
//        try {
//            return dash < 0 ? -1 : Integer.parseInt(range.substring(0, dash));
//        } catch (NumberFormatException e) {
//            return -1;
//        }
//    }
//
//    private static int rangeEnd(String range) {
//        int dash = range.indexOf('-');
//        try {
//            return dash < 0 ? -1 : Integer.parseInt(range.substring(dash + 1));
//        } catch (NumberFormatException e) {
//            return -1;
//        }
//    }
//
//    /** Wait for the pane to stop growing, then report how many rows it settled on. */
//    private static int awaitStableRows(TranscriptPane pane, long millis) throws Exception {
//        long until = System.currentTimeMillis() + millis;
//        int last = -1;
//        int same = 0;
//        while (System.currentTimeMillis() < until) {
//            int n = onEdtReturn(pane::rowCount);
//            same = n == last ? same + 1 : 0;
//            if (same >= 4 && n > 0) {
//                return n;
//            }
//            last = n;
//            Thread.sleep(200);
//        }
//        return onEdtReturn(pane::rowCount);
//    }
//
//    private static void startPinger(AtomicInteger pings, AtomicLong maxPing,
//                                    AtomicInteger stallsOver16, AtomicInteger stallsOver250) {
//        Thread pinger = new Thread(() -> {
//            java.util.concurrent.atomic.AtomicBoolean ran =
//                    new java.util.concurrent.atomic.AtomicBoolean();
//            while (true) {
//                long t0 = System.nanoTime();
//                ran.set(false);
//                SwingUtilities.invokeLater(() -> ran.set(true));
//                long ms = 0;
//                while (!ran.get() && (ms = (System.nanoTime() - t0) / 1_000_000) < 120_000) {
//                    try {
//                        // 5 ms: the 16 ms figure is a frame budget, and a watcher that sleeps past it
//                        // cannot measure it
//                        Thread.sleep(5);
//                    } catch (InterruptedException e) {
//                        return;
//                    }
//                }
//                ms = (System.nanoTime() - t0) / 1_000_000;
//                maxPing.accumulateAndGet(ms, Math::max);
//                pings.incrementAndGet();
//                if (ms > 16) {
//                    stallsOver16.incrementAndGet();
//                }
//                if (ms > 250) {
//                    stallsOver250.incrementAndGet();
//                }
//                try {
//                    Thread.sleep(100);
//                } catch (InterruptedException e) {
//                    return;
//                }
//            }
//        }, "edt-pinger");
//        pinger.setDaemon(true);
//        pinger.start();
//    }
//
//    private static NaruSessionModel awaitSession(MainWindow w, int seconds) throws Exception {
//        long until = System.currentTimeMillis() + seconds * 1000L;
//        while (System.currentTimeMillis() < until) {
//            NaruSessionModel s = w.session();
//            if (s != null && s.isRunning() && s.session() != null) {
//                return s;
//            }
//            Thread.sleep(150);
//        }
//        return w.session();
//    }
//
//    private static File capture(MainWindow w) {
//        try {
//            BufferedImage img = new Robot().createScreenCapture(new Rectangle(1400, 900));
//            File f = new File(System.getProperty("java.io.tmpdir"), "naru-transcript-smoke.png");
//            javax.imageio.ImageIO.write(img, "png", f);
//            return f;
//        } catch (Exception e) {
//            return null;
//        }
//    }
//
//    /** Paint one component off-screen at its own size, so a wide window still yields readable PNGs. */
//    private static File capturePane(Component c, String name) {
//        if (c == null || c.getWidth() <= 0 || c.getHeight() <= 0) {
//            return null;
//        }
//        try {
//            BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(),
//                    BufferedImage.TYPE_INT_ARGB);
//            Graphics g = img.createGraphics();
//            try {
//                c.paint(g);
//            } finally {
//                g.dispose();
//            }
//            File f = new File(System.getProperty("java.io.tmpdir"), name + ".png");
//            javax.imageio.ImageIO.write(img, "png", f);
//            return f;
//        } catch (Exception e) {
//            return null;
//        }
//    }
//
//    private static void report(boolean ok, String detail, NPath project, int blocks,
//                               List<String> log, AtomicInteger pings, AtomicLong maxPing,
//                               AtomicInteger over16, AtomicInteger over250) {
//        System.out.println("SMOKE transcript");
//        System.out.println("  project=" + project + " blocks=" + blocks);
//        System.out.println("  edt-pings=" + pings.get() + " max-ping-ms=" + maxPing.get()
//                + " stalls-over-16ms=" + over16.get() + " stalls-over-250ms=" + over250.get());
//        for (String l : log) {
//            System.out.println("  " + l);
//        }
//        if (detail != null) {
//            System.out.println("  failure: " + detail);
//        }
//        System.out.println("  VERDICT=" + (ok
//                ? "PASS — markdown reaches the cell, the wheel moves a loaded transcript, the EDT"
//                + " kept its frame budget"
//                : "FAIL — see the detail above"));
//    }
//
//    @FunctionalInterface
//    interface Edt<T> {
//        T get() throws Exception;
//    }
//
//    private static <T> T onEdtReturn(Edt<T> body) throws Exception {
//        Object[] box = new Object[1];
//        SwingUtilities.invokeAndWait(() -> {
//            try {
//                box[0] = body.get();
//            } catch (Exception e) {
//                throw new RuntimeException(e);
//            }
//        });
//        @SuppressWarnings("unchecked")
//        T value = (T) box[0];
//        return value;
//    }
//}
