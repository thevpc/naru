package net.thevpc.naru.desktop.smoke;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.desktop.MainWindow;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.ui.ComposerArea;
import net.thevpc.naru.desktop.ui.InputPane;
import net.thevpc.naru.desktop.ui.TaskPane;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.swing.NSwingUtils;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.swing.plaf.UIPlafManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The composer as the user actually drives it: the real {@code MainWindow}, the real Look and Feel,
 * {@code java.awt.Robot} keystrokes, and the engine's own completion behind
 * {@code NaruSessionModel.complete}.
 * <p>
 * Three claims are measured here, because a unit test cannot make any of them. That
 * {@code Ctrl+Space} survives the platform's own key handling and FlatLaf's bindings, rather than
 * only the bare {@code InputMap} the component test installs. That the engine's candidate set shows
 * up in the widget and its replacement span is honoured on Accept. And that the editor swap landed
 * in the panes the window builds, at the right size, without the EDT missing a frame on the way.
 * <p>
 * Input is parked through {@code NaruTask.requestInput} — the same call the agent's own question
 * makes — because §3.4 keeps the composer disabled until there is something to answer, and a
 * harness that typed into a disabled field would prove nothing about anything.
 * <pre>
 * usage: ComposerSmoke &lt;projectDir&gt;
 * </pre>
 */
public final class ComposerSmoke {

    private static final AtomicInteger pings = new AtomicInteger();
    private static final AtomicLong maxPing = new AtomicLong();
    private static final AtomicInteger stalls16 = new AtomicInteger();
    private static final List<String> log = new ArrayList<>();
    private static final List<String> failures = new ArrayList<>();

