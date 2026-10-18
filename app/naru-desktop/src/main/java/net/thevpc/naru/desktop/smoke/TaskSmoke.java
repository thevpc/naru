package net.thevpc.naru.desktop.smoke;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.desktop.MainWindow;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.ui.TaskPane;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.swing.NSwingUtils;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2 §6 gate run for the task monitor: "shows live parallel tasks with interrupt/kill/hold",
 * checked through the real window, the real scheduler and the real buttons rather than through the
 * unit test's hand-built pane.
 *
 * <p>Each hop is checked separately so a failure names the hop:
 * <ol>
 *   <li><b>tree</b> — a parent and two children spawned on the live session appear in the Tasks tab
 *       in spawn order, the children indented and carrying {@code ←#parentId}, in a panel that is
 *       actually on screen;</li>
 *   <li><b>hold / resume</b> — both directions, on the engine's own flags and on the row's {@code
 *       [H]} letter, within one poll of each other. Measured here: a task with nothing to run ends
 *       and deregisters the moment it is un-held, so the round trip is taken on the session's
 *       foreground task (parked on input, never deregistered) and its original flag restored;</li>
 *   <li><b>parallel</b> — two unheld tasks with real model turns are live at the same time, and the
 *       monitor counts them as such (measured, including whether the scheduler runs both
 *       simultaneously or serialises them, which is an engine fact this run reports rather than
 *       assumes);</li>
 *   <li><b>kill / interrupt</b> — clicking the pane's own Kill button on a running task terminalises
 *       it in the engine, the note line says so, and the row leaves the monitor on the next poll.
 *       A terminal task is deregistered by the engine the moment it ends, so "the row goes away" is
 *       the observable effect here, not "the row turns brown and stays"; Interrupt is the soft flag
 *       and is measured as such;</li>
 *   <li><b>detail</b> — a running task renders its frames and stacktrace block, the GUI
 *       form of {@code /task frames}.</li>
 * </ol>
 *
 * <p>Three kinds of task are used on purpose. Tree rows run {@code /print} statements and stay parked
 * held, so nothing about them races the scheduler; the parallel rows carry plain prompts and are the
 * only thing here that needs a model; the hold/resume round trip runs on the session's own foreground
 * task, because that is the one task in a live session that is un-held and never deregisters. An
 * un-held task with work in it can finish and vanish between two samples — which is what makes the
 * flag check a race rather than a measurement, and why it is measured separately here.
 *
 * <pre>
 * usage: TaskSmoke &lt;projectDir&gt; &lt;model&gt; [secondsToWait]
 *   -Dnaru.smoke.model=false  skip the model-dependent hops (tree and detail still run)
 *   -Dnaru.smoke.dump=&lt;file&gt;  write the task report there
 * </pre>
 */
public final class TaskSmoke {

    /** The reason {@code taskAction} passes to {@code cancel()}; the engine keeps it as the error. */
    private static final String KILL_REASON = "stopped from the desktop task monitor";

    private static NaruSessionModel live;
    private static MainWindow liveWin;
    private static TaskPane livePane;

    /** When {@code main} began, so a stall can be reported as "n seconds into the run". */
    private static long runStart;

    /**
     * The times of the stalls over 250 ms, kept because the count alone cannot be acted on: a run
     * that freezes the EDT twice, once during boot and once during a model turn, is two different
     * defects with one number.
     */
    private static final List<Long> stallAtMillis = java.util.Collections.synchronizedList(
            new ArrayList<>());

    /**
     * The AWT-EventQueue stack, captured from the pinger thread while a ping is over a second —
     * the only way to say which EDT task froze the window, since the EDT cannot be asked while it
     * is busy. Kept to three because a run that freezes five times has one cause, not five.
     */
    private static final List<String> edtStacks = java.util.Collections.synchronizedList(
            new ArrayList<>());

    private static String edtStackDump() {
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            if (!e.getKey().getName().startsWith("AWT-EventQueue")) {
                continue;
            }
            StringBuilder sb = new StringBuilder(e.getKey().getName()).append(" [")
                    .append(String.valueOf(e.getKey().getState())).append("] cpu=")
                    .append(edtCpuMillis(e.getKey())).append("ms");
            StackTraceElement[] st = e.getValue();
            // deep enough to reach the desktop frame that asked for the work: the top of a frozen
            // EDT is always socket or paint, and the useful line is the caller underneath it
            for (int i = 0; i < Math.min(26, st.length); i++) {
                sb.append("\n      at ").append(st[i]);
            }
            return sb.toString();
        }
        return "(no EDT thread)";
    }

    /**
     * How much CPU the EDT has actually used, in milliseconds. A ping of several seconds with a cpu
     * figure that does not move means the thread is parked on something rather than painting, which
     * is the difference between a slow widget and a lock the desktop has walked into.
     */
    private static String edtCpuMillis(Thread t) {
        try {
            java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory
                    .getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean cpu && cpu.isThreadCpuTimeSupported()) {
                if (!cpu.isThreadCpuTimeEnabled()) {
                    cpu.setThreadCpuTimeEnabled(true);
                }
                return String.valueOf(cpu.getThreadCpuTime(t.getId()) / 1_000_000);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // an unsupported MXBean is a missing measurement, not a failed run
        }
        return "?";
    }

    public static void main(String[] args) throws Exception {
        Nuts.require();
        NSwingUtils.prepareUI(new String[0]);
        NPath project = NPath.of(args.length > 0 ? args[0]
                : new File(System.getProperty("java.io.tmpdir"), "naru-task-smoke").getPath()).toAbsolute();
        String model = args.length > 1 ? args[1] : "qwen2.5-coder:14b";
        long waitSeconds = args.length > 2 ? Long.parseLong(args[2]) : 420;
        boolean useModel = !"false".equals(System.getProperty("naru.smoke.model"));
        project.mkdirs();

        List<String> log = new ArrayList<>();
        runStart = System.currentTimeMillis();
        try {
            java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory
                    .getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean cpu && cpu.isThreadCpuTimeSupported()) {
                cpu.setThreadCpuTimeEnabled(true);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // no cpu accounting available; the dumps report what they can
        }
        AtomicLong maxPing = new AtomicLong();
        AtomicInteger pings = new AtomicInteger();
        AtomicInteger stallsOver16 = new AtomicInteger();
        AtomicInteger stallsOver250 = new AtomicInteger();
        Thread pinger = new Thread(() -> {
            java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
            while (true) {
                long t0 = System.nanoTime();
                ran.set(false);
                // invokeLater and watch, rather than invokeAndWait and measure afterwards: the
                // freeze is only explained by a stack taken while the EDT is stuck in it
                SwingUtilities.invokeLater(() -> ran.set(true));
                long ms = 0;
                boolean dumped = false;
                while (!ran.get() && (ms = (System.nanoTime() - t0) / 1_000_000) < 120_000) {
                    if (ms > 1000 && !dumped && edtStacks.size() < 3) {
                        dumped = true;
                        edtStacks.add(edtStackDump());
                    }
                    try {
                        // 5 ms, not 20: the over-16 ms count is a frame-budget measure, and a
                        // watcher that sleeps longer than the budget cannot see the budget
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                ms = (System.nanoTime() - t0) / 1_000_000;
                maxPing.accumulateAndGet(ms, Math::max);
                pings.incrementAndGet();
                if (ms > 16) {
                    stallsOver16.incrementAndGet();
                }
                if (ms > 250) {
                    stallsOver250.incrementAndGet();
                    // when, not just how long: a max-ping of several seconds is only worth
                    // reporting once it can be tied to the hop that was running
                    stallAtMillis.add(System.currentTimeMillis());
                }
                if (ms > 1000 && edtStacks.size() < 3) {
                    // the poll itself is timed and cheap, so a multi-second ping means some other
                    // EDT task held the thread: dump what it was running, from outside it
                    edtStacks.add(edtStackDump());
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "edt-pinger");
        pinger.setDaemon(true);
        pinger.start();

        MainWindow[] win = new MainWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            MainWindow w = new MainWindow();
            // 1400x900 display: a wider window pushes the side tabs — the whole evidence here — off
            // the right edge, and a 2 Hz poll that repaints an invisible panel proves nothing
            w.setSize(1380, 860);
            w.setLocation(0, 0);
            w.setVisible(true);
            w.openProject(project);
            win[0] = w;
        });
        liveWin = win[0];
        long bootStart = System.currentTimeMillis();
        NaruSessionModel session = awaitSession(win[0], 60);
        long bootMillis = System.currentTimeMillis() - bootStart;
        if (session == null || !session.isRunning()) {
            report(false, "the session never started after " + bootMillis / 1000 + " s", project,
                    model, log, pings.get(), maxPing.get(), stallsOver16.get(), stallsOver250.get());
            System.exit(1);
        }
        live = session;
        log.add("session booted in " + bootMillis / 1000 + " s");

        // hop 1: the spawn tree, drawn by the Tasks tab of the real window
        NaruSession s = session.session();
        NaruTask parent = s.newTask(NaruTaskSpec.of().name("smoke-parent").statements("/print p"));
        NaruTask childA = s.newTask(NaruTaskSpec.of().name("smoke-child-a")
                .parentId(parent.id()).statements("/print a"));
        NaruTask childB = s.newTask(NaruTaskSpec.of().name("smoke-child-b")
                .parentId(parent.id()).statements("/print b"));
        // the hold/resume instrument: a task with nothing to run, so un-holding it cannot finish and
        // deregister it between two samples
        NaruTask gripper = s.newTask(NaruTaskSpec.of().name("smoke-gripper"));
        log.add("spawned parent#" + parent.id() + " children#" + childA.id() + ",#" + childB.id()
                + " gripper#" + gripper.id() + " held=" + parent.isHeld() + "/" + childA.isHeld()
                + "/" + gripper.isHeld());

        TaskPane pane = taskPane(win[0]);
        livePane = pane;
        if (pane == null) {
            report(false, "the Tasks tab did not hand out a TaskPane", project, model, log,
                    pings.get(), maxPing.get(), stallsOver16.get(), stallsOver250.get());
            System.exit(1);
        }
        List<String> labels = awaitRows(pane, 4, 8_000);
        int ip = indexOf(labels, parent.id());
        int ia = indexOf(labels, childA.id());
        int ib = indexOf(labels, childB.id());
        int ig = indexOf(labels, gripper.id());
        boolean treeOk = ip >= 0 && ia == ip + 1 && ib == ip + 2 && ig >= 0
                && !label(pane, ip).startsWith("   ")
                && label(pane, ia).startsWith("   └") && label(pane, ib).startsWith("   └")
                && label(pane, ia).contains("←#" + parent.id());
        log.add("tree: rows=" + labels.size() + " parent@" + ip + " child-a@" + ia
                + " child-b@" + ib + " gripper@" + ig + " note=" + oneLine(monitorNote(pane), 80));
        // the spawn tree as the engine sees it, so the drawn indent is checked against the scheduler's
        // own parent index rather than only against the parentId field
        long[] kids = s.findTaskIdsByParent(parent.id());
        boolean spawnTree = kids.length == 2 && (kids[0] == childA.id() || kids[0] == childB.id())
                && (kids[1] == childA.id() || kids[1] == childB.id());
        log.add("tree: findTaskIdsByParent(parent)=" + kids.length
                + " ids=" + java.util.Arrays.toString(kids) + " both-children=" + spawnTree);
        for (String l : labels) {
            log.add("  row " + oneLine(l, 150));
        }
        String[] geometry = new String[1];
        boolean[] reachable = new boolean[1];
        SwingUtilities.invokeAndWait(() -> {
            Component c = win[0].selectedSideComponent();
            if (c == null) {
                geometry[0] = "no side component";
                return;
            }
            java.awt.Point onScreen = c.getLocationOnScreen();
            java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
            reachable[0] = c.isShowing() && c.getWidth() >= 240 && onScreen.x >= 0
                    && onScreen.x + c.getWidth() <= screen.width;
            geometry[0] = "tab=" + win[0].selectedSideTab() + " x=" + onScreen.x + " width="
                    + c.getWidth() + " height=" + c.getHeight() + " screen=" + screen.width;
        });
        log.add("pane: on-screen=" + reachable[0] + " geometry=" + geometry[0]);
        File treeShot = capturePane(pane, "naru-task-tree");
        log.add("tree screenshot=" + (treeShot == null ? "none" : treeShot.getAbsolutePath()));
        if (!treeOk || !spawnTree || !reachable[0]) {
            fail(project, model, log, pings, maxPing, stallsOver16, stallsOver250,
                    "hop 1 (spawn tree in the real Tasks tab)");
            return;
        }

        // hop 2: both flag directions through the button, on a task that is still there for the second
        // half. Measured on the run before this one: a task with nothing to run ends and deregisters
        // as soon as it is un-held, so a "resume, then hold again" round trip on it races its own
        // completion — the engine answered "no task #5 — it has already finished and deregistered".
        // The session's own foreground task is parked on input and never deregisters, which makes it
        // the one task in a live session that can show both directions; its original flag is read
        // first and restored by the second half.
        long mainId = s.foregroundTaskId();
        NaruTask main = s.findTask(mainId).orNull();
        boolean mainHeldBefore = main != null && main.isHeld();
        String state0 = holdState(pane, mainId);
        boolean clickedHold = clickHold(pane, mainId);
        boolean engineHeld = awaitHeld(main, true, 3_000);
        boolean rowGainedH = awaitFlag(pane, mainId, 'H', true, 4_000);
        String state1 = holdState(pane, mainId);
        String noteAfterHold = actionNote(pane);
        boolean clickedResume = clickHold(pane, mainId);
        boolean engineFree = awaitHeld(main, false, 3_000);
        boolean rowLostH = awaitFlag(pane, mainId, 'H', false, 4_000);
        String state2 = holdState(pane, mainId);
        String noteAfterResume = actionNote(pane);
        boolean buttonFlips = "Hold/enabled".equals(state0) && "Resume/enabled".equals(state1)
                && "Hold/enabled".equals(state2);
        log.add("hold/resume: main#" + mainId + " was-held=" + mainHeldBefore
                + " clicked=" + (clickedHold && clickedResume)
                + " button=" + state0 + " -> " + state1 + " -> " + state2
                + " flips=" + buttonFlips
                + " engine-held=" + engineHeld + " row-gained-H=" + rowGainedH
                + " engine-free=" + engineFree + " row-lost-H=" + rowLostH
                + " notes=" + oneLine(noteAfterHold, 30) + " / " + oneLine(noteAfterResume, 30));
        // the same button on a spawned task, for the resume direction alone — and with it the
        // completion behaviour that made the round trip above impossible
        boolean clickedGripper = clickHold(pane, gripper.id());
        boolean gripperUnheld = awaitHeld(gripper, false, 3_000);
        boolean gripperRowLostH = awaitFlag(pane, gripper.id(), 'H', false, 3_000);
        boolean gripperRowGone = awaitRowGone(pane, gripper.id(), 6_000);
        log.add("hold/resume on a fresh task: clicked=" + clickedGripper
                + " resumed=" + gripperUnheld + " row-lost-H=" + gripperRowLostH
                + " then-row-deregistered=" + gripperRowGone
                + " (the engine drops a task the moment it ends, so the monitor's rows are live tasks"
                + " only — history belongs to the Phase 3 event log)");
        if (!buttonFlips || !engineHeld || !rowGainedH || !engineFree || !rowLostH
                || !gripperUnheld || !clickedHold || !clickedResume || !clickedGripper) {
            fail(project, model, log, pings, maxPing, stallsOver16, stallsOver250,
                    "hop 2 (the hold/resume button does not reach the engine, or the row flag and the"
                            + " button's own label do not follow: " + state0 + " / " + state1 + " / "
                            + state2 + ")");
            return;
        }

        // hop 3: two tasks with real turns, live at the same time
        NaruTask liveA = null;
        NaruTask liveB = null;
        int maxRunning = 0;
        int maxLiveAtOnce = 0;
        int samples = 0;
        boolean bothLive = false;
        long deadline = System.currentTimeMillis() + waitSeconds * 1000;
        if (useModel) {
            // long answers, no tools: the point is a turn that is still running when the 2 Hz poll
            // looks at it, not a model that behaves well. The model goes on the spec, not through
            // "/model use": a root task resolves its model from its own hint or from a parent, and
            // a spawned task has neither the session's foreground directive nor a parent to follow,
            // so NaruPromptStmt would log "No model is selected" and end the task in one tick.
            liveA = s.newTask(NaruTaskSpec.of().name("live-a").model(model)
                    .statements("Write twenty lines, each one a fruit name followed by a full "
                            + "sentence about it. Answer directly."));
            liveB = s.newTask(NaruTaskSpec.of().name("live-b").model(model)
                    .statements("Count from one to thirty, one number per line, each followed by "
                            + "the word ready. Answer directly."));
            long spawnAt = System.currentTimeMillis();
            String resumeA = action(pane, session, liveA.id(), NaruSessionModel.TaskAction.RESUME);
            String resumeB = action(pane, session, liveB.id(), NaruSessionModel.TaskAction.RESUME);
            log.add("parallel: started #" + liveA.id() + " and #" + liveB.id() + " on model " + model
                    + " resolved=" + (liveA.model() == null ? "none" : liveA.model().toText())
                    + " resume-a=" + (resumeA == null ? "ok" : oneLine(resumeA, 60))
                    + " resume-b=" + (resumeB == null ? "ok" : oneLine(resumeB, 60)));

            long sampleDeadline = Math.min(deadline, spawnAt + 180_000);
            long bestAt = 0;
            // a two-task status trace, kept because the only useful failure report is the one that
            // says what the rows actually did: whether both ever lived, and what ended them
            StringBuilder trace = new StringBuilder();
            String lastPair = "";
            while (System.currentTimeMillis() < sampleDeadline) {
                List<NaruSessionModel.TaskRow> rows = snapshot(session);
                samples++;
                int running = 0;
                int alive = 0;
                boolean a = false;
                boolean b = false;
                for (NaruSessionModel.TaskRow row : rows) {
                    if (row.id() == liveA.id() || row.id() == liveB.id()) {
                        if (!row.status().isCompleted()) {
                            alive++;
                            if (row.id() == liveA.id()) {
                                a = true;
                            } else {
                                b = true;
                            }
                        }
                        if (row.status() == NaruTaskStatus.RUNNING) {
                            running++;
                        }
                    }
                }
                maxRunning = Math.max(maxRunning, running);
                maxLiveAtOnce = Math.max(maxLiveAtOnce, alive);
                String pair = cellOf(rows, liveA.id()) + " | " + cellOf(rows, liveB.id());
                if (!pair.equals(lastPair)) {
                    lastPair = pair;
                    trace.append(pair).append('@')
                            .append((System.currentTimeMillis() - spawnAt) / 1000).append("s  ");
                }
                if (a && b) {
                    bothLive = true;
                    bestAt = System.currentTimeMillis();
                    if (running >= 1 && alive >= 2) {
                        break;
                    }
                }
                // both tasks ended: a task deregisters the moment it does, so no later sample can
                // bring them back. Checked on the engine handles, not the rows — the rows are the
                // 2 Hz poll's opinion, and a task that finishes inside one tick never appears in it.
                if (liveA.isCompleted() && liveB.isCompleted()) {
                    break;
                }
                Thread.sleep(150);
            }
            log.add("parallel: ended after " + (System.currentTimeMillis() - spawnAt) / 1000
                    + "s — " + diagnosis(liveA) + " / " + diagnosis(liveB));
            long waitMillis = bestAt == 0 ? -1 : bestAt - spawnAt;
            log.add("parallel: samples=" + samples + " both-live-at-once=" + bothLive
                    + " max-live-at-once=" + maxLiveAtOnce + " max-RUNNING-at-once=" + maxRunning
                    + (waitMillis < 0 ? "" : " first-both-live-after-" + waitMillis / 1000 + "s")
                    + " (a max-RUNNING of 1 with both tasks live means the scheduler serialises"
                    + " ticks, which this run reports rather than assumes)");
            log.add("parallel: trace #" + liveA.id() + " | #" + liveB.id() + " -> "
                    + oneLine(trace.toString(), 400));
            // the monitor's own read, timed inside the poll: if the EDT froze while two turns ran,
            // this says whether the freeze is the poll waiting on the engine or something else
            log.add("parallel: task-poll cost last=" + session.lastTaskPollMillis()
                    + "ms worst=" + session.maxTaskPollMillis() + "ms");
            File parallelShot = capturePane(pane, "naru-task-parallel");
            log.add("parallel screenshot=" + (parallelShot == null ? "none"
                    : parallelShot.getAbsolutePath()));
            if (!bothLive) {
                fail(project, model, log, pings, maxPing, stallsOver16, stallsOver250,
                        "hop 3 (two tasks never appeared live at the same time; samples=" + samples + ")");
                return;
            }
        } else {
            log.add("parallel: SKIPPED by -Dnaru.smoke.model=false");
        }

        // the detail block, read while the task is still running — the kill below ends it, and a
        // terminal task deregisters, so there is nothing left to render afterwards
        int frames = -1;
        int traceItems = -1;
        boolean detailShown = false;
        String detailHead = "skipped";
        if (liveA != null) {
            final NaruTask a = liveA;
            detailShown = onEdtReturn(() -> pane.selectTask(a.id())
                    && pane.detailText().contains("Task #" + a.id()));
            detailHead = onEdtReturn(pane::detailText);
            NaruSessionModel.TaskDetail d = onEdtReturn(() -> session.taskDetail(a.id()));
            frames = d == null ? -1 : d.frames().size();
            traceItems = d == null ? -1 : d.stacktrace().size();
            log.add("detail: selected-and-rendered=" + detailShown + " frames=" + frames
                    + " stacktrace-items=" + traceItems + " head=" + oneLine(
                    detailHead.split("\n")[0], 120));
            // the size of what the pane re-lays-out on every poll tick: the EDT dumps show a
            // JTextArea preferred-size pass during the stalls, and its cost is the text's size
            int detailChars = detailHead.length();
            int detailLines = (int) detailHead.lines().count();
            log.add("detail: text chars=" + detailChars + " lines=" + detailLines
                    + " area=" + onEdtReturn(() -> pane.detailPreferredSize()));
        }

        // hop 4: the pane's own buttons on a running task
        boolean killed = false;
        boolean killShown = false;
        boolean sawKilledRow = false;
        boolean killRowGone = false;
        boolean killReasonKept = false;
        if (liveB != null) {
            long clickAt = System.currentTimeMillis();
            boolean clicked = click(pane, liveB.id(), "Kill");
            String note = actionNote(pane);
            // polled, not read once: doClick queues the action event, so the engine call that
            // terminalises the task may land a moment after this thread resumes
            NaruTaskStatus after = awaitEngineStatus(liveB, NaruTaskStatus.KILLED, 5_000);
            String reason = awaitReason(liveB, KILL_REASON, 5_000);
            killReasonKept = reason.contains(KILL_REASON);
            sawKilledRow = awaitStatus(pane, liveB.id(), NaruTaskStatus.KILLED, 3_000);
            killRowGone = awaitRowGone(pane, liveB.id(), 8_000);
            killShown = sawKilledRow || killRowGone;
            killed = clicked && after == NaruTaskStatus.KILLED && note.startsWith("Kill")
                    && liveB.isKillRequested();
            log.add("kill: clicked=" + clicked + " engine-status=" + after
                    + " kill-requested=" + liveB.isKillRequested() + " reason=" + reason
                    + " note=" + oneLine(note, 60) + " saw-KILLED-row=" + sawKilledRow
                    + " row-gone=" + killRowGone + " in-"
                    + (System.currentTimeMillis() - clickAt) / 1000 + "s");
        }
        boolean interrupted = false;
        if (liveA != null) {
            final NaruTask a = liveA;
            boolean clicked = click(pane, a.id(), "Interrupt");
            // asked off the handle, not off a fresh taskDetail read: the task may terminalise
            // between the click and the read, and its handle outlives its registration
            interrupted = clicked && a.isInterruptRequested();
            NaruSessionModel.TaskDetail d = onEdtReturn(() -> session.taskDetail(a.id()));
            log.add("interrupt: clicked=" + clicked + " engine-flag=" + a.isInterruptRequested()
                    + " rendered-in-detail=" + (d != null && d.interruptRequested())
                    + " status-after=" + (d == null ? "gone" : d.status())
                    + " (soft flag: the task keeps running until it checks it)");
            // leave nothing running behind us: the session shutdown is not a place to test kill
            onEdtReturn(() -> session.taskAction(a.id(), NaruSessionModel.TaskAction.KILL));
        }
        if (useModel && liveB != null && !(killed && killShown && killReasonKept)) {
            fail(project, model, log, pings, maxPing, stallsOver16, stallsOver250,
                    "hop 4 (the Kill button did not terminalise the task as the desktop's own kill,"
                            + " or the monitor never reflected it: clicked=" + killed
                            + " shown=" + killShown + " reason-kept=" + killReasonKept + ")");
            return;
        }

        // the tree rows are still parked held; prove the same button set on them and on the parent
        String fg = action(pane, session, parent.id(), NaruSessionModel.TaskAction.FOREGROUND);
        boolean isFg = parent.isFg();
        boolean fgOnRow = awaitFlag(pane, parent.id(), 'F', true, 3_000);
        String killParent = action(pane, session, parent.id(), NaruSessionModel.TaskAction.KILL);
        // which of the two shapes a kill takes on screen depends on the engine: a task the scheduler
        // had already driven to an end deregisters and its row goes, while a held task that is killed
        // stays registered and is drawn in its terminal colour. Both are measured, one is enough.
        boolean parentKilledRow = awaitStatus(pane, parent.id(), NaruTaskStatus.KILLED, 3_000);
        boolean parentKillShown = parentKilledRow || awaitRowGone(pane, parent.id(), 5_000);
        log.add("buttons on the tree rows: foreground=" + (fg == null ? "ok" : fg) + " engine-fg="
                + isFg + " row-gained-F=" + fgOnRow + " kill-parent="
                + (killParent == null ? "ok" : killParent) + " status=" + parent.status()
                + " drawn-KILLED=" + parentKilledRow + " row-left-the-monitor=" + parentKillShown);

        dump(session, project.resolve("task-report.txt"));
        SwingUtilities.invokeAndWait(() -> win[0].selectSideTab("Tasks"));
        File shot = capture(win[0]);
        File paneShot = capturePane(pane, "naru-task-final");
        log.add("screenshot=" + (shot == null ? "none" : shot.getAbsolutePath())
                + " task-pane=" + (paneShot == null ? "none" : paneShot.getAbsolutePath()));

        // frames are counted, not demanded: a task parked on a model call legitimately has one frame
        // or none, while "the pane rendered the selected running task" is the actual claim
        boolean detailOk = liveA == null || detailShown;
        boolean holdResumeOk = clickedHold && clickedResume && clickedGripper && buttonFlips
                && engineHeld && rowGainedH && engineFree && rowLostH && gripperUnheld;
        // the frame budget is part of the claim, not a footnote: the monitor's reads are what froze
        // this window for 8.7 s at a time, and a run that froze it again must fail rather than report
        boolean edtOk = stallsOver250.get() == 0;
        boolean ok = treeOk && spawnTree && reachable[0] && holdResumeOk && fgOnRow && detailOk
                && killParent == null && parentKillShown && edtOk
                && (!useModel || (bothLive && killed && killShown && killReasonKept && interrupted));
        System.out.println("  claims: spawn-tree=" + treeOk + " pane-on-screen=" + reachable[0]
                + " hold/resume=" + holdResumeOk + " foreground=" + fgOnRow
                + " parallel-live=" + bothLive + " (samples=" + samples
                + " max-live-at-once=" + maxLiveAtOnce + " max-RUNNING-at-once=" + maxRunning + ")");
        System.out.println("  claims: kill=" + (killed && killShown && killReasonKept)
                + " (engine=" + (liveB == null ? "n/a" : liveB.status()) + " saw-KILLED-row="
                + sawKilledRow + " row-gone=" + killRowGone + " reason-kept=" + killReasonKept
                + ") interrupt=" + interrupted + " detail=" + detailOk + " (frames=" + frames
                + " stacktrace=" + traceItems + ")");
        String stats = win[0].host() == null ? "n/a" : win[0].host().statsLine();
        System.out.println("  host-stats=" + stats);
        report(ok, ok ? null : "see the detail lines", project, model, log, pings.get(),
                maxPing.get(), stallsOver16.get(), stallsOver250.get());
        SwingUtilities.invokeAndWait(session::shutdown);
        System.exit(ok ? 0 : 1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * The Tasks tab's pane, reached the way a user reaches it: by selecting the tab and asking the
     * window what that tab shows. No accessor was added for the harness.
     */
    private static TaskPane taskPane(MainWindow w) throws Exception {
        TaskPane[] out = new TaskPane[1];
        SwingUtilities.invokeAndWait(() -> {
            if (w.selectSideTab("Tasks") && w.selectedSideComponent() instanceof TaskPane p) {
                out[0] = p;
            }
        });
        return out[0];
    }

    /**
     * What one row says about one task, for the status trace: its status, its error when it has one,
     * and {@code gone} once the engine deregistered it — the three shapes a failed parallel run can
     * end in, which the failure line then has to tell apart.
     */
    private static String cellOf(List<NaruSessionModel.TaskRow> rows, long id) {
        for (NaruSessionModel.TaskRow r : rows) {
            if (r.id() == id) {
                return (r.status() == null ? "?" : r.status().name())
                        + (r.error() == null ? "" : "{" + oneLine(r.error(), 50) + "}");
            }
        }
        return "gone";
    }

    /**
     * What the engine says about a task the monitor can no longer draw: its end status, its error
     * and the last message it produced. A task that finishes inside one poll tick leaves the monitor
     * with nothing to show, and the handle is the only thing that outlives the registration — this
     * is how "ran and finished" is told apart from "refused to start" in the failure report.
     */
    private static String diagnosis(NaruTask t) {
        if (t == null) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder("#").append(t.id()).append(' ').append(t.status());
        String err = t.error() == null ? null : t.error().orNull();
        if (err != null) {
            sb.append(" err=").append(oneLine(err, 90));
        }
        try {
            List<NaruMessage> h = t.history();
            if (h != null && !h.isEmpty()) {
                NaruMessage m = h.get(h.size() - 1);
                sb.append(" last=").append(m.getRole()).append(':')
                        .append(oneLine(String.valueOf(m.getContent()), 90));
            }
        } catch (RuntimeException ignored) {
            // the diagnosis must not be the thing that breaks the run
        }
        return sb.toString();
    }

    /**
     * The stalls over 250 ms, as seconds into the run. Two same-sized freezes in different hops are
     * different defects, and the count alone cannot tell them apart.
     */
    private static String stallTimes() {
        if (stallAtMillis.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" stall-at-seconds[");
        synchronized (stallAtMillis) {
            for (int i = 0; i < stallAtMillis.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append((stallAtMillis.get(i) - runStart) / 1000);
            }
        }
        return sb.append(']').toString();
    }

    /** The action a button performs, through the same model call the button makes. */
    private static String action(TaskPane pane, NaruSessionModel s, long id,
                                 NaruSessionModel.TaskAction a) throws Exception {
        return onEdtReturn(() -> {
            pane.selectTask(id);
            return s.taskAction(id, a);
        });
    }

    /**
     * Press the pane's real button for a task: select the row first, then {@code doClick()} the
     * button whose text matches. This is what the unit test cannot do, and what §6 asks for — a
     * monitor whose interrupt/kill/hold are reachable by clicking.
     */
    private static boolean click(TaskPane pane, long id, String buttonText) throws Exception {
        return onEdtReturn(() -> {
            if (!pane.selectTask(id)) {
                return false;
            }
            JButton b = findButton(pane, buttonText);
            if (b == null || !b.isEnabled()) {
                return false;
            }
            b.doClick();
            return true;
        });
    }

    private static JButton findButton(Container c, String text) {
        for (Component x : c.getComponents()) {
            if (x instanceof JButton b && text.equals(b.getText())) {
                return b;
            }
            if (x instanceof Container inner) {
                JButton found = findButton(inner, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Press the hold button, whatever word it currently carries — it is one control for both
     * directions, so the harness must not assume which of the two it is about to click.
     */
    private static boolean clickHold(TaskPane pane, long id) throws Exception {
        return onEdtReturn(() -> {
            if (!pane.selectTask(id)) {
                return false;
            }
            JButton b = findHoldButton(pane);
            if (b == null || !b.isEnabled()) {
                return false;
            }
            b.doClick();
            return true;
        });
    }

    private static JButton findHoldButton(Container c) {
        for (Component x : c.getComponents()) {
            if (x instanceof JButton b
                    && ("Hold".equals(b.getText()) || "Resume".equals(b.getText()))) {
                return b;
            }
            if (x instanceof Container inner) {
                JButton found = findHoldButton(inner);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * The hold button's word and its enabled state for one task. Read in its own EDT step, after the
     * click's event has had a chance to run — {@code doClick} queues, so a read in the same body
     * would report the state from before the press.
     */
    private static String holdState(TaskPane pane, long id) throws Exception {
        return onEdtReturn(() -> pane.selectTask(id) ? pane.holdButtonState() : "(no row)");
    }

    /** Wait for a {@code hold()} / {@code unhold()} call to reach the engine's flag. */
    private static boolean awaitHeld(NaruTask t, boolean want, long millis) throws Exception {
        if (t == null) {
            return false;
        }
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            try {
                if (t.isHeld() == want) {
                    return true;
                }
            } catch (RuntimeException e) {
                return false;
            }
            Thread.sleep(120);
        }
        return false;
    }

    /** The labels the renderer produces, read on the EDT. */
    private static List<String> labelsOf(TaskPane pane) throws Exception {
        return onEdtReturn(() -> {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < pane.taskCount(); i++) {
                out.add(pane.rowLabelAt(i));
            }
            return out;
        });
    }

    private static List<NaruSessionModel.TaskRow> snapshot(NaruSessionModel s) throws Exception {
        return onEdtReturn(s::tasks);
    }

    private static String label(TaskPane pane, int i) throws Exception {
        if (i < 0) {
            return "";
        }
        List<String> ls = labelsOf(pane);
        return i < ls.size() ? ls.get(i) : "";
    }

    private static String monitorNote(TaskPane pane) throws Exception {
        return onEdtReturn(pane::monitorNote);
    }

    private static String actionNote(TaskPane pane) throws Exception {
        return onEdtReturn(pane::actionNote);
    }

    /** Wait until the pane draws at least {@code n} rows. */
    private static List<String> awaitRows(TaskPane pane, int n, long millis) throws Exception {
        long until = System.currentTimeMillis() + millis;
        List<String> ls = List.of();
        while (System.currentTimeMillis() < until) {
            ls = labelsOf(pane);
            if (ls.size() >= n) {
                return ls;
            }
            Thread.sleep(150);
        }
        return ls;
    }

    /**
     * Wait until the row for {@code id} shows, or drops, one of the terminal's flag letters. The
     * flags are the two letters {@code TaskText} can answer from a snapshot: {@code H} held and
     * {@code F} foreground.
     */
    private static boolean awaitFlag(TaskPane pane, long id, char flag, boolean want, long millis)
            throws Exception {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            List<String> ls = labelsOf(pane);
            int i = indexOf(ls, id);
            if (i >= 0 && flagIn(ls.get(i), flag) == want) {
                return true;
            }
            Thread.sleep(120);
        }
        return false;
    }

    /** Is this letter inside the row's {@code [..]} flag group? */
    private static boolean flagIn(String label, char flag) {
        int open = label.indexOf('[');
        int close = label.indexOf(']', open + 1);
        return open >= 0 && close > open && label.substring(open + 1, close).indexOf(flag) >= 0;
    }

    /** Wait until the row for {@code id} shows that status. */
    private static boolean awaitStatus(TaskPane pane, long id, NaruTaskStatus want, long millis)
            throws Exception {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            List<String> ls = labelsOf(pane);
            int i = indexOf(ls, id);
            if (i >= 0 && ls.get(i).contains(" " + want.name() + " ")) {
                return true;
            }
            Thread.sleep(120);
        }
        return false;
    }

    /**
     * Wait until no row carries {@code id}. A terminal task is deregistered by the engine the moment
     * it ends, so this — not a permanent brown row — is what the monitor owes the user after a kill.
     */
    private static boolean awaitRowGone(TaskPane pane, long id, long millis) throws Exception {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            if (indexOf(labelsOf(pane), id) < 0) {
                return true;
            }
            Thread.sleep(120);
        }
        return false;
    }

    /** The status the engine reports once the click's queue entry has run; the last one seen. */
    private static NaruTaskStatus awaitEngineStatus(NaruTask t, NaruTaskStatus want, long millis)
            throws Exception {
        long until = System.currentTimeMillis() + millis;
        NaruTaskStatus last = null;
        while (System.currentTimeMillis() < until && last != want) {
            try {
                last = t.status();
            } catch (RuntimeException e) {
                last = null;
            }
            if (last == want) {
                return last;
            }
            Thread.sleep(120);
        }
        return last;
    }

    /** The reason the desktop passed to {@code cancel()}, as the killed task reports it. */
    private static String awaitReason(NaruTask t, String needle, long millis) throws Exception {
        long until = System.currentTimeMillis() + millis;
        String reason = "";
        while (System.currentTimeMillis() < until) {
            String raw;
            try {
                raw = String.valueOf(t.error().orNull());
            } catch (RuntimeException e) {
                // a task still settling can throw here; the harness reports it rather than dying on it
                raw = "error() threw " + e;
            }
            reason = oneLine(raw, 120);
            if (reason.contains(needle)) {
                return reason;
            }
            Thread.sleep(120);
        }
        return reason;
    }

    /**
     * The row that <i>is</i> this task. A child's label also carries {@code ←#parent}, so a plain
     * substring search would answer "found" for a row that is merely pointing at the id — which is
     * how a killed parent looked like it never left the monitor.
     */
    private static int indexOf(List<String> labels, long id) {
        String key = "#" + id + " ";
        for (int i = 0; i < labels.size(); i++) {
            String head = labels.get(i).trim();
            if (head.startsWith("└")) {
                head = head.substring(1).trim();
            }
            if (head.startsWith(key)) {
                return i;
            }
        }
        return -1;
    }

    private static void submit(NaruSessionModel s, String line) throws Exception {
        SwingUtilities.invokeAndWait(() -> s.submit(line));
    }

    private static NaruSessionModel awaitSession(MainWindow w, int seconds) throws Exception {
        for (int i = 0; i < seconds * 4; i++) {
            NaruSessionModel s = w.session();
            if (s != null && s.isRunning() && s.session() != null) {
                return s;
            }
            Thread.sleep(250);
        }
        return w.session();
    }

    private static NaruInputRequest awaitNewRequest(NaruSessionModel s, long[] seen, long deadline)
            throws Exception {
        while (System.currentTimeMillis() < deadline) {
            NaruInputRequest[] now = new NaruInputRequest[1];
            long[] count = new long[1];
            SwingUtilities.invokeAndWait(() -> {
                now[0] = s.pendingRequest();
                count[0] = s.inputRequestsSeen();
            });
            if (now[0] != null && count[0] > seen[0]) {
                seen[0] = count[0];
                return now[0];
            }
            Thread.sleep(250);
        }
        return null;
    }

    /** Wait until the transcript stops growing, so /model use has taken effect before tasks start. */
    private static void awaitQuiet(NaruSessionModel s, long deadline, List<String> log)
            throws Exception {
        int last = -1;
        long quietSince = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            int total = onEdtReturn(() -> s.transcript().totalBlocks());
            if (total != last) {
                last = total;
                quietSince = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - quietSince > 4000) {
                return;
            }
            Thread.sleep(500);
        }
        log.add("note: the transcript never went quiet before the deadline");
    }

    /** The monitor's own report of every task, so a FAIL can be read rather than re-run. */
    private static void dump(NaruSessionModel s, NPath file) {
        try {
            StringBuilder sb = new StringBuilder();
            List<NaruSessionModel.TaskRow> rows = onEdtReturn(s::tasks);
            sb.append("rows=").append(rows.size()).append('\n');
            for (NaruSessionModel.TaskRow r : rows) {
                sb.append(net.thevpc.naru.desktop.util.TaskText.rowLabel(r)).append('\n');
            }
            file.writeString(sb.toString());
            System.out.println("  task-report=" + file);
        } catch (Exception e) {
            System.out.println("  task-report failed: " + e);
        }
    }

    private static File capture(MainWindow w) {
        try {
            BufferedImage img = new Robot().createScreenCapture(new Rectangle(1400, 900));
            File f = new File(System.getProperty("java.io.tmpdir"), "naru-task-smoke.png");
            ImageIO.write(img, "png", f);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Paint the pane itself, off-screen and at its own size — the full-screen capture cuts the side
     * panel on a 1400-pixel display, and the panel is the thing under test.
     */
    private static File capturePane(Component c, String name) {
        if (c == null || c.getWidth() <= 0 || c.getHeight() <= 0) {
            return null;
        }
        try {
            BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics g = img.createGraphics();
            try {
                c.paint(g);
            } finally {
                g.dispose();
            }
            File f = new File(System.getProperty("java.io.tmpdir"), name + ".png");
            ImageIO.write(img, "png", f);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private static void fail(NPath project, String model, List<String> log, AtomicInteger pings,
                             AtomicLong maxPing, AtomicInteger over16, AtomicInteger over250,
                             String hop) {
        log.add("FAILED HOP: " + hop);
        report(false, hop, project, model, log, pings.get(), maxPing.get(), over16.get(),
                over250.get());
        postMortem();
        if (live != null) {
            try {
                SwingUtilities.invokeAndWait(live::shutdown);
            } catch (Exception ignored) {
                // the JVM is exiting anyway; a stuck session is not worth a second failure line
            }
        }
        System.exit(1);
    }

    private static void report(boolean ok, String detail, NPath project, String model,
                               List<String> log, int pings, long maxPing, int over16, int over250) {
        System.out.println("SMOKE tasks");
        System.out.println("  project=" + project);
        System.out.println("  model=" + model);
        System.out.println("  edt-pings=" + pings + " max-ping-ms=" + maxPing
                + " stalls-over-16ms=" + over16 + " stalls-over-250ms=" + over250
                + stallTimes());
        synchronized (edtStacks) {
            for (String s : edtStacks) {
                System.out.println("  edt-busy:" + s);
            }
        }
        for (String l : log) {
            System.out.println("  " + l);
        }
        if (detail != null) {
            System.out.println("  failure: " + detail);
        }
        System.out.println("  VERDICT=" + (ok
                ? "PASS — spawn tree, hold/resume, live parallel tasks, kill/interrupt, frames"
                : "FAIL — see the detail above"));
    }

    private static void postMortem() {
        NaruSessionModel s = live;
        if (s == null) {
            return;
        }
        try {
            SwingUtilities.invokeAndWait(() -> {
                List<NaruSessionModel.TaskRow> rows = s.tasks();
                System.out.println("  post-mortem: task rows=" + rows.size()
                        + " transcript blocks=" + s.transcript().totalBlocks()
                        + " parked=" + (s.pendingRequest() != null));
                for (NaruSessionModel.TaskRow r : rows) {
                    System.out.println("    " + oneLine(
                            net.thevpc.naru.desktop.util.TaskText.rowLabel(r), 160));
                }
            });
            MainWindow w = liveWin;
            TaskPane p = livePane;
            if (w != null && p != null) {
                SwingUtilities.invokeAndWait(() -> w.selectSideTab("Tasks"));
                System.out.println("  post-mortem task-pane=" + capturePane(p, "naru-task-failure"));
            }
        } catch (Exception e) {
            System.out.println("  post-mortem failed: " + e);
        }
    }

    @FunctionalInterface
    interface Edt<T> {
        T get() throws Exception;
    }

    private static <T> T onEdtReturn(Edt<T> body) throws Exception {
        Object[] box = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                box[0] = body.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        @SuppressWarnings("unchecked")
        T value = (T) box[0];
        return value;
    }

    private static String oneLine(String s, int max) {
        String x = String.valueOf(s).replace('\n', ' ').replace('\r', ' ').trim();
        return x.length() > max ? x.substring(0, max) + "…" : x;
    }
}
