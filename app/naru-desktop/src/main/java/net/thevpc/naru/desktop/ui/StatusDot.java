package net.thevpc.naru.desktop.ui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * One filled circle: the state of the thing beside it, without a word for it.
 * <p>
 * The desktop already spells state out in text everywhere, which is why a session list and a window
 * title take a reader a glance to compare. A hue does the comparing instead, as long as it is the
 * same hue for the same state in every place — see {@link UiPalette}.
 * <p>
 * Sized from the font rather than in pixels, and re-measured when the font changes, so it stays
 * beside the text it belongs to when the interface size changes.
 */
public final class StatusDot extends JComponent {

    /** ~20 Hz: a breath, not a blink. The dot is a state, and a state should not twitch. */
    private static final int PULSE_MS = 50;
    private static final double TAU = 2 * Math.PI;

    private Color color = UiPalette.IDLE;
    private boolean pulsing;
    private double phase;
    private final javax.swing.Timer pulse = new javax.swing.Timer(PULSE_MS,
            e -> {
                phase = (phase + 0.16) % TAU;
                repaint();
            });

    public StatusDot() {
        setOpaque(false);
        setFocusable(false);
        addPropertyChangeListener("font", e -> revalidate());
    }

    /** Paint the dot this hue; pass {@code null} to hide it. */
    public void setColor(Color c) {
        if (c == null ? color == null : c.equals(color)) {
            return;
        }
        color = c;
        repaint();
    }

    /**
     * Breathe while the state behind the dot is something happening rather than something settled:
     * a session still opening has no result to read yet, and a still circle invites the reader to
     * wait for one. The timer runs only while it is asked to.
     */
    public void setPulsing(boolean on) {
        if (pulsing == on) {
            return;
        }
        pulsing = on;
        if (on) {
            if (!pulse.isRunning()) {
                pulse.start();
            }
        } else {
            pulse.stop();
            phase = 0;
            repaint();
        }
    }

    /** How far through the breath the halo is: 0 when the state is settled, otherwise 0…1. */
    double pulseLevel() {
        return pulsing ? 0.5 + 0.5 * Math.sin(phase) : 0;
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (color == null) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int d = diameter();
        double breath = pulseLevel();
        // a soft halo, so the dot reads as a lit state rather than a bullet character; while the
        // state is in motion the halo widens and brightens, which is the only movement a 9px
        // circle can make without changing what it claims
        int halo = d + 4 + (int) Math.round(3 * breath);
        g2.setColor(UiPalette.alpha(color, (int) (60 + 80 * breath)));
        g2.fillOval((getWidth() - halo) / 2, (getHeight() - halo) / 2, halo, halo);
        g2.setColor(color);
        g2.fillOval((getWidth() - d) / 2, (getHeight() - d) / 2, d, d);
        g2.dispose();
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(measuredFont());
        int h = fm == null ? 15 : fm.getHeight();
        return new Dimension(Math.max(9, Math.min(14, h / 2 + 5)), h);
    }

    /** The circle inside the box the component reserves. */
    private int diameter() {
        FontMetrics fm = getFontMetrics(measuredFont());
        int h = fm == null ? 15 : fm.getHeight();
        return Math.max(5, Math.min(9, h / 2));
    }

    /** The font the dot is measured with: an unrealized component carries no font of its own. */
    private Font measuredFont() {
        Font f = getFont();
        if (f != null) {
            return f;
        }
        f = javax.swing.UIManager.getFont("Label.font");
        return f != null ? f : new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    }
}
