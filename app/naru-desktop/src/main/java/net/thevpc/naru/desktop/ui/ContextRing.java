package net.thevpc.naru.desktop.ui;

import javax.swing.JProgressBar;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;

/**
 * How full the model's context window is, drawn as a ring instead of a bar.
 * <p>
 * A bar in a status row is a shape the reader has to measure — it is long, so it takes width, and
 * its length says nothing until they notice where the fill stops. A ring says the same thing in the
 * diameter of a glyph: the arc is the proportion, and the round hole in the middle keeps it from
 * reading as a filled button.
 * <p>
 * Two things move it. The arc eases toward a new reading over a few frames, so a stream that adds
 * tokens each second grows rather than jumps — a bar that snaps is the same information and looks
 * like a redraw. And while the session is doing work, a short highlight travels around the track,
 * which is the only signal this gauge has that the number it is holding is current rather than the
 * last thing measured before the answer finished.
 * <p>
 * The diameter follows the font, so an interface-size change rescales it the way it rescales every
 * other control; a fixed pixel size is the one thing that would clip or float in that case.
 * <p>
 * It extends {@code JProgressBar} rather than {@code JComponent} for the reader who never sees it:
 * the bar's model is what an assistive technology asks for, and a bare custom component has no
 * accessible context at all — naming one throws a null pointer on the event thread. Every pixel of
 * the bar's own painting is replaced below; what is kept is the number.
 */
public final class ContextRing extends JProgressBar {

    /** Frames per second of the easing and the sweep: enough to look smooth, few enough to be free. */
    private static final int TICK_MS = 40;

    /** Proportion of the ring the travelling highlight covers while the session works. */
    private static final double SWEEP_DEGREES = 55;

    /** A reading this close to its target counts as reached, so the timer can stop. */
    private static final double SETTLED = 0.004;

    /** Unknown: no model call has been measured yet, so there is no arc to draw. */
    public static final double UNKNOWN = -1;

    private double target = UNKNOWN;
    private double shown;
    private boolean live;
    private double sweepDegrees;
    private final Timer timer;

    public ContextRing() {
        super(0, 100);
        setOpaque(false);
        setBorderPainted(false);
        setStringPainted(false);
        setFocusable(false);
        // a bare JComponent carries no font until something gives it one, and the diameter is
        // measured from a font: start from the interface's own and let the row this sits in replace
        // it with the size it actually uses
        Font base = UIManager.getFont("Label.font");
        setFont(base != null ? base : new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        // an unrealized component has no font of its own; ask for it now and re-measure on a change
        addPropertyChangeListener("font", e -> revalidate());
        timer = new Timer(TICK_MS, e -> step());
    }

    /** Move the arc to a new reading. A negative reading is {@link #UNKNOWN}: nothing was measured. */
    public void setFraction(double fraction) {
        double next = (Double.isNaN(fraction) || fraction < 0) ? UNKNOWN : Math.min(1, fraction);
        if (next == target) {
            return;
        }
        target = next;
        if (target == UNKNOWN) {
            shown = 0;
        }
        // the model is what an assistive technology reads; the arc below is only how it looks
        setValue((int) Math.round(Math.max(0, next) * 100));
        repaintClock();
        repaint();
    }

    /** Whether the session is working: the difference between a measurement and a live one. */
    public void setLive(boolean liveNow) {
        if (live == liveNow) {
            return;
        }
        live = liveNow;
        repaintClock();
        repaint();
    }

    /** The reading the gauge is heading for, or {@link #UNKNOWN}. */
    public double getFraction() {
        return target;
    }
    /** The value actually painted, which eases toward {@link #getFraction()}. */
    double shown() {
        return shown;
    }

    /** Whether the animation timer is still running: it must stop once the gauge has settled. */
    boolean animating() {
        return timer.isRunning();
    }

    /** Release the animation timer. */
    public void dispose() {
        timer.stop();
    }

    void step() {
        boolean moving = false;
        if (target != UNKNOWN && Math.abs(target - shown) > SETTLED) {
            shown += (target - shown) * 0.25;
            moving = true;
        } else if (target != UNKNOWN && shown != target) {
            shown = target;
            moving = true;
        }
        if (live) {
            sweepDegrees = (sweepDegrees + 5) % 360;
            moving = true;
        }
        repaint();
        if (!moving) {
            timer.stop();
        }
    }

    private void repaintClock() {
        if (target != shown || live) {
            if (!timer.isRunning()) {
                timer.start();
            }
        } else {
            timer.stop();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int d = diameter();
        float stroke = Math.max(2f, d / 7f);
        // an Arc2D sweeps a rectangle while the track is an ellipse, so the frame the ring is
        // inscribed in is stated twice from the same numbers rather than shared as one shape
        float inset = stroke / 2f;
        float span = d - stroke;
        Rectangle2D box = new Rectangle2D.Float(inset, inset, span, span);
        g2.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // the track: what the ring would be if the window were full, kept faint so the arc is the figure
        g2.setColor(UiPalette.alpha(UiPalette.muted(), 170));
        g2.draw(new Ellipse2D.Float(inset, inset, span, span));

        if (target != UNKNOWN || shown > 0) {
            // start at the top and grow clockwise: a reader compares it to a clock, not to a slope
            g2.setColor(UiPalette.level(shown));
            g2.draw(new Arc2D.Float(box, 90, (float) -360 * (float) shown, Arc2D.OPEN));
        }
        if (live) {
            g2.setComposite(AlphaComposite.SrcOver.derive(0.85f));
            g2.setColor(UiPalette.level(target == UNKNOWN ? 0 : target));
            g2.draw(new Arc2D.Float(box, (float) (90 - sweepDegrees), (float) -SWEEP_DEGREES, Arc2D.OPEN));
        }
        g2.dispose();
    }

    @Override
    public Dimension getPreferredSize() {
        int d = diameter();
        return new Dimension(d, d);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    /**
     * A fixed glyph, never a stretchable one. The bar's own UI installs a maximum size of zero
     * height for a horizontal bar, which the status row would honour by squeezing this into a
     * sliver of its own top edge.
     */
    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    /** The font the gauge is measured with: an unrealized component has none of its own. */
    private Font measuredFont() {
        Font f = getFont();
        if (f != null) {
            return f;
        }
        f = UIManager.getFont("Label.font");
        return f != null ? f : new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    }

    /** The glyph the gauge occupies: one line of the surrounding text, no more. */
    private int diameter() {
        FontMetrics fm = getFontMetrics(measuredFont());
        int h = fm == null ? 16 : fm.getHeight();
        return Math.max(14, Math.min(26, h + 2));
    }
}
