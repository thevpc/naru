package net.thevpc.naru.desktop.smoke;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruLineCompletion;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.desktop.MainWindow;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.ui.ComposerArea;
import net.thevpc.naru.desktop.ui.TaskPane;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.swing.NSwingUtils;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.swing.plaf.UIPlafManager;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JWindow;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;

import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The candidate list driven by the MOUSE, in the real window.
 * <p>
 * {@code ComposerSmoke} proved the keyboard path by pressing Enter directly after Ctrl+Space, which
 * is the one sequence that cannot break: nothing between the two keystrokes can move focus or the
 * caret. A user does something else — the list appears, they click a row, they press Enter. That is
 * where this harness looks, and it records what the window actually does rather than what the widget
 * intends: every focus-owner hand-off, every moment the list changes visibility, every throwable the
 * EDT swallows (including the ones AWT logs to its own error listener instead of to stderr), and the
 * frame budget of the keystrokes themselves.
 * <pre>
 * usage: ComposerMouseSmoke &lt;projectDir&gt;
 * </pre>
 */
public final class ComposerMouseSmoke {

    private static final AtomicInteger pings = new AtomicInteger();
    private static final AtomicLong maxPing = new AtomicLong();
    private static final AtomicInteger stalls16 = new AtomicInteger();
    private static final List<String> log = new ArrayList<>();
    private static final List<String> failures = new ArrayList<>();
    private static final List<String> focusMoves = new ArrayList<>();
    private static final List<String> visibility = new ArrayList<>();
    private static final List<String> throwables = new ArrayList<>();
    private static final AtomicInteger currentStall = new AtomicInteger();

