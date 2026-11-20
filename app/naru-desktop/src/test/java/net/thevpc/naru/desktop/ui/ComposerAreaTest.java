package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruWorkspaceBuilder;
import net.thevpc.naru.api.registry.NaruLineCompletion.Candidate;
import net.thevpc.naru.api.registry.NaruLineCompletion.Kind;
import net.thevpc.naru.api.registry.NaruLineCompletion.Result;
import net.thevpc.naru.desktop.host.InteractionHost;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composer's keys and its candidate list: first against a fake completion source, then against
 * the real engine.
 * <p>
 * The fake is what makes the important assertion possible. The widget must replace <em>exactly the
 * span the engine named</em>, and only a source under test control can name an odd one — a test that
 * goes through a live session can pin the delegation but not the arithmetic, so the last test in
 * this file pins the delegation and nothing more.
 * <p>
 * Keys are dispatched as real {@link KeyEvent}s to the focused component rather than by calling
 * action names: a binding that exists but never fires — eaten by the widget first, or scoped to a
 * component nobody focuses — is the failure this has to catch.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ComposerAreaTest {

    private static JFrame frame;
    private static final List<String> askedWith = new ArrayList<>();

    @BeforeAll
    static void requireNuts() {
        Nuts.require();
    }

    @AfterAll
    static void tearDown() throws Exception {
        onEdt(() -> {
            if (frame != null) {
                frame.dispose();
                frame = null;
            }
        });
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

    private static <T> T onEdtReturn(Edt<T> body) throws Exception {
        Object[] out = new Object[1];
        onEdt(() -> out[0] = body.get());
        return unchecked(out[0]);
    }

    interface Edt<T> {
        T get() throws Exception;
    }

    @SuppressWarnings("unchecked")
    private static <T> T unchecked(Object o) {
        return (T) o;
    }

    /** Put a fresh composer in a visible frame and focus it, so key events and popups behave. */
    private static ComposerArea inAFrame(ComposerArea.Source source) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "a completion popup and a focused key event need a graphics environment");
        ComposerArea area = new ComposerArea(source);
        onEdt(() -> {
            if (frame == null) {
                frame = new JFrame("naru composer test");
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                frame.setSize(700, 260);
                frame.setVisible(true);
            }
            frame.getContentPane().removeAll();
            frame.getContentPane().add(new JScrollPane(area));
            frame.getContentPane().revalidate();
            frame.getContentPane().repaint();
            area.requestFocusInWindow();
        });
        return area;
    }

    /** A source that always answers {@code r} and records the text and caret it was asked with. */
    private static ComposerArea.Source answering(Result r) {
        return (rawLine, caret) -> {
            askedWith.add(rawLine + "@" + caret);
            return r;
        };
    }

    private static Result resultAt(int start, int end, Candidate... candidates) {
        return new Result(List.of(candidates), "tok", start, end, false);
    }

    /**
     * A candidate row as plain text. The list paints its rows with markup — the insert coloured, the
     * engine's description greyed — so an assertion about <i>which</i> row is selected has to look
     * past the styling it is not about.
     */
    private static String plain(String label) {
        return label == null ? null : label.replaceAll("<[^>]*>", "").replace("&amp;", "&").trim();
    }

    /**
     * Swing focus is asynchronous, and a WHEN_FOCUSED binding only fires for the component that
     * actually owns it — so "wait until it does" is part of the test, not a convenience. Without a
     * window manager (Xvfb here) the request can take a cycle or two to land.
     */
    private static void awaitFocus(ComposerArea area) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!onEdtReturn(area::isFocusOwner) && System.currentTimeMillis() < deadline) {
            onEdt(() -> {
                frame.toFront();
                frame.requestFocus();
                area.requestFocusInWindow();
            });
            Thread.sleep(25);
        }
        assertTrue(onEdtReturn(area::isFocusOwner), "the composer never became the focus owner");
    }

    private static void press(ComposerArea area, int keyCode, int modifiers) throws Exception {
        awaitFocus(area);
        onEdt(() -> {
            assertTrue(area.isFocusOwner(), "the composer must hold focus to be handed a key");
            KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(
                    new KeyEvent(area, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                            modifiers, keyCode, KeyEvent.CHAR_UNDEFINED));
        });
    }

    private static void type(ComposerArea area, char c) throws Exception {
        awaitFocus(area);
        onEdt(() -> {
            KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            long now = System.currentTimeMillis();
            kfm.dispatchEvent(new KeyEvent(area, KeyEvent.KEY_PRESSED, now, 0,
                    KeyEvent.getExtendedKeyCodeForChar(c), c));
            kfm.dispatchEvent(new KeyEvent(area, KeyEvent.KEY_TYPED, now, 0,
                    KeyEvent.VK_UNDEFINED, c));
        });
    }

    @Test
    @Order(1)
    void ctrlSpaceAndTabBothAskTheEngineAtTheCaret() throws Exception {
        askedWith.clear();
        ComposerArea area = inAFrame(answering(resultAt(0, 0,
                new Candidate("ollama/qwen2.5-coder:14b", "local model", Kind.KEYWORD))));
        assertEquals("naru-complete", onEdtReturn(() -> area.getInputMap(JComponent.WHEN_FOCUSED)
                .get(KeyStroke.getKeyStroke("control SPACE"))), "Ctrl+Space is the key the plan asks for");
        assertEquals("naru-complete", onEdtReturn(() -> area.getInputMap(JComponent.WHEN_FOCUSED)
                .get(KeyStroke.getKeyStroke("TAB"))), "and Tab, as it has always been in readline");

        onEdt(() -> {
            area.setText("/model use ");
            area.setCaretPosition(area.getText().length());
        });
        press(area, KeyEvent.VK_SPACE, KeyEvent.CTRL_DOWN_MASK);
        assertTrue(onEdtReturn(area::isCompletionVisible), "Ctrl+Space opened the candidate list");
        assertEquals("/model use @11", askedWith.get(askedWith.size() - 1),
                "asked with the whole text and the caret, which is the seam NaruSessionModel exposes");
        assertEquals(1, onEdtReturn(area::completionCount));
        String label = onEdtReturn(() -> area.completionLabelAt(0));
        assertTrue(label.contains("ollama/qwen2.5-coder:14b"), "the insert text is on the row: " + label);
        assertTrue(label.contains("local model"), "and so is the engine's description: " + label);
    }

    @Test
    @Order(2)
    void acceptReplacesExactlyTheSpanTheEngineNamed() throws Exception {
        // "/cat /ro" with the caret at 8: the engine's token is "/ro" at [5,8) and the answer "/root/"
        ComposerArea area = inAFrame(answering(resultAt(5, 8,
                new Candidate("/root/", "directory", Kind.FILE))));
        onEdt(() -> {
            area.setText("/cat /ro");
            area.setCaretPosition(8);
            area.requestCompletion();
        });
        assertTrue(onEdtReturn(area::isCompletionVisible), "the list is up");
        press(area, KeyEvent.VK_ENTER, 0);
        assertEquals("/cat /root/", onEdtReturn(area::getText),
                "the span the engine named is the span that went away — Enter did not send, it completed");
        assertEquals(11, onEdtReturn(area::getCaretPosition), "caret lands after what was inserted");
        assertFalse(onEdtReturn(area::isCompletionVisible), "and the list closes on the way out");
    }

    @Test
    @Order(3)
    void upAndDownMoveThroughTheListAndEnterTakesThatRow() throws Exception {
        ComposerArea area = inAFrame(answering(resultAt(0, 0,
                new Candidate("first", null, Kind.SUBCOMMAND),
                new Candidate("second", null, Kind.SUBCOMMAND),
                new Candidate("third", null, Kind.SUBCOMMAND))));
        onEdt(area::requestCompletion);
        assertEquals("first", plain(onEdtReturn(area::selectedCompletionLabel)), "the list opens on the first row");
        press(area, KeyEvent.VK_DOWN, 0);
        press(area, KeyEvent.VK_DOWN, 0);
        assertEquals("third", plain(onEdtReturn(area::selectedCompletionLabel)));
        press(area, KeyEvent.VK_DOWN, 0);
        assertEquals("third", plain(onEdtReturn(area::selectedCompletionLabel)),
                "at the end of the list Down holds rather than wrapping out from under the user");
        press(area, KeyEvent.VK_ENTER, 0);
        assertEquals("third", onEdtReturn(area::getText),
                "Enter takes the highlighted row, not the first one — the old menu popup could not");
    }

    @Test
    @Order(4)
    void noCandidatesMeansNothingOnScreen() throws Exception {
        ComposerArea area = inAFrame(answering(Result.none("", 0, 0)));
        onEdt(() -> {
            area.setText("hello");
            area.setCaretPosition(5);
            area.requestCompletion();
        });
        assertFalse(onEdtReturn(area::isCompletionVisible),
                "an empty engine result is no popup, not an empty one");
        assertEquals(0, onEdtReturn(area::lastCompletionCount));
    }

    @Test
    @Order(5)
    void editingAfterARequestClosesTheListBecauseTheSpanIsStale() throws Exception {
        ComposerArea area = inAFrame(answering(resultAt(6, 6,
                new Candidate("value", null, Kind.KEYWORD))));
        onEdt(() -> {
            area.setText("set key ");
            area.setCaretPosition(8);
            area.requestCompletion();
        });
        assertTrue(onEdtReturn(area::isCompletionVisible));
        type(area, 'x');
        assertFalse(onEdtReturn(area::isCompletionVisible),
                "the typed edit moved every offset, so the remembered span no longer describes the text");
        assertEquals("set key x", onEdtReturn(area::getText), "and the keystroke itself still arrived");
    }

    @Test
    @Order(6)
    void historyIsOnlyAtTheEndsOfADraft() throws Exception {
        ComposerArea area = inAFrame(answering(Result.none("", 0, 0)));
        onEdt(() -> {
            area.setHistory(() -> "previous line", () -> "next line");
            area.setText("one\ntwo\nthree");
            area.setCaretPosition(area.getText().indexOf("two") + 1);
        });
        press(area, KeyEvent.VK_UP, 0);
        assertEquals(0, onEdtReturn(() -> area.getLineOfOffset(area.getCaretPosition())),
                "inside a multi-line draft Up moved the caret to the line above; it did not replace the text");
        assertEquals("one\ntwo\nthree", onEdtReturn(area::getText));
        press(area, KeyEvent.VK_UP, 0);
        assertEquals("previous line", onEdtReturn(area::getText),
                "at the first line, Up is the history recall it has always been");
    }

    @Test
    @Order(7)
    void enterSendsAndShiftEnterIsANewline() throws Exception {
        ComposerArea area = inAFrame(answering(Result.none("", 0, 0)));
        AtomicInteger sent = new AtomicInteger();
        onEdt(() -> {
            area.setOnSend(sent::incrementAndGet);
            area.setText("a line");
        });
        press(area, KeyEvent.VK_ENTER, 0);
        assertEquals(1, sent.get(), "Enter sends");
        press(area, KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK);
        assertEquals("a line\n", onEdtReturn(area::getText), "Shift+Enter is a newline, as §3.4 has it");
    }

    @Test
    @Order(8)
    void undoTakesBackTheLastEdit() throws Exception {
        ComposerArea area = inAFrame(answering(resultAt(8, 8,
                new Candidate("value", null, Kind.KEYWORD))));
        assertEquals("naru-undo", onEdtReturn(() -> area.getInputMap(JComponent.WHEN_FOCUSED)
                .get(KeyStroke.getKeyStroke(java.awt.Toolkit.getDefaultToolkit()
                        .getMenuShortcutKeyMaskEx() == KeyEvent.CTRL_DOWN_MASK
                        ? "control BACK_SPACE" : "meta BACK_SPACE"))));
        onEdt(() -> {
            area.setText("set key ");
            area.setCaretPosition(8);
            area.requestCompletion();
            area.acceptCompletion();
        });
        assertEquals("set key value", onEdtReturn(area::getText));
        onEdt(area::undoLast);
        assertEquals("set key ", onEdtReturn(area::getText),
                "a completion the user did not want is one keystroke from being gone");
    }

    /**
     * The invariant the first real-window run broke: opening the list must not create a window.
     * <p>
     * A {@code JPopupMenu} that cannot be drawn inside its invoker's window is promoted by Swing to a
     * heavyweight top-level, and on X11 that window takes the keyboard. The desktop composer is
     * pinned to the bottom of the frame and its list is up to ten rows tall, so the promotion
     * happened on every real completion: Enter, Shift+Enter and every key after them went to the
     * popup instead, and Accept never ran. The frame here is sized so the list cannot fit below the
     * caret, which is the case the widget has to solve by moving rather than by growing a window.
     */
    @Test
    @Order(9)
    void theListIsDrawnInsideTheWindowAndNeverInItsOwn() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "window geometry needs a graphics environment");
        List<Candidate> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(new Candidate("/cmd" + i, "/cmd" + i + " — long enough to measure the width",
                    Kind.DIRECTIVE));
        }
        Result r = new Result(many, "/cmd", 0, 4, false);
        ComposerArea area = new ComposerArea((line, caret) -> r);
        JFrame low = new JFrame("naru composer at the bottom edge");
        try {
            int windowsBefore = onEdtReturn(() -> {
                low.setLayout(new java.awt.BorderLayout());
                low.setSize(520, 400);
                low.getContentPane().add(new JScrollPane(area), java.awt.BorderLayout.SOUTH);
                low.setVisible(true);
                area.setText("/cmd");
                area.setCaretPosition(4);
                return java.awt.Window.getWindows().length;
            });
            onEdt(area::requestCompletion);
            assertTrue(onEdtReturn(area::isCompletionVisible), "the list opened");
            java.awt.Rectangle b = onEdtReturn(area::completionBounds);
            int paneHeight = onEdtReturn(() -> low.getRootPane().getLayeredPane().getHeight());
            assertNotNull(b, "the card is in the window's layered pane");
            assertTrue(b.y >= 0 && b.y + b.height <= paneHeight,
                    "the list hung above the caret instead of past the window's bottom edge: "
                            + b + " in a layered pane " + paneHeight + " tall");
            assertEquals("JFrame", onEdtReturn(area::completionHostWindow),
                    "the list lives in the frame the composer is in");
            assertEquals(windowsBefore, onEdtReturn(() -> java.awt.Window.getWindows().length),
                    "opening the list created a top-level window, which takes the keyboard");
        } finally {
            onEdt(low::dispose);
        }
    }

    /**
     * The mouse path, which no keyboard harness can see. {@code BasicListUI} calls
     * {@code SwingUtilities2.adjustFocus(list)} on mouse press, and its condition is
     * {@code !hasFocus() && isRequestFocusEnabled()} — so a list that is merely not focusable still
     * asks for the keyboard, and Swing answers by moving it to the next focusable component in the
     * window. In the real main window that was a {@code JComboBox}: after one click, Enter fired the
     * button instead of accepting the row, and the composer looked dead.
     */
    @Test
    @Order(10)
    void aClickSelectsTheRowAndNeverMovesTheKeyboard() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "a click that must not steal the keyboard needs a real focused window");
        Candidate[] rows = new Candidate[12];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = new Candidate("/c" + i, "candidate number " + i, Kind.DIRECTIVE);
        }
        ComposerArea area = inAFrame(answering(resultAt(0, 2, rows)));
        javax.swing.JButton below = new javax.swing.JButton("send");
        onEdt(() -> {
            java.awt.Container cp = frame.getContentPane();
            cp.setLayout(new java.awt.BorderLayout());
            cp.removeAll();
            cp.add(new JScrollPane(area), java.awt.BorderLayout.CENTER);
            cp.add(below, java.awt.BorderLayout.SOUTH);
            cp.revalidate();
            cp.repaint();
            area.setText("/c");
            area.setCaretPosition(2);
        });
        awaitFocus(area);
        onEdt(area::requestCompletion);
        assertTrue(onEdtReturn(area::isCompletionVisible), "the list opened");
        // The card asks for layout; a real user's reaction time flushes that queue for free, a test
        // that clicks in the same millisecond has to flush it itself or it aims at nothing.
        onEdt(() -> area.getRootPane().validate());
        JList<?> list = candidateList(area);
        assertNotNull(list, "the list the widget drew is the one the click has to reach");
        // The exact condition SwingUtilities2.adjustFocus tests on mouse press. A synthetic
        // MouseEvent does not go through the native focus renegotiation a real click does, so this
        // test cannot observe the theft itself — ComposerMouseSmoke drives a real Robot click for
        // that — but it can pin the half of the JDK condition the widget is responsible for.
        assertFalse(list.isRequestFocusEnabled(),
                "a focusable-in-request list asks Swing for the keyboard on mouse press, and Swing"
                        + " answers by moving it to the next focusable component in the window");
        assertFalse(list.isFocusable(), "the list must not be a focus destination either");
        assertNotNull(list, "the list the widget drew is the one the click has to reach");

        click(list, 2, 1);
        assertEquals("/c2", onEdtReturn(area::selectedCompletionInsert),
                "a single click highlights the row the user aimed at");
        assertEquals("/c", onEdtReturn(area::getText), "one click chooses; it does not commit");
        assertTrue(onEdtReturn(area::isCompletionVisible),
                "the list stays open so the choice can be seen before it is taken");
        assertFalse(onEdtReturn(() -> below.isFocusOwner()),
                "the click handed the keyboard to the button below the composer");
        assertTrue(onEdtReturn(area::isFocusOwner),
                "the composer must still hold the keyboard after a click");

        click(list, 2, 2);
        assertEquals("/c2", onEdtReturn(area::getText), "the second click commits, like Enter does");
        assertFalse(onEdtReturn(area::isCompletionVisible), "committing closes the list");
        assertTrue(onEdtReturn(area::isFocusOwner),
                "committing with the mouse leaves the caret where the user is typing");
        assertFalse(onEdtReturn(() -> below.isFocusOwner()), "and still never moves the keyboard");
    }

    /**
     * Find the JList the popup actually put in the window. A card stays in the layered pane once it
     * has been shown, so a frame reused by several tests can hold more than one — the showing one is
     * the list a click can land on.
     */
    private static JList<?> candidateList(ComposerArea area) throws Exception {
        return onEdtReturn(() -> findList(area.getRootPane().getLayeredPane()));
    }

    private static JList<?> findList(java.awt.Container c) {
        for (java.awt.Component x : c.getComponents()) {
            if (x instanceof JList<?> l && l.isShowing() && l.getModel().getSize() > 0
                    && l.getModel().getElementAt(0) instanceof Candidate) {
                return l;
            }
            if (x instanceof java.awt.Container inner) {
                JList<?> found = findList(inner);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * A real mouse gesture through the event system, not a call to the listener that handles it. The
     * row's y comes from the list's own fixed cell height rather than {@code getCellBounds}, because
     * that is the arithmetic the click hit-test uses and it does not depend on the layout queue
     * having run yet — which a real user's reaction time settles and a test cannot wait for.
     */
    private static void click(JList<?> list, int row, int clicks) throws Exception {
        onEdt(() -> {
            assertTrue(row < list.getModel().getSize(), "the list holds " + row + " rows or fewer");
            long now = System.currentTimeMillis();
            int x = 6;
            int y = (int) (row * list.getFixedCellHeight() + list.getFixedCellHeight() / 2);
            list.dispatchEvent(new java.awt.event.MouseEvent(list,
                    java.awt.event.MouseEvent.MOUSE_PRESSED, now, 0, x, y, clicks, false));
            list.dispatchEvent(new java.awt.event.MouseEvent(list,
                    java.awt.event.MouseEvent.MOUSE_RELEASED, now, 0, x, y, clicks, false));
            list.dispatchEvent(new java.awt.event.MouseEvent(list,
                    java.awt.event.MouseEvent.MOUSE_CLICKED, now, 0, x, y, clicks, false));
        });
    }

    /**
     * A source that narrows its own candidate set to the word before the caret, which is the shape
     * {@code NaruLineCompletion} answers with. The auto-open tests need a source whose answer depends
     * on the text: a constant answer cannot show the list getting smaller as the line grows.
     */
    private static ComposerArea.Source narrowing(String... commands) {
        return (rawLine, caret) -> {
            String before = rawLine.substring(0, Math.min(caret, rawLine.length()));
            int start = before.lastIndexOf(' ') + 1;
            String token = before.substring(start);
            List<Candidate> out = new ArrayList<>();
            for (String c : commands) {
                if (c.startsWith(token)) {
                    out.add(new Candidate(c, null, Kind.DIRECTIVE));
                }
            }
            return new Result(out, token, start, before.length(), false);
        };
    }

    @Test
    @Order(12)
    void typingASlashOpensTheCommandListAndEachKeystrokeNarrowsIt() throws Exception {
        ComposerArea area = inAFrame(narrowing("/model", "/map", "/settings"));
        type(area, '/');
        assertTrue(onEdtReturn(area::isCompletionVisible), "the slash alone opens the command list");
        assertEquals(3, onEdtReturn(area::completionCount));
        type(area, 'm');
        assertEquals(2, onEdtReturn(area::completionCount),
                "the list filtered to what the line still matches instead of closing on the edit");
        type(area, 'o');
        assertEquals(1, onEdtReturn(area::completionCount), "one row left, and the list stayed up");
        assertEquals("/model", plain(onEdtReturn(area::selectedCompletionLabel)));
        press(area, KeyEvent.VK_ENTER, 0);
        assertEquals("/model", onEdtReturn(area::getText), "Enter took the row, it did not send the line");
        assertFalse(onEdtReturn(area::isCompletionVisible),
                "committing closes the list — the word is finished, and a row that repeats what is"
                        + " already on the line is noise");
    }

    @Test
    @Order(13)
    void anEmptyFieldStaysQuietUntilTheUserAsks() throws Exception {
        ComposerArea area = inAFrame(narrowing("/model", "/map"));
        assertFalse(onEdtReturn(area::isCompletionVisible),
                "a fresh composer must not paint every command the engine knows just because it"
                        + " gained focus — an empty field is the engine's whole directive list");
        press(area, KeyEvent.VK_SPACE, KeyEvent.CTRL_DOWN_MASK);
        assertTrue(onEdtReturn(area::isCompletionVisible),
                "Ctrl+Space is the explicit ask, and it is not gated on the line looking like a command");
        assertEquals(2, onEdtReturn(area::completionCount));
    }

    /**
     * A source with a candidate for whatever word sits under the caret, prose included. The auto-open
     * gate reads the engine's token, so a source that answers with a constant token cannot show the
     * difference between a slash and a letter.
     */
    private static ComposerArea.Source alwaysAnswering() {
        return (rawLine, caret) -> {
            String before = rawLine.substring(0, Math.min(caret, rawLine.length()));
            int start = before.lastIndexOf(' ') + 1;
            String token = before.substring(start);
            return new Result(List.of(new Candidate(token + "-x", null, Kind.FILE)),
                    token, start, before.length(), false);
        };
    }

    @Test
    @Order(14)
    void proseNeverOpensTheListEvenWhenTheSourceHasACandidate() throws Exception {
        ComposerArea area = inAFrame(alwaysAnswering());
        type(area, 'h');
        type(area, 'i');
        assertFalse(onEdtReturn(area::isCompletionVisible),
                "the engine had a row to offer but the line is prose for the model, so nothing opened");
        onEdt(() -> {
            area.setText("");
            area.setCaretPosition(0);
        });
        type(area, '/');
        assertTrue(onEdtReturn(area::isCompletionVisible), "the slash is what turns it on");
    }

    @Test
    @Order(15)
    void escapeSilencesTheWordUntilTheNextKeystroke() throws Exception {
        ComposerArea area = inAFrame(narrowing("/model", "/map"));
        type(area, '/');
        assertTrue(onEdtReturn(area::isCompletionVisible));
        press(area, KeyEvent.VK_ESCAPE, 0);
        assertFalse(onEdtReturn(area::isCompletionVisible), "Escape closes the list");
        type(area, 'm');
        assertTrue(onEdtReturn(area::isCompletionVisible),
                "Escape dismisses this word, it does not switch completion off for the session");
    }

    @Test
    @Order(11)
    void theRealEngineAnswersForTheRealComposer() throws Exception {
        NPath dir = NPath.ofTempFolder("naru-desktop-composer");
        dir.mkdirs();
        dir.resolve("notes.txt").writeString("hello\n");
        InteractionHost host = new InteractionHost();
        host.startFlushTimer();
        NaruSessionModel model = new NaruSessionModel(
                () -> NaruWorkspaceBuilder.of().projectDirectory(dir).build(), dir, host);
        onEdt(model::start);
        long deadline = System.currentTimeMillis() + 180_000;
        while (model.session() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertNotNull(model.session(), "the desktop session never booted: " + model.statusText());
        try {
            ComposerArea area = inAFrame(model::complete);
            onEdt(() -> {
                area.setText("/mod");
                area.setCaretPosition(4);
                area.requestCompletion();
            });
            assertTrue(onEdtReturn(area::completionCount) > 0,
                    "the engine's own NaruLineCompletion answered for a partial directive");
            List<String> labels = new ArrayList<>();
            onEdt(() -> {
                for (int i = 0; i < area.completionCount(); i++) {
                    labels.add(plain(area.completionLabelAt(i)));
                }
            });
            assertTrue(labels.stream().anyMatch(l -> l.startsWith("/model")),
                    "and one candidate is /model: " + labels);
            assertTrue(onEdtReturn(area::lastCompletionMicros) < 2_000_000,
                    "one directive listing is not a freeze: " + onEdtReturn(area::lastCompletionMicros)
                            + " us on the EDT");
            press(area, KeyEvent.VK_TAB, 0);
            assertTrue(onEdtReturn(area::isCompletionVisible), "Tab asks too, as in readline");
            // the engine's own answer for "/mod" is what has to come back — which directive wins is
            // its business, so the assertion is against the highlighted row, not a hard-coded name
            String wanted = onEdtReturn(area::selectedCompletionInsert);
            press(area, KeyEvent.VK_ENTER, 0);
            assertEquals(wanted, onEdtReturn(area::getText),
                    "Enter left a real directive on the line, not a hand-edited string");
        } finally {
            onEdt(model::shutdown);
        }
    }
}
