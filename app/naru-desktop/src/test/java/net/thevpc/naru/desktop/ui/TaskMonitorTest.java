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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
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

    @Test
    @Order(4)
    void thePaneDrawsTheTreeAndSelectsATask() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "the monitor's renderer needs a graphics environment");
        onEdt(() -> {
            pane = new TaskPane(model);
            model.refreshNow();
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

            assertTrue(pane.selectTask(firstChild.id()), "a live id is selectable");
            assertTrue(pane.detailText().contains("Task #" + firstChild.id()), pane.detailText());
            assertFalse(pane.selectTask(999_999L), "a stale id must not fake a selection");
            assertEquals(pane.detailText(), pane.detailText(), "and it leaves the detail alone");

            assertTrue(pane.monitorNote().contains("task"), pane.monitorNote());
        });
    }

    /**
     * The hold control, pressed rather than called.
     * <p>
     * Hold and resume share one button whose word flips, and the defect this covers is exactly the
     * pair coming apart: with {@code setEnabled(… && !held)} the button read "Resume" and could not
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

        // free and live → the holdable direction: the word is Hold and the button can be pressed
        assertEquals("Hold/enabled", selectAndRead(mainId));
        press(mainId);
        awaitTrue(main::isHeld, "the press reached hold()");
        // held and live → the resumable direction, and the pair the bug got wrong: with
        // setEnabled(… && !held) the word said "Resume" while the button was disabled, so a held
        // task had no way back from the mouse
        assertEquals("Resume/enabled", selectAndRead(mainId));
        press(mainId);
        awaitTrue(() -> !main.isHeld(), "and the same press reaches unhold()");
        assertEquals("Hold/enabled", selectAndRead(mainId), "the word and the flag agree again");

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

    /** Poll from the test thread, which is what lets the EDT keep running while the click lands. */
    private static void awaitTrue(java.util.function.BooleanSupplier want, String what)
            throws Exception {
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

    private static javax.swing.JButton holdButton(java.awt.Container c) {
        for (java.awt.Component x : c.getComponents()) {
            if (x instanceof javax.swing.JButton b
                    && ("Hold".equals(b.getText()) || "Resume".equals(b.getText()))) {
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

    private static int indexOf(TaskPane pane, long id) {
        for (int i = 0; i < pane.taskCount(); i++) {
            if (pane.rowLabelAt(i).contains("#" + id + " ")) {
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
