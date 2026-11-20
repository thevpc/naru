package net.thevpc.naru.desktop.ui;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseMotionListener;

/**
 * The fade between "the pointer is on this" and "it is not".
 * <p>
 * A hover state that snaps reads as the interface flinching; the same state crossed in about 150 ms
 * reads as the row answering the cursor. That is the only reason this exists — a hover that has to
 * be animated by hand, because a Swing component either paints its rollover or it does not.
 * <p>
 * The fade is kept on the component as a client property, so painters read it where they already
 * paint ({@link #level}) and no listener has to be told about a rebuild.
 * <p>
 * An exit cannot be trusted to arrive. A pointer that is warped out of the window, or a row that
 * disappears under a scroll, leaves the component with no event at all and a hover lit forever, so
 * while a row is lit the fader keeps asking where the pointer actually is. That question is cheap
 * and it is the only one whose answer is the truth: listeners report crossings, and crossings are
 * only a guess about the pointer's position.
 */
public final class HoverFader {

    private static final String LEVEL = "naru.hover.level";
    private static final String TARGET = "naru.hover.target";
    private static final String TIMER = "naru.hover.timer";

    /** ~60 Hz while the level is moving: fast enough to feel instant, slow enough to see. */
    private static final int TICK_MS = 16;
    /** Where the pointer sits once the row is fully lit: watching, not animating. */
    private static final int SETTLE_MS = 120;
    private static final float STEP = 1f / 9f;

    /** Where the pointer is. A seam so the fade can be tested without a mouse attached. */
    static Pointer pointerAt = HoverFader::pointerUnderMouse;

    interface Pointer {
        /** True while the pointer sits inside {@code component}. */
        boolean inside(JComponent component);
    }

    private HoverFader() {
    }

    /** Track the pointer over {@code component}, or anywhere inside it, and fade its level. */
    public static void install(JComponent component) {
        // The boundary is the component's own: entered and exited say when the pointer crosses it.
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                aim(component);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                aim(component);
            }
        });
        // Motion is what actually arrives. A row's body is a text pane, and a text pane consumes the
        // mouse events it is given, so they never bubble up to the row that holds it — the hover of
        // everything under it would be invisible, which is most of the row. Listening on the
        // descendants instead asks the same question the boundary would have: is the point inside?
        MouseMotionListener moved = new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                aim(component);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                aim(component);
            }
        };
        watch(component, moved);
        aim(component);
    }

    private static void watch(JComponent component, MouseMotionListener motion) {
        component.addMouseMotionListener(motion);
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                if (child instanceof JComponent nested) {
                    watch(nested, motion);
                }
            }
        }
    }

    /** How far into the hover this component is: 0 when the pointer never came, 1 when it is over it. */
    public static float level(JComponent component) {
        return component.getClientProperty(LEVEL) instanceof Float f ? Math.max(0, Math.min(1, f)) : 0f;
    }

    /** True while the fader is watching this row, whether or not its level is still moving. */
    static boolean fading(JComponent component) {
        return component.getClientProperty(TIMER) instanceof Timer timer && timer.isRunning();
    }

    /**
     * Re-read the pointer and run the fade towards it. Every event calls this and none of them says
     * which side the pointer is on: the answer is taken from the geometry, so an event from a deep
     * child means the same thing as one from the row itself.
     */
    private static void aim(JComponent component) {
        boolean inside = pointerAt.inside(component);
        if (Boolean.valueOf(inside).equals(component.getClientProperty(TARGET))) {
            return;
        }
        component.putClientProperty(TARGET, inside);
        if (!inside && level(component) <= 0f) {
            // Nothing to fade: a row that was never lit does not need a timer to say so.
            return;
        }
        Timer timer = timer(component);
        timer.setDelay(TICK_MS);
        timer.start();
    }

    private static Timer timer(JComponent component) {
        if (component.getClientProperty(TIMER) instanceof Timer running) {
            return running;
        }
        Timer created = new Timer(TICK_MS, e -> step(component));
        component.putClientProperty(TIMER, created);
        return created;
    }

    private static void step(JComponent component) {
        // The pointer is re-asked each tick rather than trusted from the last event, which is what
        // dims a row whose exit event never came.
        boolean inside = pointerAt.inside(component);
        component.putClientProperty(TARGET, inside);
        float level = level(component);
        float next = Math.max(0, Math.min(1, level + (inside ? STEP : -STEP)));
        component.putClientProperty(LEVEL, next);
        if (next != level) {
            component.repaint();
        }
        Timer timer = timer(component);
        if (next >= 1f) {
            // Lit and parked on: keep watching for a departure, but stop asking the row to repaint.
            timer.setDelay(SETTLE_MS);
        } else {
            timer.setDelay(TICK_MS);
            if (next <= 0f) {
                timer.stop();
            }
        }
    }

    private static boolean pointerUnderMouse(JComponent component) {
        if (GraphicsEnvironment.isHeadless() || !component.isShowing()) {
            return Boolean.TRUE.equals(component.getClientProperty(TARGET));
        }
        try {
            PointerInfo pointer = MouseInfo.getPointerInfo();
            if (pointer == null) {
                return Boolean.TRUE.equals(component.getClientProperty(TARGET));
            }
            Point at = pointer.getLocation();
            SwingUtilities.convertPointFromScreen(at, component);
            return component.contains(at);
        } catch (RuntimeException gone) {
            // A component mid-teardown cannot answer where it is; the next tick asks again.
            return Boolean.TRUE.equals(component.getClientProperty(TARGET));
        }
    }
}