    private ComposerMouseSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Nuts.require();
        NSwingUtils.prepareUI(new String[0]);
        UIPlafManager.getCurrentManager().apply("FlatLight");
        UIPlafManager.getCurrentManager().apply("FlatDark");
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("SMOKE composer-mouse\n  VERDICT=FAIL — needs a display (Robot)");
            System.exit(1);
        }
        NPath project = NPath.of(args.length > 0 ? args[0]
                : System.getProperty("java.io.tmpdir") + "/naru-composer-smoke").toAbsolute();
        project.mkdirs();

        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                throwables.add("uncaught on " + t.getName() + ": " + e));
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
        log.add("laf=" + UIManager.getLookAndFeel().getName());

        NaruSessionModel session = awaitSession(win[0], 90);
        if (session == null) {
            finish(false);
            System.exit(1);
        }
        long parkedId = awaitParkedTask(session, 20);
        if (parkedId < 0) {
            parkedId = parkOne(session);
        }
        if (parkedId < 0) {
            finish(false);
            System.exit(1);
        }
        final long parked = parkedId;
        onEdt(() -> {
            TaskPane rail = find(win[0], TaskPane.class);
            if (rail != null) {
                rail.selectTask(parked);
            }
        });
        Thread.sleep(400);

        Robot robot = new Robot();
        ComposerArea area = onEdtReturn(() -> find(win[0], ComposerArea.class));
        if (area == null) {
            finish(false);
            System.exit(1);
        }
        onEdt(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addPropertyChangeListener(ComposerMouseSmoke::onFocusChange));
        if (!awaitFocus(area, win[0], robot)) {
            fail("the composer never took focus, so nothing below is a keystroke test");
            finish(false);
            System.exit(1);
        }

        // hop 1 — type exactly what the user types, then ask. Keystroke cost is measured here because
        // every keystroke currently hides the list, and hiding by add/remove is a window-wide relayout.
        focusMoves.clear();
        type(robot, KeyEvent.VK_SLASH);
        int stallsAfterTyping = stalls16.get();
        log.add("typed \"/\": " + (stallsAfterTyping - 0) + " stalls over 16ms since boot, "
                + "text=\"" + text(area) + "\" caret=" + onEdtReturn(area::getCaretPosition));

        long keyDown = System.nanoTime();
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_SPACE);
        robot.keyRelease(KeyEvent.VK_SPACE);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        boolean shown = await(10_000, () -> onEdtReturnBool(area::isCompletionVisible));
        log.add("ctrl+space: shown=" + shown + " in " + (System.nanoTime() - keyDown) / 1000 + "µs, "
                + onEdtReturn(area::completionCount) + " candidates, engine work "
                + onEdtReturn(area::lastCompletionMicros) + "µs");
        onEdt(() -> {
            for (int i = 0; i < Math.min(6, area.completionCount()); i++) {
                log.add("  candidate[" + i + "] = " + area.completionLabelAt(i));
            }
        });
        log.add("after ctrl+space: visible=" + onEdtReturnBool(area::isCompletionVisible)
                + " composer-focus=" + onEdtReturnBool(area::isFocusOwner)
                + " global=" + onEdtReturn(ComposerMouseSmoke::focusOwner));

        // let the paint settle the way it does for a human before the mouse moves
        Thread.sleep(500);
        log.add("after 500ms idle: visible=" + onEdtReturnBool(area::isCompletionVisible)
                + " composer-focus=" + onEdtReturnBool(area::isFocusOwner));
        capture(robot, "naru-mouse-1-open");

        // hop 2 — the click. Row 1, not row 0: the user is choosing, not confirming.
        JListRef ref = new JListRef();
        Rectangle row1 = onEdtReturn(() -> rowOnScreen(area, ref, 1));
        if (row1 == null) {
            fail("no candidate list found inside the window to click, so the popup never really drew");
            finish(false);
            System.exit(1);
        }
        log.add("clicking row 1 at " + row1 + " (list " + onEdtReturn(ref::bounds) + ")");
        visibility.clear();
        Thread watcher = watchVisibility(area);
        robot.mouseMove(row1.x + row1.width / 2, row1.y + row1.height / 2);
        robot.delay(60);
        robot.mousePress(KeyEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(KeyEvent.BUTTON1_DOWN_MASK);
        robot.delay(400);
        watcher.interrupt();
        watcher.join(1_000);
        log.add("after single click: visible=" + onEdtReturnBool(area::isCompletionVisible)
                + " selected=\"" + onEdtReturn(area::selectedCompletionLabel) + "\""
                + " selected-index=" + onEdtReturn(() -> ref.index())
                + " composer-focus=" + onEdtReturnBool(area::isFocusOwner)
                + " global=" + onEdtReturn(ComposerMouseSmoke::focusOwner)
                + " text=\"" + text(area) + "\""
                + " focus-moves=" + focusMoves);
        log.add("visibility samples during the click: " + visibility);
        // The expectation a user has: one click picks the row out of the list and leaves the list
        // open, so they can see what they chose before committing. If the click instead closes the
        // list or the keyboard leaves the composer, this is where it shows.
        if (!onEdtReturnBool(area::isCompletionVisible)) {
            fail("a single click closed the candidate list, so the user cannot see what they picked");
        }
        if (onEdtReturn(() -> ref.index()) != 1) {
            fail("a single click on row 1 did not highlight row 1 (index was "
                    + onEdtReturn(() -> ref.index()) + ")");
        }
        if (!text(area).equals("/")) {
            fail("a single click changed the draft — it should highlight, not insert: text=\""
                    + text(area).replace('\n', ' ') + "\"");
        }
        capture(robot, "naru-mouse-2-clicked");

        // hop 3 — Enter after the click. The claim: it takes the row the user clicked, it does not
        // send the half-typed line, and the list closes.
        boolean openBeforeEnter = onEdtReturnBool(area::isCompletionVisible);
        String wanted = onEdtReturn(area::selectedCompletionInsert);
        type(robot, KeyEvent.VK_ENTER);
        robot.delay(400);
        String after = text(area);
        log.add("enter after click: list was " + (openBeforeEnter ? "open" : "ALREADY CLOSED")
                + ", insert-was=\"" + wanted + "\", text now=\"" + after.replace('\n', ' ') + "\""
                + ", visible=" + onEdtReturnBool(area::isCompletionVisible)
                + ", pending request now " + (onEdtReturn(session::pendingRequest) == null
                ? "answered/gone" : "still the same one"));
        if (!after.equals(wanted)) {
            fail("Enter with the list open did not leave the clicked row in the composer"
                    + " (expected \"" + wanted + "\", got \"" + after.replace('\n', ' ') + "\")");
        }
        if (onEdtReturnBool(area::isCompletionVisible)) {
            fail("the list stayed open after Enter took the candidate");
        }
        if (!onEdtReturnBool(area::isFocusOwner)) {
            fail("the click moved the keyboard off the composer: "
                    + onEdtReturn(ComposerMouseSmoke::focusOwner));
        }
        capture(robot, "naru-mouse-3-enter");

        // hop 4 — the same list, driven with no mouse at all, which is the path ComposerSmoke proved.
        // Reporting both paths side by side is what tells a reader whether the mouse is the variable.
        onEdt(() -> {
            area.setText("/");
            area.setCaretPosition(1);
            area.requestFocusInWindow();
        });
        robot.delay(150);
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_SPACE);
        robot.keyRelease(KeyEvent.VK_SPACE);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        robot.delay(300);
        boolean kbOpen = onEdtReturnBool(area::isCompletionVisible);
        String kbWanted = onEdtReturn(area::selectedCompletionInsert);
        type(robot, KeyEvent.VK_ENTER);
        robot.delay(300);
        log.add("keyboard-only round: open=" + kbOpen + " insert=\"" + kbWanted + "\""
                + " text now=\"" + text(area).replace('\n', ' ') + "\"");
        if (kbOpen && !text(area).equals(kbWanted)) {
            fail("Enter did not take the highlighted candidate even with no mouse involved");
        }

        // hop 5 — double click, the other way a user expects to confirm a row.
        onEdt(() -> {
            area.setText("/");
            area.setCaretPosition(1);
            area.requestFocusInWindow();
        });
        robot.delay(150);
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_SPACE);
        robot.keyRelease(KeyEvent.VK_SPACE);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        robot.delay(300);
        Rectangle row0 = onEdtReturn(() -> rowOnScreen(area, ref, 0));
        if (row0 != null) {
            robot.mouseMove(row0.x + row0.width / 2, row0.y + row0.height / 2);
            robot.delay(50);
            robot.mousePress(KeyEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(KeyEvent.BUTTON1_DOWN_MASK);
            robot.delay(60);
            robot.mousePress(KeyEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(KeyEvent.BUTTON1_DOWN_MASK);
            robot.delay(400);
            log.add("double click row 0: text=\"" + text(area).replace('\n', ' ')
                    + "\" visible=" + onEdtReturnBool(area::isCompletionVisible)
                    + " composer-focus=" + onEdtReturnBool(area::isFocusOwner));
        } else {
            log.add("double click row 0: the list was not there to click");
        }
        capture(robot, "naru-mouse-4-double");

        // hop 6 — the price of a keystroke while nothing is open: the lag the user feels while typing.
        onEdt(() -> {
            area.setText("");
            area.requestFocusInWindow();
        });
        int stallsBeforeType = stalls16.get();
        long t0 = System.nanoTime();
        for (int vk : new int[]{KeyEvent.VK_S, KeyEvent.VK_L, KeyEvent.VK_E, KeyEvent.VK_P,
                KeyEvent.VK_SPACE, KeyEvent.VK_SLASH, KeyEvent.VK_H, KeyEvent.VK_E, KeyEvent.VK_L,
                KeyEvent.VK_P}) {
            type(robot, vk);
        }
        long typedMs = (System.nanoTime() - t0) / 1_000_000;
        log.add("typed 10 keys in " + typedMs + "ms: " + (stalls16.get() - stallsBeforeType)
                + " EDT stalls over 16ms, max ping " + maxPing.get() + "ms");

        if (!throwables.isEmpty()) {
            log.add("throwables seen: " + throwables);
            fail("the run produced " + throwables.size()
                    + " swallowed/uncaught throwable(s): " + throwables.get(0));
        } else {
            log.add("throwables seen: none");
        }
        finish(failures.isEmpty());
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    // ── popup introspection: the harness must find the list the widget drew, not a model of it ──

    /** The JList the popup actually shows: the one inside the window whose model holds candidates. */
    private static final class JListRef {
        JList<NaruLineCompletion.Candidate> list;

        Rectangle bounds() {
            return list == null ? null : list.getBounds();
        }

        /** Which row the list itself thinks is highlighted — the widget's answer, not the model's. */
        int index() {
            return list == null ? -99 : list.getSelectedIndex();
        }
    }

    private static JList<NaruLineCompletion.Candidate> candidateList(ComposerArea area) {
        Container root = area.getRootPane().getLayeredPane();
        for (Component c : root.getComponents()) {
            JList<NaruLineCompletion.Candidate> l = findCandidate((Container) c);
            if (l != null) {
                return l;
            }
        }
        return null;
    }

    private static JList<NaruLineCompletion.Candidate> findCandidate(Container c) {
        for (Component x : c.getComponents()) {
            if (x instanceof JList<?> l && l.getModel().getSize() > 0
                    && l.getModel().getElementAt(0) instanceof NaruLineCompletion.Candidate) {
                @SuppressWarnings("unchecked")
                JList<NaruLineCompletion.Candidate> typed = (JList<NaruLineCompletion.Candidate>) l;
                return typed;
            }
            if (x instanceof Container inner) {
                JList<NaruLineCompletion.Candidate> f = findCandidate(inner);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    /** Where row {@code i} is on the screen right now, in screen coordinates for {@link Robot}. */
    private static Rectangle rowOnScreen(ComposerArea area, JListRef ref, int i) {
        JList<NaruLineCompletion.Candidate> l = candidateList(area);
        if (l == null || !l.isShowing() || i >= l.getModel().getSize()) {
            return null;
        }
        ref.list = l;
        int cellHeight = (int) l.getFixedCellHeight();
        Rectangle cell = l.getCellBounds(i, i);
        if (cell == null) {
            return null;
        }
        Point p = l.getLocationOnScreen();
        return new Rectangle(p.x + 20, p.y + cell.y, 60,
                Math.max(10, cell.height > 0 ? cell.height : cellHeight));
    }

    /** Edge-triggered visibility, so a list that opened and closed between two reads still shows it. */
    private static Thread watchVisibility(ComposerArea area) {
        Thread t = new Thread(() -> {
            boolean last = false;
            while (!Thread.currentThread().isInterrupted()) {
                boolean now;
                try {
                    Object[] box = new Object[1];
                    SwingUtilities.invokeAndWait(() -> box[0] = area.isCompletionVisible());
                    now = Boolean.TRUE.equals(box[0]);
                } catch (Exception e) {
                    return;
                }
                if (now != last) {
                    visibility.add((now ? "opened" : "closed") + "@" + currentStall.getAndIncrement());
                    last = now;
                }
                try {
                    Thread.sleep(4);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "visibility-watcher");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void onFocusChange(PropertyChangeEvent e) {
        if ("focusOwner".equals(e.getPropertyName())) {
            focusMoves.add(stringOf(e.getOldValue()) + " -> " + stringOf(e.getNewValue()));
        }
    }

    private static String stringOf(Object c) {
        if (c == null) {
            return "null";
        }
        Window w = SwingUtilities.getWindowAncestor((Component) c);
        return ((Component) c).getClass().getSimpleName()
                + (w == null ? "" : "@" + w.getClass().getSimpleName());
    }

    private static String text(ComposerArea area) {
        try {
            return onEdtReturn(area::getText).replace("\n", "\\n");
        } catch (Exception e) {
            return "?";
        }
    }

    private static String focusOwner() {
        Component o = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (o == null) {
            return "null";
        }
        Window w = SwingUtilities.getWindowAncestor(o);
        return o.getClass().getSimpleName() + " in " + (w == null ? "no window"
                : w.getClass().getSimpleName() + (w instanceof JWindow ? " (popup)" : " (main)"));
    }

    private static long parkOne(NaruSessionModel session) {
        long fg = onEdtReturn(() -> {
            NaruSession s = session.session();
            return s == null ? -1L : s.foregroundTaskId();
        });
        if (fg < 0) {
            return -1;
        }
        Thread t = new Thread(() -> {
            NaruTask task = session.session().findTask(fg).orNull();
            if (task != null) {
                task.requestInput(NMsg.ofC("which model? (parked by ComposerMouseSmoke)"));
            }
        }, "smoke-park");
        t.start();
        try {
            t.join(15_000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return awaitParkedTask(session, 20);
    }

    private static long awaitParkedTask(NaruSessionModel session, int seconds) {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < until) {
            Long id = onEdtReturn(() -> session.pendingRequest() == null ? null
                    : session.pendingRequest().task().id());
            if (id != null) {
                return id;
            }
            sleep(150);
        }
        return -1;
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

    private static File capture(Robot r, String name) {
        try {
            BufferedImage img = r.createScreenCapture(new Rectangle(1380, 880));
            File f = new File(System.getProperty("java.io.tmpdir"), name + ".png");
            javax.imageio.ImageIO.write(img, "png", f);
            log.add("screenshot: " + f);
            return f;
        } catch (Exception e) {
            return null;
        }
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
        fail("the session never started within " + seconds + "s");
        return null;
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
                    Thread.sleep(20);
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

    private static void finish(boolean ok) {
        System.out.println("SMOKE composer-mouse");
        System.out.println("  edt-pings=" + pings.get() + " max-ping-ms=" + maxPing.get()
                + " stalls-over-16ms=" + stalls16.get());
        for (String l : log) {
            System.out.println("  " + l);
        }
        for (String f : failures) {
            System.out.println("  failure: " + f);
        }
        System.out.println("  VERDICT=" + (ok
                ? "PASS — the mouse and Enter do the same thing, and the list survives the click"
                : "FAIL — the composer popup does not survive how a user actually drives it"));
    }
}
