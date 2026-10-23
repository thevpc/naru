package net.thevpc.naru.desktop.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hover has to be crossed, not switched: the row under the pointer brightens over a few ticks and
 * dims back out, and the motion stops the moment it reaches either end, so a column nobody is
 * reading costs no repaints at all.
 * <p>
 * The pointer here is one the test places ({@link HoverFader#pointerAt}), because the fade asks that
 * question rather than trusting the events — and a test run has no hand on a mouse.
 */
class HoverFaderTest {

    private static final Color HUE = new Color(0x2E, 0x7D, 0x32);
    private static final Color SURFACE = Color.WHITE;

    /** The rows the test pointer is sitting on. Weak so a discarded row stops being hovered. */
    private static final Set<JComponent> UNDER =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    private HoverFader.Pointer realPointer;

    @BeforeEach
    void holdThePointer() {
        realPointer = HoverFader.pointerAt;
        HoverFader.pointerAt = UNDER::contains;
    }

    @AfterEach
    void giveTheMouseBack() {
        HoverFader.pointerAt = realPointer;
        UNDER.clear();
    }

    @Test
    void aComponentThePointerNeverTouchedIsNotHovered() throws Exception {
        JPanel row = new JPanel();
        onEdt(() -> HoverFader.install(row));
        assertEquals(0f, ask(() -> HoverFader.level(row)), "no hover before the pointer arrives");
        assertFalse(ask(() -> HoverFader.fading(row)), "and nothing ticking yet");
    }

    @Test
    void theFadeWalksUpToThePointerAndThenStopsRepainting() throws Exception {
        CountingRow row = new CountingRow();
        install(row);
        move(row, true);
        await("the row to light up", () -> HoverFader.level(row) >= 1f);
        int painted = row.paints.get();
        Thread.sleep(400);
        assertEquals(painted, row.paints.get(), "a row that is fully lit asks for no more repaints");
        assertTrue(ask(() -> HoverFader.fading(row)), "but it keeps watching the pointer");
    }

    @Test
    void theFadeWalksBackDownWhenThePointerLeaves() throws Exception {
        CountingRow row = new CountingRow();
        install(row);
        move(row, true);
        await("the row to light up", () -> HoverFader.level(row) > 0.3f);
        move(row, false);
        await("the row to dim out", () -> HoverFader.level(row) <= 0f);
        await("the ticking to stop at the bottom", () -> !HoverFader.fading(row));
    }

    /** A mid-crossing exit turns the fade around instead of throwing it to the far end. */
    @Test
    void aPointerThatLeavesEarlyFadesFromWhereItStands() throws Exception {
        JPanel row = new JPanel();
        install(row);
        move(row, true);
        await("the fade to start", () -> HoverFader.level(row) > 0f);
        float turned = ask(() -> HoverFader.level(row));
        move(row, false);
        await("the row to dim out", () -> HoverFader.level(row) <= 0f);
        assertTrue(turned < 1f, "the exit was taken mid-crossing, got " + turned);
    }

    /**
     * The failure this exists for: a pointer that leaves without the row ever hearing about it —
     * warped out of the window, or hidden under a scroll. Only the row's own question catches it.
     */
    @Test
    void aRowDimsOutEvenWhenNoExitEventEverArrives() throws Exception {
        JPanel row = new JPanel();
        install(row);
        move(row, true);
        await("the row to light up", () -> HoverFader.level(row) >= 1f);
        UNDER.remove(row);
        await("the row to notice on its own", () -> HoverFader.level(row) <= 0f);
        assertFalse(ask(() -> HoverFader.fading(row)), "and then stop watching");
    }

    @Test
    void aRowUnderThePointerPaintsMoreThanARowNobodyIsOn() throws Exception {
        JPanel idle = new JPanel();
        JPanel hot = new JPanel();
        onEdt(() -> {
            HoverFader.install(idle);
            HoverFader.install(hot);
        });
        move(hot, true);
        await("the hover to cross", () -> HoverFader.level(hot) >= 1f);
        assertNotEquals(paint(hot, 60), paint(idle, 60), "the hovered row is set apart from its neighbour");
        // the gutter thickens into the row instead of pushing the row's text aside
        assertEquals(UiPalette.readable(HUE), paint(hot, TranscriptLayer.STRIPE + 1));
        assertEquals(SURFACE, paint(idle, TranscriptLayer.STRIPE + 1), "an idle gutter keeps its 3px");
    }

    /**
     * A row's body is a text pane, and a text pane consumes the mouse events it is handed, so they
     * never reach the row by bubbling. The fader has to be listening where the events stop.
     */
    @Test
    void aPointerOverTheBodyInsideARowStillBelongsToTheRow() throws Exception {
        JPanel row = new JPanel(null);
        JLabel body = new JLabel("the row's text");
        row.add(body);
        onEdt(() -> {
            row.setSize(120, 40);
            body.setBounds(10, 10, 60, 20);
            HoverFader.install(row);
        });
        UNDER.add(row);
        onEdt(() -> {
            MouseEvent moved = new MouseEvent(body, MouseEvent.MOUSE_MOVED,
                    System.currentTimeMillis(), 0, 20, 15, 0, false);
            moved.consume();
            for (MouseMotionListener listener : body.getMouseMotionListeners()) {
                listener.mouseMoved(moved);
            }
        });
        await("the row to light up from its own body", () -> HoverFader.level(row) >= 1f);
    }

    private static void install(JComponent row) throws Exception {
        onEdt(() -> HoverFader.install(row));
    }

    /** Put the pointer on or off the row and hand it the event a crossing would have produced. */
    private static void move(JComponent row, boolean inside) throws Exception {
        if (inside) {
            UNDER.add(row);
        } else {
            UNDER.remove(row);
        }
        onEdt(() -> {
            MouseEvent moved = new MouseEvent(row, MouseEvent.MOUSE_MOVED,
                    System.currentTimeMillis(), 0, 4, 4, 0, false);
            for (MouseMotionListener listener : row.getMouseMotionListeners()) {
                listener.mouseMoved(moved);
            }
        });
    }

    /** A row that counts the repaints it is asked for, which is what a settled hover must not do. */
    private static final class CountingRow extends JPanel {
        final AtomicInteger paints = new AtomicInteger();

        @Override
        public void repaint() {
            if (paints != null) {
                paints.incrementAndGet();
            }
            super.repaint();
        }
    }

    /** The colour {@code x} ends up with when the row is painted at its laid-out size. */
    private static Color paint(JComponent component, int x) throws Exception {
        BufferedImage image = new BufferedImage(120, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(SURFACE);
        g.fillRect(0, 0, 120, 40);
        onEdt(() -> {
            component.setSize(120, 40);
            TranscriptLayer.paint(component, g, HUE, false);
        });
        g.dispose();
        return new Color(image.getRGB(x, 20) & 0xFFFFFF);
    }

    private static <T> T ask(Callable<T> work) throws Exception {
        final Object[] box = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                box[0] = work.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        @SuppressWarnings("unchecked")
        T value = (T) box[0];
        return value;
    }

    private static void onEdt(Runnable work) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            work.run();
        } else {
            SwingUtilities.invokeAndWait(work);
        }
    }

    private static void await(String what, Callable<Boolean> done) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(ask(done))) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
