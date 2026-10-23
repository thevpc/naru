package net.thevpc.naru.desktop.ui;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dot that breathes says the state behind it is still happening. That claim has to be paid for and
 * given back: the halo widens while the work runs, and the circle goes still and stops claiming
 * motion the moment the state settles.
 */
class StatusDotTest {

    @Test
    void theBreathGoesAwayWhenTheStateSettles() throws Exception {
        StatusDot dot = new StatusDot();
        onEdt(() -> dot.setPulsing(true));
        await("the halo to reach its widest", () -> dot.pulseLevel() > 0.9);
        onEdt(() -> dot.setPulsing(false));
        assertEquals(0d, ask(dot::pulseLevel), "a settled state has no motion to claim");
    }

    @Test
    void theBreathPaintsALargerHaloThanTheStillDot() throws Exception {
        StatusDot dot = new StatusDot();
        dot.setSize(24, 20);
        int still = litPixels(dot);
        onEdt(() -> dot.setPulsing(true));
        await("the halo to reach its widest", () -> dot.pulseLevel() > 0.95);
        int wide = litPixels(dot);
        onEdt(() -> dot.setPulsing(false));
        assertTrue(wide > still, "the breath must be visible, got " + wide + " over " + still);
    }

    /** How many pixels the dot lights at the breath level it is currently holding. */
    private static int litPixels(StatusDot dot) throws Exception {
        BufferedImage image = new BufferedImage(24, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        onEdt(() -> dot.paintComponent(g));
        g.dispose();
        int lit = 0;
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 24; x++) {
                if ((image.getRGB(x, y) >>> 24) > 0) {
                    lit++;
                }
            }
        }
        return lit;
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