    private ComposerSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Nuts.require();
        NSwingUtils.prepareUI(new String[0]);
        UIPlafManager.getCurrentManager().apply("FlatLight");
        UIPlafManager.getCurrentManager().apply("FlatDark");
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("SMOKE composer\n  VERDICT=FAIL — this run needs a display (Robot)");
            System.exit(1);
        }
        NPath project = NPath.of(args.length > 0 ? args[0]
                : System.getProperty("java.io.tmpdir") + "/naru-composer-smoke").toAbsolute();
        project.mkdirs();
        startPinger();

        MainWindow[] win = new MainWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            MainWindow w = new MainWindow();
            w.setSize(1380, 860);
            w.setLocation(0, 0);
            w.setVisible(true);
            w.openProject(project);
            win[0] = w;
        });
        log.add("laf=" + UIManager.getLookAndFeel().getName() + "/"
                + UIManager.getLookAndFeel().getID());

        NaruSessionModel session = awaitSession(win[0], 90);
        if (session == null) {
            finish(false, project);
            System.exit(1);
        }
        log.add("tasks: " + describe(session));

        long parkedId = awaitParkedTask(session, 30);
        if (parkedId < 0) {
            parkedId = parkOne(session);
        }
        if (parkedId < 0) {
            finish(false, project);
            System.exit(1);
        }
        log.add("input parked on task #" + parkedId
                
                + ", composer enabled=" + onEdtReturn(() -> composerEnabled(win[0])));
        log.add("tasks now: " + describe(session));

        // the window's own selection sync should already have picked the parked task; saying it out
        // loud is what tells a reader whether §3.4's rule or this harness enabled the field
        final long parked = parkedId;
        onEdt(() -> {
            TaskPane rail = find(win[0], TaskPane.class);
            if (rail != null) {
                rail.selectTask(parked);
            }
        });
        Thread.sleep(400);
        if (!onEdtReturn(() -> composerEnabled(win[0]))) {
            fail("the composer stayed disabled with a parked request on the selected task");
        }

        Robot robot = new Robot();
        ComposerArea area = onEdtReturn(() -> find(win[0], ComposerArea.class));
        if (area == null) {
            finish(false, project);
            System.exit(1);
        }
        log.add("composer class=" + area.getClass().getSimpleName()
                + " rows=" + onEdtReturn(() -> area.getLineCount())
                + " wrap=" + onEdtReturn(area::getLineWrap));
        if (!awaitFocus(area, win[0], robot)) {
            fail("the composer never took focus, so nothing below is a keystroke test");
            finish(false, project);
            System.exit(1);
        }

        // hop 1 — the keystroke: Ctrl+Space must reach the widget through the native input path
        int stallsBefore = stalls16.get();
        type(robot, KeyEvent.VK_SLASH);
        for (int vk : new int[]{KeyEvent.VK_M, KeyEvent.VK_O, KeyEvent.VK_D}) {
            type(robot, vk);
        }
        long keyDown = System.nanoTime();
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_SPACE);
        robot.keyRelease(KeyEvent.VK_SPACE);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        boolean shown = await(10_000, () -> onEdtReturnBool(area::isCompletionVisible));
        long askToPaintMicros = (System.nanoTime() - keyDown) / 1_000;
        if (!shown) {
            fail("Ctrl+Space did not open the candidate list in the real window");
        }
        int count = onEdtReturn(area::completionCount);
        log.add("ctrl+space: " + count + " candidates, " + askToPaintMicros / 1000
                + "ms from the keystroke to the list being visible, engine work "
                + onEdtReturn(area::lastCompletionMicros) + "µs ("
                + onEdtReturn(area::lastCompletionCount) + " rows)");
        onEdt(() -> {
            for (int i = 0; i < Math.min(5, area.completionCount()); i++) {
                log.add("  candidate[" + i + "] = " + area.completionLabelAt(i));
            }
        });
        String host = onEdtReturn(area::completionHostWindow);
        log.add("popup: " + onEdtReturn(area::completionBounds) + " inside " + host
                + (host.equals("MainWindow") ? "" : " — NOT the main window, so the keyboard is at risk"));
        if (!host.equals("MainWindow")) {
            fail("the candidate list is drawn in a " + host + " instead of the main window");
        }
        // the claim the geometry fix is for: opening the list must not move the focus. A Robot key
        // goes to whichever native window holds input focus, so a heavyweight popup swallows every
        // keystroke after it and the accept never runs — which is what the first run measured.
        log.add("focus after ctrl+space: composer=" + onEdtReturnBool(area::isFocusOwner)
                + " global=" + onEdtReturn(ComposerSmoke::focusOwner));
        if (!onEdtReturnBool(area::isFocusOwner)) {
            fail("opening the candidate list took the keyboard away from the composer");
        }
        File open = capture(robot, "naru-composer-open");
        log.add("screenshot: " + open);

        // hop 2 — accept: the engine's span wins, not a guess about word boundaries
        String wanted = onEdtReturn(area::selectedCompletionInsert);
        int beforeLen = onEdtReturn(() -> area.getText().length());
        type(robot, KeyEvent.VK_ENTER);
        boolean applied = await(3_000, () -> onEdtReturnBool(() -> area.getText().equals(wanted)));
        log.add("accept: \"" + areaText(area) + "\" (was " + beforeLen + " chars, insert \""
                + wanted + "\")");
        if (!applied) {
            fail("Enter did not leave the highlighted candidate as the whole line");
        }
        if (onEdtReturn(area::isCompletionVisible)) {
            fail("the list stayed open after the candidate was taken");
        }
        File accepted = capture(robot, "naru-composer-accepted");
        log.add("screenshot: " + accepted);

        // hop 3 — the multiline half: Shift+Enter must be a newline, not a submission. Robot presses
        // key codes, so the modifier is VK_SHIFT here; a Swing modifier mask is not a key.
        type(robot, KeyEvent.VK_ENTER, KeyEvent.VK_SHIFT);
        type(robot, KeyEvent.VK_X);
        String drafted = wanted + "\nx";
        boolean twoLines = await(3_000, () -> onEdtReturnBool(() -> area.getText().equals(drafted)));
        log.add("shift+enter: line count " + onEdtReturn(area::getLineCount) + ", text \""
                + areaText(area) + "\"");
        if (!twoLines) {
            fail("Shift+Enter did not add a newline in the real window");
        }

        // hop 4 — and Enter with the list closed is still a send. The claim is that the draft reached
        // the request it was addressed to, not that the field emptied: the engine parks the next
        // readline cycle immediately, so the task's own status can still read BLOCKED_ON_INPUT.
        NaruInputRequest before = onEdtReturn(session::pendingRequest);
        String draft = onEdtReturn(area::getText);
        int stallsBeforeSend = stalls16.get();
        type(robot, KeyEvent.VK_ENTER);
        boolean answered = await(10_000, () -> onEdtReturnBool(
                () -> session.pendingRequest() != before));
        boolean emptied = await(3_000, () -> onEdtReturnBool(() -> area.getText().isEmpty()));
        NaruSessionModel.TaskRow row = rowFor(session, parked);
        log.add("send: \"" + draft.replace('\n', ' ') + "\" answered the request task #" + parked
                + " was waiting on (" + (answered ? "yes" : "no") + "), composer emptied=" + emptied
                + ", task now " + (row == null ? "gone" : row.status()) + ", "
                + (stalls16.get() - stallsBeforeSend) + " EDT stalls over 16ms during the send");
        if (!answered) {
            fail("Enter with the list closed did not deliver the draft to the parked task");
        }
        if (!emptied) {
            fail("the composer kept the text after sending it");
        }
        log.add("edt stalls over 16ms since the first keystroke: " + (stalls16.get() - stallsBefore)
                + " of " + pings.get() + " pings (max " + maxPing.get() + "ms)");

        // hop 5 — the reuse actually happened in the window the user opens
        List<String> areas = onEdtReturn(() -> {
            List<String> out = new ArrayList<>();
            for (String pane : List.of("TaskPane", "InspectorPane", "WorkbenchPane")) {
                Component c = findByName(win[0], pane);
                out.add(pane + "=" + (c == null ? "absent"
                        : countMonospace((Container) c) + " monospace area(s)"));
            }
            out.add("window total RSTA=" + countRsta(win[0]));
            return out;
        });
        for (String a : areas) {
            log.add("reuse: " + a);
        }

        finish(failures.isEmpty(), project);
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static boolean composerEnabled(MainWindow w) {
        ComposerArea a = find(w, ComposerArea.class);
        InputPane p = find(w, InputPane.class);
        return a != null && a.isEnabled() && p != null;
    }

    private static String areaText(ComposerArea area) {
        try {
            return onEdtReturn(area::getText).replace("\n", "\\n");
        } catch (Exception e) {
            return "?";
        }
    }

    /** Who holds the keyboard right now — the difference between a key and a key that arrives. */
    private static String focusOwner() {
        Component o = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (o == null) {
            return "null";
        }
        Window w = SwingUtilities.getWindowAncestor(o);
        return o.getClass().getSimpleName() + " in " + (w == null ? "no window"
                : w.getClass().getSimpleName() + (w instanceof JWindow ? " (popup)" : " (main)"));
    }

    /** Park the foreground task the way the agent's question does, so §3.4 enables the composer. */
    private static long parkOne(NaruSessionModel session) {
        long fg = onEdtReturn(() -> {
            NaruSession s = session.session();
            return s == null ? -1L : s.foregroundTaskId();
        });
        if (fg < 0) {
            fail("the session has no foreground task to park on");
            return -1;
        }
        Thread t = new Thread(() -> {
            NaruTask task = session.session().findTask(fg).orNull();
            if (task != null) {
                task.requestInput(NMsg.ofC("which model should answer this? (parked by ComposerSmoke)"));
            }
        }, "smoke-park");
        t.start();
        try {
            t.join(15_000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        long parked = awaitParkedTask(session, 20);
        if (parked < 0) {
            fail("requestInput did not park task #" + fg + " and publish a pending request");
        }
        return parked;
    }

    private static long awaitParkedTask(NaruSessionModel session, int seconds) {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < until) {
            Long fromPending = onEdtReturn(() -> session.pendingRequest() == null ? null
                    : session.pendingRequest().task().id());
            if (fromPending != null) {
                return fromPending;
            }
            sleep(200);
        }
        return -1;
    }

    private static NaruSessionModel.TaskRow rowFor(NaruSessionModel session, long id) {
        for (NaruSessionModel.TaskRow r : session.tasks()) {
            if (r.id() == id) {
                return r;
            }
        }
        return null;
    }

    private static String describe(NaruSessionModel session) {
        StringBuilder sb = new StringBuilder();
        for (NaruSessionModel.TaskRow r : session.tasks()) {
            sb.append('#').append(r.id()).append(' ').append(r.status())
                    .append(r.conversation() ? " (interactive)" : " (batch)").append(", ");
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static boolean awaitFocus(ComposerArea area, MainWindow w, Robot robot) throws Exception {
        long until = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < until) {
            if (onEdtReturnBool(area::isFocusOwner)) {
                return true;
            }
            onEdt(() -> {
                w.toFront();
                w.requestFocus();
                area.requestFocusInWindow();
            });
            robot.delay(80);
        }
        return onEdtReturnBool(area::isFocusOwner);
    }

    private static void type(Robot r, int vk) {
        r.keyPress(vk);
        r.keyRelease(vk);
        r.delay(30);
    }

    private static void type(Robot r, int vk, int modifierKeyCode) {
        r.keyPress(modifierKeyCode);
        r.keyPress(vk);
        r.keyRelease(vk);
        r.keyRelease(modifierKeyCode);
        r.delay(30);
    }

    private static File capture(Robot r, String name) {
        try {
            BufferedImage img = r.createScreenCapture(new Rectangle(1380, 880));
            File f = new File(System.getProperty("java.io.tmpdir"), name + ".png");
            javax.imageio.ImageIO.write(img, "png", f);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private static int countRsta(Container c) {
        int n = 0;
        for (Component x : c.getComponents()) {
            if (x instanceof RSyntaxTextArea) {
                n++;
            }
            if (x instanceof Container inner) {
                n += countRsta(inner);
            }
        }
        return n;
    }

    /** Monospaced areas are the detail panes; the composer is proportional, which separates them. */
    private static int countMonospace(Container c) {
        int n = 0;
        for (Component x : c.getComponents()) {
            if (x instanceof RSyntaxTextArea t && !t.getFont().getFamily().equals("SansSerif")) {
                n++;
            }
            if (x instanceof Container inner) {
                n += countMonospace(inner);
            }
        }
        return n;
    }

    private static Component findByName(Container c, String simpleName) {
        for (Component x : c.getComponents()) {
            if (x.getClass().getSimpleName().equals(simpleName)) {
                return x;
            }
            if (x instanceof Container inner) {
                Component f = findByName(inner, simpleName);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    private static <T> T find(Container c, Class<T> kind) {
        for (Component x : c.getComponents()) {
            if (kind.isInstance(x)) {
                return kind.cast(x);
            }
            if (x instanceof Container inner) {
                T found = find(inner, kind);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static NaruSessionModel awaitSession(MainWindow w, int seconds) throws Exception {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < until) {
            NaruSessionModel s = w.session();
            if (s != null && s.isRunning() && s.session() != null) {
                return s;
            }
            Thread.sleep(150);
        }
        NaruSessionModel s = w.session();
        if (s == null || !s.isRunning()) {
            fail("the session never started within " + seconds + "s");
            return null;
        }
        return s;
    }

    private static boolean await(long millis, java.util.function.BooleanSupplier body) {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            if (body.getAsBoolean()) {
                return true;
            }
            sleep(20);
        }
        return body.getAsBoolean();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean onEdtReturnBool(java.util.function.BooleanSupplier body) {
        try {
            return onEdtReturn(body::getAsBoolean);
        } catch (Exception e) {
            return false;
        }
    }

    private static void startPinger() {
        Thread pinger = new Thread(() -> {
            while (true) {
                long t = System.currentTimeMillis();
                try {
                    SwingUtilities.invokeAndWait(() -> {
                    });
                } catch (Exception e) {
                    return;
                }
                long d = System.currentTimeMillis() - t;
                pings.incrementAndGet();
                maxPing.accumulateAndGet(d, Math::max);
                if (d > 16) {
                    stalls16.incrementAndGet();
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "edt-pinger");
        pinger.setDaemon(true);
        pinger.start();
    }

    private static void onEdt(Runnable body) throws Exception {
        SwingUtilities.invokeAndWait(body);
    }

    private static <T> T onEdtReturn(Edt<T> body) {
        try {
            Object[] box = new Object[1];
            SwingUtilities.invokeAndWait(() -> box[0] = body.get());
            @SuppressWarnings("unchecked")
            T value = (T) box[0];
            return value;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    interface Edt<T> {
        T get();
    }

    private static void fail(String message) {
        failures.add(message);
    }

    private static void finish(boolean ok, NPath project) {
        System.out.println("SMOKE composer");
        System.out.println("  project=" + project);
        System.out.println("  edt-pings=" + pings.get() + " max-ping-ms=" + maxPing.get()
                + " stalls-over-16ms=" + stalls16.get());
        for (String l : log) {
            System.out.println("  " + l);
        }
        for (String f : failures) {
            System.out.println("  failure: " + f);
        }
        System.out.println("  VERDICT=" + (ok
                ? "PASS — Ctrl+Space opens the engine's candidates in the real window, Enter takes"
                + " the highlighted row at the engine's span, Shift+Enter writes a newline and the"
                + " send still reaches the parked task"
                : "FAIL — see the failures above"));
    }
}
