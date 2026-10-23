package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspaceBuilder;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.desktop.host.InteractionHost;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.model.NaruSessionModel.TaskAction;
import net.thevpc.naru.desktop.model.NaruSessionModel.TaskRow;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The task monitor on a real session: a spawn tree, and interrupt / kill / hold actually reaching
 * the scheduler.
 * <p>
 * Plan §6 promises "live parallel tasks with interrupt/kill/hold", and the pieces are only provable
 * together: the tree needs a parent that the engine assigned, and an action is only worth a button
 * if the task it pressed on changes state. So this boots the desktop's own path (the same
 * {@link NaruSessionModel} the window builds, on the same workspace factory) rather than mocking a
 * session — a mock would prove the pane talks to an interface, which is not the risk here.
 * <p>
 * Measured before writing this: {@code session.newTask(...)} parks every task held and READY, so a
 * spawned probe task cannot race the scheduler out of existence between the poll and the assertion.
 * That is also what makes the kill case trustworthy — the task was definitely still alive when the
 * button's engine call ran.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TaskMonitorTest {

    private static NaruSessionModel model;
    private static InteractionHost host;
    private static TaskPane pane;
    private static NaruTask parent;
    private static NaruTask firstChild;
    private static NaruTask secondChild;
    private static NaruTask resumeChild;

    @BeforeAll
    static void requireNuts() {
        Nuts.require();
    }

    private static void onEdt(ThrowingRunnable body) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                body.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    @Order(1)
    void bootASessionAndSpawnTwoChildren() throws Exception {
        NPath dir = NPath.ofTempFolder("naru-desktop-task-monitor");
        onEdt(() -> {
            host = new InteractionHost();
            host.startFlushTimer();
            model = new NaruSessionModel(
                    () -> NaruWorkspaceBuilder.of().projectDirectory(dir).build(), dir, host);
            model.start();
        });
        long deadline = System.currentTimeMillis() + 180_000;
        while (model.session() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        NaruSession session = model.session();
        assertNotNull(session, "the desktop session never booted; status was " + model.statusText());

        // engine calls from the test thread, never from the EDT — the same rule the widgets follow
        parent = session.newTask(NaruTaskSpec.of().name("probe-parent").statements("/print p"));
        firstChild = session.newTask(NaruTaskSpec.of().name("probe-child-1")
                .parentId(parent.id()).statements("/print c1"));
        secondChild = session.newTask(NaruTaskSpec.of().name("probe-child-2")
                .parentId(parent.id()).statements("/print c2"));
        // a fourth task, dedicated to the action that lets a task run: an unheld task is a task the
        // scheduler may finish and deregister, so nothing else in this file is allowed to depend on
        // it still being there
        resumeChild = session.newTask(NaruTaskSpec.of().name("probe-child-3")
                .parentId(parent.id()).statements("/print c3"));
        assertTrue(firstChild.isHeld() && secondChild.isHeld() && parent.isHeld(),
                "premise: a fresh task is parked held, so nothing below races the scheduler");
    }

    @Test
    @Order(2)
    void thePollShowsRealParentsAndASpawnTree() throws Exception {
        onEdt(model::refreshNow);
        List<TaskRow> rows = model.tasks();
        TaskRow p = rowOf(rows, parent.id());
        TaskRow c1 = rowOf(rows, firstChild.id());
        TaskRow c2 = rowOf(rows, secondChild.id());

        assertTrue(p.parentId() <= 0, "a root task has no parent to draw: " + p);
        assertEquals(parent.id(), c1.parentId(), "parentId is engine state, not the -1 stub it was");
        assertEquals(parent.id(), c2.parentId());
        assertEquals(0, p.depth());
        assertEquals(1, c1.depth());
        assertEquals(1, c2.depth());
        int ip = rows.indexOf(p);
        assertEquals(ip + 1, rows.indexOf(c1), "a spawn is drawn directly under the task that made it");
        assertEquals(ip + 2, rows.indexOf(c2));
        assertTrue(c1.held(), "the row carries the held flag the monitor needs to label its button");

        // the detail is what /task frames prints, read once for the task on screen
        NaruSessionModel.TaskDetail d = onEdtReturn(() -> model.taskDetail(firstChild.id()));
        assertNotNull(d, "a live task has a detail");
        assertEquals(firstChild.id(), d.id());
        assertEquals(parent.id(), d.parentId());
        assertEquals("probe-child-1", d.name());
        assertNotNull(d.frames(), "frames are always a list, even when the task has none");
        assertNull(onEdtReturn(() -> model.taskDetail(999_999L)),
                "a task that never existed has no detail to invent");
    }

    @Test
    @Order(3)
    void holdResumeInterruptAndKillGoThrough() throws Exception {
        // hold is already on from the probe; the monitor's Resume is what this first exercises, on
        // the task that is allowed to disappear afterwards
        assertNull(onEdtReturn(() -> model.taskAction(resumeChild.id(), TaskAction.RESUME)));
        assertFalse(resumeChild.isHeld(), "resume reached the scheduler");

        assertNull(onEdtReturn(() -> model.taskAction(secondChild.id(), TaskAction.HOLD)));
        assertTrue(secondChild.isHeld(), "hold reached the scheduler");

        assertNull(onEdtReturn(() -> model.taskAction(secondChild.id(), TaskAction.INTERRUPT)));
        assertTrue(secondChild.isInterruptRequested(),
                "interrupt is the soft flag: requested, and the task is still live");
        assertTrue(secondChild.isHeld(), "interrupting must not terminalise a task");

        String failure = onEdtReturn(() -> model.taskAction(secondChild.id(), TaskAction.KILL));
        assertNull(failure, failure);
        assertEquals(net.thevpc.naru.api.scheduler.NaruTaskStatus.KILLED, secondChild.status(),
                "kill is the terminal one, and the row shows why");
        assertTrue(secondChild.error().orNull() != null
                        && secondChild.error().orNull().contains("task monitor"),
                "a killed task says who stopped it: " + secondChild.error().orNull());

        String missing = onEdtReturn(() -> model.taskAction(999_999L, TaskAction.KILL));
        assertNotNull(missing, "pressing Kill on a task that is gone must say so, not pretend");
        assertTrue(missing.contains("999999"), missing);
    }

    /**
     * The monitor's two views, and the tree the second one draws.
     * <p>
     * The rail is deliberately narrow, so the owner's default is the interactive filter: a session
     * that spawned six routine tasks would otherwise bury the task the user is talking to. The cost
     * is that the spawn tree is now a second click away, which makes the filter itself part of §6's
     * claim — a monitor that could not reach the tree would not "show live parallel tasks". Both
     * ways into it are exercised here: the toggle, and the jump-from-another-pane path
     * ({@code selectTask}), which opens the tree by itself rather than reporting no such row.
     */
    @Test
    @Order(4)
    void thePaneFiltersToInteractiveTasksAndOpensTheTree() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "the monitor's renderer needs a graphics environment");
        // what the selection listener is for: the window wires the transcript, the tools and the
        // input area to it, so a row that never reports its id leaves those panes on another task
        List<Long> selections = new ArrayList<>();
        onEdt(() -> {
            pane = new TaskPane(model);
            pane.setTaskSelectionListener(selections::add);
            model.refreshNow();
        });

        int interactiveRows = onEdtReturn(pane::taskCount);
        List<String> interactiveLabels = onEdtReturn(() -> {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < pane.taskCount(); i++) {
                out.add(pane.rowLabelAt(i));
            }
            return out;
        });
        // the filter's own contract, stated against the engine rather than against an assumption
        // about which probes are conversations: the rail shows exactly the rows the model marks
        // conversation (plus a parked question, asserted below). {@code isConversation()} is the
        // monotonic "has ever entered INTERACTIVE mode" flag, so which probes carry it is the
        // engine's business — deriving the expected set from the rows instead of hard-coding ids is
        // what keeps this honest if the promotion rule ever changes.
        List<String> expected = new ArrayList<>();
        List<String> hidden = new ArrayList<>();
        for (TaskRow r : model.tasks()) {
            String head = "#" + r.id() + " ";
            if (r.conversation()) {
                expected.add(head);
            } else {
                hidden.add(head);
            }
        }
        assertFalse(hidden.isEmpty(), "premise: the session has a non-interactive task to filter"
                + " out, or this test proves nothing: " + model.tasks().size() + " rows");
        for (String head : expected) {
            assertTrue(containsHead(interactiveLabels, head),
                    "an interactive task belongs in the default view, " + head + " was missing from "
                            + interactiveLabels);
        }
        for (String head : hidden) {
            assertFalse(containsHead(interactiveLabels, head),
                    "a spawned job is not an interactive task, so the default view has to leave out"
                            + " " + head + ": " + interactiveRows + " rows, they were "
                            + interactiveLabels);
        }

        // …unless it is sitting on a question. requestInput does not require INTERACTIVE mode and
        // pending input registers off the status, so a BATCH job can be the one thing waiting for
        // the user — and the composer only answers the *selected* pending task, so a row hidden by
        // the filter would be a question with no way to answer it.
        TaskRow parked = null;
        for (TaskRow r : model.tasks()) {
            if (!r.conversation()) {
                parked = r;
                break;
            }
        }
        assertNotNull(parked, "premise: one non-conversation job to park");
        final long parkedId = parked.id();
        NaruTask parkedTask = model.session().findTask(parkedId).orNull();
        assertNotNull(parkedTask, "the job the filter hid is still a live task");
        parkedTask.requestInput(NMsg.ofC("which of the two? (parked from a batch job)"));
        awaitTrue(() -> {
            TaskRow now = rowOf(model.tasks(), parkedId);
            return now != null && now.status() == NaruTaskStatus.BLOCKED_ON_INPUT;
        }, "the engine settled the job into BLOCKED_ON_INPUT");
        List<String> afterPark = onEdtReturn(() -> {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < pane.taskCount(); i++) {
                out.add(pane.rowLabelAt(i));
            }
            return out;
        });
        String parkedHead = "#" + parked.id() + " ";
        assertTrue(containsHead(afterPark, parkedHead),
                "a parked question must be reachable in the default view: " + afterPark);
        for (String head : hidden) {
            if (head.equals(parkedHead)) {
                continue;
            }
            assertFalse(containsHead(afterPark, head),
                    "the exception is a parked question, not the whole tree: " + head + " stayed"
                            + " hidden in " + afterPark);
        }

        // the user's way back to the tree: press the toggle, and wait for the poll to redraw
        onEdt(TaskMonitorTest::pressAllTasks);
        awaitTrue(() -> onEdtReturnCount() >= 3, "the full tree appeared after All tasks");
        onEdt(() -> {
            assertTrue(pane.taskCount() >= 3,
                    "the parent and both children must be on screen: " + pane.taskCount());
            int parentAt = indexOf(pane, parent.id());
            assertTrue(parentAt >= 0, "the parent row exists");
            assertFalse(pane.rowLabelAt(parentAt).startsWith("   "),
                    "a root is not indented: " + pane.rowLabelAt(parentAt));
            assertTrue(pane.rowLabelAt(parentAt + 1).startsWith("   └"),
                    "the child row is drawn nested, in the tree order the model arranged: "
                            + pane.rowLabelAt(parentAt + 1));
            assertTrue(pane.rowLabelAt(parentAt + 1).contains("probe-child-1"),
                    pane.rowLabelAt(parentAt + 1));
        });

        // selecting by id is the path another pane takes, and the tree has to open for it
        onEdt(() -> assertTrue(pane.selectTask(firstChild.id()), "a live id is selectable"));
        // the frames are fetched off the EDT, so the detail lands a turn after the selection
        awaitTrue(() -> detailTextNow().contains("probe-child-1"),
                "the detail pane filled in for the task that was selected");
        String shown = onEdtReturn(() -> pane.detailText());
        onEdt(() -> {
            assertFalse(pane.selectTask(999_999L), "a stale id must not fake a selection");
            assertTrue(pane.monitorNote().contains("task"), pane.monitorNote());
        });
        assertEquals(shown, onEdtReturn(() -> pane.detailText()),
                "and a rejected selection leaves the detail alone");

        assertTrue(selections.contains(firstChild.id()),
                "the window's panes were told which task is on screen: " + selections);
    }

    /** Read the row count on the EDT, from the polling thread. */
    private static int onEdtReturnCount() {
        try {
            return onEdtReturn(pane::taskCount);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The hold control, pressed rather than called.
     * <p>
     * Hold and resume share one button whose glyph flips, and the defect this covers is exactly the
     * pair coming apart: with {@code setEnabled(… && !held)} the button said "Resume" and could not
     * be pressed, so a held task had no way back from the mouse. A test that goes through
     * {@code taskAction} cannot see it — only the widget has a label and an enabled flag at the same
     * time. Found by the §6 gate run, pinned here so it stays found.
     */
    @Test
    @Order(5)
    void theHoldButtonIsPressableInBothDirections() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "the monitor's buttons need a graphics environment");
        assertNotNull(pane, "the pane test above created it");
        // the instrument is the session's own foreground task: it is the one live task in a session
        // that is never going to deregister under the test. A spawned probe task with statements in
        // it runs the moment it is un-held and is gone before the second half
        NaruSession s = onEdtReturn(model::session);
        long mainId = s.foregroundTaskId();
        NaruTask main = s.findTask(mainId).orNull();
        assertNotNull(main, "the session's own task is on the scheduler");
        assertFalse(main.isHeld(), "premise: the foreground task runs free");

        // free and live → the holdable direction: the pause glyph, and the button can be pressed
        assertEquals("Ⅱ/enabled", selectAndRead(mainId));
        press(mainId);
        awaitTrue(main::isHeld, "the press reached hold()");
        // held and live → the resumable direction, and the pair the bug got wrong: with
        // setEnabled(… && !held) the button said "Resume" while it was disabled, so a held
        // task had no way back from the mouse
        assertEquals("▶/enabled", selectAndRead(mainId));
        press(mainId);
        awaitTrue(() -> !main.isHeld(), "and the same press reaches unhold()");
        assertEquals("Ⅱ/enabled", selectAndRead(mainId), "the glyph and the flag agree again");

        // A task that can no longer answer must not offer a pressable action. Which of the two the
        // monitor shows depends on the engine: a killed task is deregistered, and the row leaves as
        // soon as the poll notices — measured both ways, once as a KILLED row that stayed a few
        // seconds and once as a row gone in under a second, depending on what the scheduler had
        // already swept. Either is correct; a pressable Hold on a dead task would not be.
        assertEquals(NaruTaskStatus.KILLED, secondChild.status(), "premise: killed in test 3");
        String state = selectAndRead(secondChild.id());
        assertTrue(state.equals("(no row #" + secondChild.id() + ")") || state.endsWith("/disabled"),
                "a terminal task is either gone from the monitor or drawn with its buttons off: "
                        + state);
        onEdt(pane::dispose);
    }

    /**
     * The detail pane must not wait on the engine's model catalogue.
     * <p>
     * Found by the §6 gate run, and the numbers are the reason it is pinned here rather than
     * argued: the monitor's own poll measured 2 ms, while the window froze for 8.7 s at a time with
     * the EDT parked in an HTTPS read. The stack named the path — {@code TaskPane.showSelected} →
     * {@code taskDetail} → {@code NaruTask.model()}, which for a hinted model asks every provider
     * what it has, including the one that answers over the network. The read now happens on a worker
     * and is asked once per task, so this test's premise is the caller's clock: the first detail read
     * returns immediately, and the label settles shortly after on its own.
     */
    @Test
    @Order(6)
    void theDetailReadNeverWaitsForTheModelCatalogue() throws Exception {
        long id = parent.id();
        long t0 = System.currentTimeMillis();
        NaruSessionModel.TaskDetail d = onEdtReturn(() -> model.taskDetail(id));
        long callerMillis = System.currentTimeMillis() - t0;
        assertNotNull(d, "a live task always has a detail");
        assertTrue(callerMillis < 1000,
                "the first read returned after " + callerMillis + "ms; it is not allowed to wait on"
                        + " a provider answer at all");
        // whichever way the resolution goes, it goes: pending is a state the pane waits out, not
        // one it is stuck in, and the answer replaces the marker without another click
        long settle = System.currentTimeMillis() + 20_000;
        while (model.isTaskModelLabelPending(id) && System.currentTimeMillis() < settle) {
            Thread.sleep(50);
        }
        assertFalse(model.isTaskModelLabelPending(id), "the label worker never answered for #" + id);
        String label = onEdtReturn(() -> model.taskDetail(id)).model();
        assertTrue(label == null || !label.contains("resolving"),
                "the detail line settled on something the user can read: " + label);
    }

    /**
     * Closing a session must not throw on the EDT through the panes it wired together.
     * <p>
     * The §6 gate run ended this way — not with a failed claim, but with an
     * {@code IllegalStateException: session is stopped} on the EDT after its own verdict line. The
     * path is the window's: {@code shutdown()} fires one last poll refresh so the monitor shows the
     * state at close, clearing its rows, which fires a selection change, which the window forwards to
     * the inspector, which then asked a stopped session for its project directory. The fix is in the
     * model — {@code session()} answers null once closed, and every pane already draws a "no session"
     * text — so this test reproduces the wiring rather than the exception type.
     */
    @Test
    @Order(7)
    void closingTheSessionLeaksNoEngineCallIntoThePanes() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "the panes under test need a graphics environment");
        onEdt(() -> {
            TaskPane closing = new TaskPane(model);
            net.thevpc.naru.desktop.ui.InspectorPane insp =
                    new net.thevpc.naru.desktop.ui.InspectorPane(model);
            net.thevpc.naru.desktop.ui.WorkbenchPane wb =
                    new net.thevpc.naru.desktop.ui.WorkbenchPane(model);
            // the same three-way wiring MainWindow.activateSession installs
            closing.setTaskSelectionListener(id -> {
                insp.setSelectedTaskId(id);
                wb.setSelectedTaskId(id);
            });
            closing.selectTask(parent.id());
            // no try/catch: an engine call that survives the close throws out of this EDT step and
            // fails the test, which is exactly what the gate run saw
            model.shutdown();
            assertNull(model.session(), "a closed session has no engine left to inspect");
            insp.dispose();
            closing.dispose();
        });
    }

    /** Select a row and read the hold button as the user sees it, in one EDT step. */
    private static String selectAndRead(long id) throws Exception {
        return onEdtReturn(() -> pane.selectTask(id)
                ? pane.holdButtonState() : "(no row #" + id + ")");
    }

    /**
     * Press the hold button in its own EDT step. The click and the read cannot share one step:
     * {@code doClick} lets its action event go in a later cycle, so a read in the same body reports
     * the button as it was before the press.
     */
    private static void press(long id) throws Exception {
        onEdt(() -> {
            pane.selectTask(id);
            pressHold();
        });
    }

    /** Read the detail pane from the test thread, so a poll can ask it between EDT turns. */
    private static String detailTextNow() {
        try {
            return onEdtReturn(pane::detailText);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Poll from the test thread, which is what lets the EDT keep running while the click lands. */
    private static void awaitTrue(java.util.function.BooleanSupplier want, String what)            throws Exception {
        long until = System.currentTimeMillis() + 3000;
        while (!want.getAsBoolean() && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        assertTrue(want.getAsBoolean(), what);
    }

    /** Click whichever word the hold button currently carries. */
    private static void pressHold() {
        javax.swing.JButton b = holdButton(pane);
        assertNotNull(b, "the monitor has a Hold/Resume button");
        assertTrue(b.isEnabled(), "the button reads " + b.getText() + " and must be pressable");
        b.doClick();
    }

    /** Click the view toggle, in its own EDT step like every other press in this file. */
    private static void pressAllTasks() {
        javax.swing.JToggleButton b = allTasksButton(pane);
        assertNotNull(b, "the monitor has an All tasks toggle");
        b.doClick();
    }

    /**
     * The rail's buttons carry a glyph and nothing else, so they are found by what the tooltip says
     * they do — which is also how a reader with no icon font finds them.
     */
    private static javax.swing.JToggleButton allTasksButton(java.awt.Container c) {
        for (java.awt.Component x : c.getComponents()) {
            if (x instanceof javax.swing.JToggleButton b && b.getAccessibleContext() != null
                    && "Show all tasks as a tree".equals(b.getAccessibleContext().getAccessibleName())) {
                return b;
            }
            if (x instanceof java.awt.Container inner) {
                javax.swing.JToggleButton found = allTasksButton(inner);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static javax.swing.JButton holdButton(java.awt.Container c) {
        for (java.awt.Component x : c.getComponents()) {
            if (x instanceof javax.swing.JButton b && b.getAccessibleContext() != null
                    && "Hold / resume selected task".equals(b.getAccessibleContext().getAccessibleName())) {
                return b;
            }
            if (x instanceof java.awt.Container inner) {
                javax.swing.JButton found = holdButton(inner);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Does any row label carry this {@code #id } head, once the tree gutter is trimmed? */
    private static boolean containsHead(List<String> labels, String head) {
        for (String l : labels) {
            if (l.replaceFirst("^[│ └]*", "").startsWith(head)) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(TaskPane pane, long id) {
        String want = "#" + id + " ";
        for (int i = 0; i < pane.taskCount(); i++) {
            // anchored on the head, with the tree gutter trimmed first: a child label carries its
            // parent as "←#2 ", and a substring match would find the parent's id on the child's row
            String head = pane.rowLabelAt(i).replaceFirst("^[│ └]*", "");
            if (head.startsWith(want)) {
                return i;
            }
        }
        return -1;
    }

    private static TaskRow rowOf(List<TaskRow> rows, long id) {
        for (TaskRow r : rows) {
            if (r.id() == id) {
                return r;
            }
        }
        throw new AssertionError("task #" + id + " is not in the monitor: " + rows);
    }

    @FunctionalInterface
    interface Edt<T> {
        T get() throws Exception;
    }

    private static <T> T onEdtReturn(Edt<T> body) throws Exception {
        Object[] box = new Object[1];
        onEdt(() -> box[0] = body.get());
        @SuppressWarnings("unchecked")
        T value = (T) box[0];
        return value;
    }

    @AfterAll
    static void shutDown() throws Exception {
        if (model != null) {
            // shutdown closes the host and stops the poll; it is the window's own teardown path
            onEdt(model::shutdown);
        }
    }
}
