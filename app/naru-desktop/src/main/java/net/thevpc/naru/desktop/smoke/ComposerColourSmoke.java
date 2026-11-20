//package net.thevpc.naru.desktop.smoke;
//
//import net.thevpc.naru.api.agent.NaruSession;
//import net.thevpc.naru.api.registry.NaruLineSyntax.Role;
//import net.thevpc.naru.api.task.NaruTask;
//import net.thevpc.naru.desktop.MainWindow;
//import net.thevpc.naru.desktop.model.NaruSessionModel;
//import net.thevpc.naru.desktop.ui.ComposerArea;
//import net.thevpc.naru.desktop.ui.ComposerSyntax;
//import net.thevpc.naru.desktop.ui.InputPane;
//import net.thevpc.naru.desktop.ui.TaskPane;
//import net.thevpc.nuts.Nuts;
//import net.thevpc.nuts.io.NPath;
//import net.thevpc.nuts.swing.NSwingUtils;
//import net.thevpc.nuts.text.NMsg;
//import net.thevpc.swing.plaf.UIPlafManager;
//import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
//import org.fife.ui.rsyntaxtextarea.Token;
//import org.fife.ui.rsyntaxtextarea.TokenTypes;
//
//import javax.swing.SwingUtilities;
//import javax.swing.UIManager;
//import javax.swing.text.BadLocationException;
//import java.awt.Color;
//import java.awt.Component;
//import java.awt.Container;
//import java.awt.GraphicsEnvironment;
//import java.awt.Point;
//import java.awt.Rectangle;
//import java.awt.Robot;
//import java.awt.event.KeyEvent;
//import java.awt.image.BufferedImage;
//import java.util.ArrayList;
//import java.util.LinkedHashMap;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.atomic.AtomicInteger;
//import java.util.concurrent.atomic.AtomicLong;
//
///**
// * The composer's colours, measured from the pixels the window painted.
// * <p>
// * A token list can be right and the screen still wrong: the scheme might never reach the widget, the
// * font might fall back, the word the colour belongs to might be a different one. So this harness types
// * a real command into the real window, asks the widget where each span of it is on screen, and reads
// * those pixels back. Every sample classifies itself against the palette by average colour distance,
// * which is what a user's eye does, and the answer is printed whether it passed or not.
// * <pre>
// * usage: ComposerColourSmoke &lt;projectDir&gt;
// * </pre>
// */
//public final class ComposerColourSmoke {
//
//    private static final AtomicInteger pings = new AtomicInteger();
//    private static final AtomicLong maxPing = new AtomicLong();
//    private static final AtomicInteger stalls16 = new AtomicInteger();
//    private static final List<String> log = new ArrayList<>();
//    private static final List<String> failures = new ArrayList<>();
//
//    private ComposerColourSmoke() {
//    }
//
//    public static void main(String[] args) throws Exception {
//        Nuts.require();
//        NSwingUtils.prepareUI(new String[0]);
//        UIPlafManager.getCurrentManager().apply("FlatLight");
//        UIPlafManager.getCurrentManager().apply("FlatDark");
//        if (GraphicsEnvironment.isHeadless()) {
//            System.out.println("SMOKE composer-colour\n  VERDICT=FAIL — needs a display (Robot)");
//            System.exit(1);
//        }
//        NPath project = NPath.of(args.length > 0 ? args[0]
//                : System.getProperty("java.io.tmpdir") + "/naru-composer-smoke").toAbsolute();
//        project.mkdirs();
//        startPinger();
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
//        log.add("laf=" + UIManager.getLookAndFeel().getName());
//
//        NaruSessionModel session = awaitSession(win[0], 90);
//        ComposerArea area = onEdtReturn(() -> find(win[0], ComposerArea.class));
//        InputPane input = onEdtReturn(() -> find(win[0], InputPane.class));
//        if (session == null || area == null || input == null) {
//            fail("no live composer to colour (session=" + (session != null)
//                    + " area=" + (area != null) + " input=" + (input != null) + ")");
//            finish(false);
//            System.exit(1);
//        }
//        // a real user reaches for the keyboard, so the composer is given a task to talk to first
//        long parked = awaitParkedTask(session, 10);
//        if (parked < 0) {
//            parked = parkOne(session);
//        }
//        if (parked >= 0) {
//            final long id = parked;
//            onEdt(() -> {
//                TaskPane rail = find(win[0], TaskPane.class);
//                if (rail != null) {
//                    rail.selectTask(id);
//                }
//            });
//            Thread.sleep(300);
//        } else {
//            log.add("no task could be parked; the composer is coloured as a disabled field would be");
//        }
//        Robot robot = new Robot();
//        if (!awaitFocus(area, win[0], robot)) {
//            log.add("the composer would not take focus; typing is done by setting its text instead");
//        }
//
//        // ── hop 1 — a command line typed the way a user types one ──────────────────────────────
//        String command = "/assert --level 2 /tmp/x 42 tail";
//        if (onEdtReturnBool(area::isFocusOwner)) {
//            onEdt(() -> area.setText(""));
//            typeString(robot, command);
//        } else {
//            onEdt(() -> area.setText(command));
//        }
//        Thread.sleep(400);
//        String text = onEdtReturn(area::getText);
//        log.add("typed text=\"" + text + "\" composer-mode=" + onEdtReturn(input::composerMode)
//                + " scan-failures=" + onEdtReturn(input::composerSyntaxFailure));
//        if (!command.equals(text)) {
//            fail("the composer does not hold the line that was typed, so the samples below are not "
//                    + "the samples of a command: \"" + text + "\"");
//        }
//        capture(robot, "naru-colour-1-command");
//
//        Map<String, Role> expected = new LinkedHashMap<>();
//        expected.put("/assert", Role.DIRECTIVE);
//        expected.put("--level", Role.OPTION_KEY);
//        expected.put("2", Role.OPTION_VALUE);
//        expected.put("/tmp/x", Role.PATH);
//        expected.put("42", Role.NUMBER);
//        expected.put("tail", Role.TEXT);
//        paintEachWord(robot, area, expected);
//
//        // ── hop 1b — the two roles a typed key cannot reach on this layout ─────────────────────
//        onEdt(() -> area.setText("/print \"hello world\" 42"));
//        Thread.sleep(300);
//        capture(robot, "naru-colour-1b-string");
//        Map<String, Role> quoted = new LinkedHashMap<>();
//        quoted.put("\"hello world\"", Role.STRING);
//        quoted.put("42", Role.NUMBER);
//        paintEachWord(robot, area, quoted);
//
//        // ── hop 2 — an unknown command is the one colour a user must not miss ──────────────────
//        onEdt(() -> area.setText("/no-such-command here"));
//        Thread.sleep(300);
//        capture(robot, "naru-colour-2-unknown");
//        Map<String, Role> unknown = new LinkedHashMap<>();
//        unknown.put("/no-such-command", Role.UNKNOWN_DIRECTIVE);
//        unknown.put("here", Role.TEXT);
//        paintEachWord(robot, area, unknown);
//
//        // ── hop 3 — a comment, then a draft in someone else's language ─────────────────────────
//        onEdt(() -> area.setText("# a line the engine skips"));
//        Thread.sleep(300);
//        Map<String, Role> comment = new LinkedHashMap<>();
//        comment.put("# a line the engine skips", Role.COMMENT);
//        paintEachWord(robot, area, comment);
//
//        String javaDraft = "public class Foo {\n    Object x = null;\n}";
//        onEdt(() -> area.setText(javaDraft));
//        Thread.sleep(400);
//        capture(robot, "naru-colour-3-draft");
//        String mode = onEdtReturn(input::composerMode);
//        log.add("draft composer-mode=" + mode);
//        if (!mode.contains("java")) {
//            fail("a Java draft was not detected as Java: mode=" + mode);
//        }
//        List<String> types = onEdtReturn(() -> {
//            List<String> out = new ArrayList<>();
//            Token t = ((RSyntaxDocument) area.getDocument()).getTokenListForLine(0);
//            for (; t != null && t.isPaintable(); t = t.getNextToken()) {
//                out.add(name(t.getType()) + ":" + t.getLexeme());
//            }
//            return out;
//        });
//        log.add("line 1 tokens=" + types);
//        // plain text can only ever say IDENTIFIER; a keyword is Java's own maker answering
//        if (!types.contains("RESERVED_WORD:public")) {
//            fail("the draft was not tokenized by Java's maker, so the detected language never reached"
//                    + " the painter: " + types);
//        }
//        // the word `null` must not be the same colour as the plain text around it
//        int at = javaDraft.indexOf("null");
//        Color inkNull = coreInk(robot, area, onEdtReturn(() -> boxOf(area, at, at + 4)));
//        int pub = javaDraft.indexOf("public");
//        Color inkKeyword = coreInk(robot, area, onEdtReturn(() -> boxOf(area, pub, pub + 6)));
//        Color fg = onEdtReturn(area::getForeground);
//        log.add("draft ink: null=" + describe(inkNull) + " public=" + describe(inkKeyword)
//                + " plain-foreground=" + describe(fg));
//        if (inkNull == null) {
//            fail("nothing could be sampled from the draft's second line");
//        } else if (distance(inkNull, fg) < 12) {
//            fail("the draft's `null` is painted in the plain text colour, so the detected Java maker"
//                    + " never reached the paint: " + describe(inkNull));
//        }
//
//        // ── hop 4 — the cost of colouring while typing ─────────────────────────────────────────
//        stalls16.set(0);
//        pings.set(0);
//        maxPing.set(0);
//        onEdt(() -> area.setText(""));
//        long typedAt = System.currentTimeMillis();
//        if (onEdtReturnBool(area::isFocusOwner)) {
//            typeString(robot, "explain what /tmp does and why 42 matters");
//        }
//        long typedMs = System.currentTimeMillis() - typedAt;
//        log.add("typed 44 keys in " + typedMs + "ms, edt stalls over 16ms=" + stalls16.get()
//                + " max-ping-ms=" + maxPing.get());
//        if (stalls16.get() > 3) {
//            fail("colouring the composer stalls the event queue: " + stalls16.get() + " frames late");
//        }
//
//        finish(failures.isEmpty());
//        System.exit(failures.isEmpty() ? 0 : 1);
//    }
//
//    /** Where a word is on screen, then what colour the middle of its glyphs came out as. */
//    private static void paintEachWord(Robot robot, ComposerArea area, Map<String, Role> words) {
//        for (Map.Entry<String, Role> e : words.entrySet()) {
//            String word = e.getKey();
//            Role role = e.getValue();
//            // modelToView lays the document out, so it belongs on the EDT like every other call here
//            int at = onEdtReturn(() -> area.getText().indexOf(word));
//            if (at < 0) {
//                fail("the word \"" + word + "\" is not in the composer, so it cannot be sampled");
//                continue;
//            }
//            Rectangle box = onEdtReturn(() -> boxOf(area, at, at + word.length()));
//            if (box == null) {
//                fail("\"" + word + "\" is not laid out on screen (offset " + at + ")");
//                continue;
//            }
//            Color ink = coreInk(robot, area, box);
//            Color wanted = ComposerSyntax.colorFor(role);
//            Color fg = onEdtReturn(area::getForeground);
//            Color best = wanted == null ? fg : wanted;
//            String nearest = nearestName(ink, best, fg, wanted == null);
//            log.add("  " + role + " \"" + word + "\" box=" + box + " ink=" + describe(ink)
//                    + " wanted=" + describe(best) + " distance="
//                    + (ink == null ? "n/a" : Math.round(distance(ink, best)))
//                    + " classified=" + nearest);
//            if (ink == null) {
//                fail("\"" + word + "\" painted nothing at " + box);
//                continue;
//            }
//            if (distance(ink, best) > 60) {
//                fail("\"" + word + "\" is not painted as " + role + ": ink " + describe(ink)
//                        + " is " + Math.round(distance(ink, best)) + " away from " + describe(best));
//            }
//            if (role != Role.TEXT && distance(ink, fg) < 12 && !best.equals(fg)) {
//                fail("\"" + word + "\" is painted in the plain text colour, so the role never reached"
//                        + " the screen: " + describe(ink));
//            }
//        }
//    }
//
//    /**
//     * The rectangle a range of document offsets occupies on screen, widened by a pixel each side.
//     * RSTA answers with the caret position of an offset, so the box of {@code [start, end)} is the gap
//     * between the two carets — the same arithmetic the completion popup is placed with.
//     */
//    private static Rectangle boxOf(ComposerArea area, int start, int end) {
//        try {
//            Rectangle a = area.modelToView(start);
//            Rectangle b = area.modelToView(end);
//            if (a == null || b == null || a.y != b.y) {
//                return null;
//            }
//            Point onScreen = area.getLocationOnScreen();
//            int x = Math.min(a.x, b.x) + onScreen.x + 1;
//            int width = Math.max(2, Math.abs(b.x - a.x) - 2);
//            int h = Math.min(a.height, b.height);
//            return new Rectangle(x, a.y + onScreen.y + 1, width, h - 2);
//        } catch (BadLocationException | IllegalStateException e) {
//            return null;
//        }
//    }
//
//    /**
//     * The average colour of the pixels in {@code box} that are furthest from the background: the
//     * bodies of the glyphs, where antialiasing has not thinned the ink. Null when the box is empty of
//     * ink at all.
//     */
//    private static Color coreInk(Robot robot, Component area, Rectangle box) {
//        if (box == null || box.width <= 0 || box.height <= 0) {
//            return null;
//        }
//        Color bg = onEdtReturn(area::getBackground);
//        BufferedImage shot = robot.createScreenCapture(box);
//        List<int[]> pixels = new ArrayList<>();
//        for (int y = 0; y < shot.getHeight(); y++) {
//            for (int x = 0; x < shot.getWidth(); x++) {
//                int p = shot.getRGB(x, y);
//                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
//                if (distanceFrom(new int[]{r, g, b}, bg) > 60) {
//                    pixels.add(new int[]{r, g, b});
//                }
//            }
//        }
//        if (pixels.isEmpty()) {
//            return null;
//        }
//        // The ink furthest from the background is the body of a stroke, where the colour is whole;
//        // the pixels nearer the background are the antialiased edge and drag the average grey.
//        pixels.sort((u, v) -> Integer.compare(distanceFrom(v, bg), distanceFrom(u, bg)));
//        int take = Math.max(1, pixels.size() / 3);
//        long rs = 0, gs = 0, bs = 0;
//        for (int i = 0; i < take; i++) {
//            int[] p = pixels.get(i);
//            rs += p[0];
//            gs += p[1];
//            bs += p[2];
//        }
//        return new Color((int) (rs / take), (int) (gs / take), (int) (bs / take));
//    }
//
//    private static int distanceFrom(int[] p, Color bg) {
//        return sq(p[0] - bg.getRed()) + sq(p[1] - bg.getGreen()) + sq(p[2] - bg.getBlue());
//    }
//
//    private static String nearestName(Color ink, Color wanted, Color fg, boolean textRole) {
//        if (ink == null) {
//            return "no ink";
//        }
//        Map<String, Color> palette = new LinkedHashMap<>();
//        for (Role r : Role.values()) {
//            Color c = ComposerSyntax.colorFor(r);
//            if (c != null) {
//                palette.put(r.name(), c);
//            }
//        }
//        palette.put("PLAIN-FOREGROUND", fg);
//        String name = null;
//        double best = Double.MAX_VALUE;
//        for (Map.Entry<String, Color> e : palette.entrySet()) {
//            double d = distance(ink, e.getValue());
//            if (d < best) {
//                best = d;
//                name = e.getKey();
//            }
//        }
//        return name + "(" + Math.round(best) + ")" + (textRole ? " [TEXT expects the foreground]" : "");
//    }
//
//    private static double distance(Color a, Color b) {
//        if (a == null || b == null) {
//            return Double.MAX_VALUE;
//        }
//        return Math.sqrt(distanceFrom(new int[]{a.getRed(), a.getGreen(), a.getBlue()}, b));
//    }
//
//    private static int sq(int v) {
//        return v * v;
//    }
//
//    /** RSTA's type constants are numbers; the log has to say which one answered. */
//    private static String name(int type) {
//        try {
//            for (java.lang.reflect.Field f : TokenTypes.class.getFields()) {
//                if (f.getType() == int.class && f.getInt(null) == type) {
//                    return f.getName();
//                }
//            }
//        } catch (Exception e) {
//            log.add("  token name lookup failed: " + e);
//        }
//        return "?" + type;
//    }
//
//    private static String describe(Color c) {
//        return c == null ? "null" : String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
//    }
//
//    // ── window plumbing, the same as the other composer harnesses ─────────────────────────────
//
//    private static void typeString(Robot robot, String s) {
//        for (char c : s.toCharArray()) {
//            int vk;
//            boolean shift = false;
//            switch (c) {
//                case ' ' -> vk = KeyEvent.VK_SPACE;
//                case '/' -> vk = KeyEvent.VK_SLASH;
//                case '-' -> vk = KeyEvent.VK_MINUS;
//                case '=' -> vk = KeyEvent.VK_EQUALS;
//                case '"' -> {
//                    // shift-2 on the layout the Xvfb session uses; VK_QUOTE would give a single quote
//                    vk = KeyEvent.VK_2;
//                    shift = true;
//                }
//                case '\\' -> {
//                    vk = KeyEvent.VK_BACK_SLASH;
//                    shift = true;
//                }
//                case '}' -> {
//                    vk = KeyEvent.VK_BRACERIGHT;
//                    shift = true;
//                }
//                case '{' -> {
//                    vk = KeyEvent.VK_BRACELEFT;
//                    shift = true;
//                }
//                case '\n' -> vk = KeyEvent.VK_ENTER;
//                default -> {
//                    if (Character.isLetter(c)) {
//                        vk = Character.toUpperCase(c) - 'A' + KeyEvent.VK_A;
//                        shift = Character.isUpperCase(c);
//                    } else if (Character.isDigit(c)) {
//                        vk = c - '0' + KeyEvent.VK_0;
//                        if (c == '0') {
//                            vk = KeyEvent.VK_0;
//                        }
//                    } else {
//                        vk = 0;
//                    }
//                }
//            }
//            if (vk == 0) {
//                continue;
//            }
//            if (shift) {
//                robot.keyPress(KeyEvent.VK_SHIFT);
//            }
//            robot.keyPress(vk);
//            robot.keyRelease(vk);
//            if (shift) {
//                robot.keyRelease(KeyEvent.VK_SHIFT);
//            }
//            robot.delay(18);
//        }
//    }
//
//    private static boolean awaitFocus(ComposerArea area, MainWindow w, Robot robot) throws Exception {
//        long until = System.currentTimeMillis() + 8_000;
//        while (System.currentTimeMillis() < until) {
//            if (onEdtReturnBool(area::isFocusOwner)) {
//                return true;
//            }
//            onEdt(() -> {
//                w.toFront();
//                w.requestFocus();
//                area.requestFocusInWindow();
//            });
//            robot.delay(80);
//        }
//        return onEdtReturnBool(area::isFocusOwner);
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
//        return null;
//    }
//
//    private static long awaitParkedTask(NaruSessionModel session, int seconds) {
//        long until = System.currentTimeMillis() + seconds * 1000L;
//        while (System.currentTimeMillis() < until) {
//            Long id = onEdtReturn(() -> session.pendingRequest() == null ? null
//                    : session.pendingRequest().task().id());
//            if (id != null) {
//                return id;
//            }
//            sleep(200);
//        }
//        return -1;
//    }
//
//    private static long parkOne(NaruSessionModel session) {
//        long fg = onEdtReturn(() -> {
//            NaruSession s = session.session();
//            return s == null ? -1L : s.foregroundTaskId();
//        });
//        if (fg < 0) {
//            return -1;
//        }
//        Thread t = new Thread(() -> {
//            NaruTask task = session.session().findTask(fg).orNull();
//            if (task != null) {
//                task.requestInput(NMsg.ofC("which model? (parked by ComposerColourSmoke)"));
//            }
//        }, "smoke-park");
//        t.start();
//        try {
//            t.join(15_000);
//        } catch (InterruptedException ignored) {
//            Thread.currentThread().interrupt();
//        }
//        return awaitParkedTask(session, 20);
//    }
//
//    private static void capture(Robot r, String name) {
//        try {
//            BufferedImage img = r.createScreenCapture(new Rectangle(1380, 880));
//            java.io.File f = new java.io.File(System.getProperty("java.io.tmpdir"), name + ".png");
//            javax.imageio.ImageIO.write(img, "png", f);
//            log.add("  screenshot=" + f);
//        } catch (Exception e) {
//            log.add("  screenshot failed: " + e);
//        }
//    }
//
//    @SuppressWarnings("unchecked")
//    private static <T> T find(Container c, Class<T> kind) {
//        for (Component x : c.getComponents()) {
//            if (kind.isInstance(x)) {
//                return (T) x;
//            }
//            if (x instanceof Container inner) {
//                T found = find(inner, kind);
//                if (found != null) {
//                    return found;
//                }
//            }
//        }
//        return null;
//    }
//
//    private static void startPinger() {
//        Thread pinger = new Thread(() -> {
//            while (true) {
//                long t = System.currentTimeMillis();
//                try {
//                    SwingUtilities.invokeAndWait(() -> {
//                    });
//                } catch (Exception e) {
//                    return;
//                }
//                long d = System.currentTimeMillis() - t;
//                pings.incrementAndGet();
//                maxPing.accumulateAndGet(d, Math::max);
//                if (d > 16) {
//                    stalls16.incrementAndGet();
//                }
//                try {
//                    Thread.sleep(50);
//                } catch (InterruptedException e) {
//                    return;
//                }
//            }
//        }, "edt-pinger");
//        pinger.setDaemon(true);
//        pinger.start();
//    }
//
//    private static void onEdt(Runnable body) throws Exception {
//        SwingUtilities.invokeAndWait(body);
//    }
//
//    private static <T> T onEdtReturn(Edt<T> body) {
//        try {
//            Object[] box = new Object[1];
//            SwingUtilities.invokeAndWait(() -> box[0] = body.get());
//            @SuppressWarnings("unchecked")
//            T value = (T) box[0];
//            return value;
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        }
//    }
//
//    private static boolean onEdtReturnBool(java.util.function.BooleanSupplier body) {
//        try {
//            return onEdtReturn(body::getAsBoolean);
//        } catch (Exception e) {
//            return false;
//        }
//    }
//
//    @FunctionalInterface
//    interface Edt<T> {
//        T get();
//    }
//
//    private static void sleep(long millis) {
//        try {
//            Thread.sleep(millis);
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//        }
//    }
//
//    private static void fail(String message) {
//        failures.add(message);
//    }
//
//    private static void finish(boolean ok) {
//        System.out.println("SMOKE composer-colour");
//        System.out.println("  edt-pings=" + pings.get() + " max-ping-ms=" + maxPing.get()
//                + " stalls-over-16ms=" + stalls16.get());
//        for (String l : log) {
//            System.out.println("  " + l);
//        }
//        for (String f : failures) {
//            System.out.println("  failure: " + f);
//        }
//        System.out.println("  VERDICT=" + (ok
//                ? "PASS — every role in a typed command is painted with its own colour at its own word,"
//                + " an unknown command is red, and a Java draft is painted by Java's own token maker"
//                : "FAIL — see the failures above"));
//    }
//}
