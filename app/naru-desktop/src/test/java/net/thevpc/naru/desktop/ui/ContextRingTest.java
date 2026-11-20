package net.thevpc.naru.desktop.ui;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The context gauge: what it accepts as a reading, and how it moves between readings.
 * <p>
 * The gauge is the one widget in the status row that animates itself, and an animation that never
 * ends is a CPU that never idles with the app closed and the reader away. So the tests are about
 * the two ways it can go wrong: a ring that keeps ticking after it arrived, and a ring sized in
 * pixels that stops matching the text beside it when the interface size changes.
 */
class ContextRingTest {

    @Test
    void theGaugeKeepsPaceWithTheInterfaceSize() throws Exception {
        ContextRing ring = new ContextRing();
        Dimension small = onEdtReturn(ring::getPreferredSize);
        onEdt(() -> ring.setFont(ring.getFont().deriveFont(Font.PLAIN, 26f)));
        Dimension large = onEdtReturn(ring::getPreferredSize);
        assertTrue(large.height > small.height, "a bigger interface size gets a bigger gauge: " + small + " -> " + large);
    }

    @Test
    void aReadingOutsideTheRingIsClampedToIt() throws Exception {
        ContextRing ring = new ContextRing();
        onEdt(() -> {
            assertEquals(ContextRing.UNKNOWN, ring.getFraction(), "no model call has been measured yet");
            ring.setFraction(1.7);
            assertEquals(1.0, ring.getFraction(), "a window cannot be more than full");
            ring.setFraction(-0.5);
            assertEquals(ContextRing.UNKNOWN, ring.getFraction(), "a negative reading is not a reading");
        });
    }

    @Test
    void theRingArrivesAtItsReadingAndThenStopsMoving() throws Exception {
        ContextRing ring = new ContextRing();
        onEdt(() -> ring.setFraction(0.5));
        await(() -> !ring.animating(), "the animation stopped once the ring arrived");
        onEdt(() -> {
            assertEquals(0.5, ring.shown(), 0.005, "the ring drew the whole reading it was given");
            assertFalse(ring.animating(), "and it is not still ticking");
        });
        // a second reading starts the clock again: the gauge moving is the point, a gauge that
        // snaps is what the reader watches a stream do token by token
        onEdt(() -> ring.setFraction(0.9));
        assertTrue(onEdtReturn(ring::animating), "a new reading starts a new move");
        await(() -> !ring.animating(), "the second reading arrives too");
        onEdt(() -> assertEquals(0.9, ring.shown(), 0.005));
    }

    @Test
    void anUnmeasuredGaugeDropsItsArcButKeepsSweepingWhileTheSessionWorks() throws Exception {
        ContextRing ring = new ContextRing();
        onEdt(() -> {
            ring.setFraction(0.4);
            ring.setFraction(ContextRing.UNKNOWN);
            assertEquals(0.0, ring.shown(), 0.005, "no reading means no arc, not the last one kept");
        });
        onEdt(() -> ring.setLive(true));
        assertTrue(onEdtReturn(ring::animating), "a live session keeps the sweep moving");
        onEdt(() -> ring.setLive(false));
        await(() -> !ring.animating(), "and an idle one stops it");
    }

    @Test
    void theGaugeRefusesToBeSqueezedByItsRow() throws Exception {
        ContextRing ring = new ContextRing();
        Dimension preferred = onEdtReturn(ring::getPreferredSize);
        // the bar's own UI installs a maximum height of zero for a horizontal bar, and a box row
        // honours that: the gauge was left with a sliver of its own top edge to draw in
        assertEquals(preferred, onEdtReturn(ring::getMaximumSize), "the row cannot stretch or shrink it");
        assertEquals(preferred, onEdtReturn(ring::getMinimumSize), "nor clip it");
    }

    @Test
    void theGaugeNamesItselfForAReader() {
        ContextRing ring = new ContextRing();
        // a bare JComponent returns a null accessible context until it is asked for one, and the
        // status row names its gauge the moment it builds — naming null throws on the EDT
        assertNotNull(ring.getAccessibleContext(), "the gauge is reachable by a screen reader");
        ring.getAccessibleContext().setAccessibleName("Context usage");
        assertEquals("Context usage", ring.getAccessibleContext().getAccessibleName());
    }

    private static void onEdt(Runnable body) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            body.run();
            return;
        }
        SwingUtilities.invokeAndWait(body);
    }

    private static <T> T onEdtReturn(java.util.concurrent.Callable<T> body) throws Exception {
        Object[] box = new Object[1];
        onEdt(() -> {
            try {
                box[0] = body.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        @SuppressWarnings("unchecked") T value = (T) box[0];
        return value;
    }

    private static void await(java.util.function.BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        org.junit.jupiter.api.Assertions.fail(what + " (still false after 5s)");
    }
}
