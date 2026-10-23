package net.thevpc.naru.desktop.ui;

import javax.swing.JComponent;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Insets;

/**
 * The chrome every transcript row shares: a flat, full-width layer marked by a coloured gutter
 * stripe and closed by a hairline, instead of a rounded box floating on the panel.
 * <p>
 * Boxes-with-borders were the old look, and they cost the reader twice: a tinted outline around
 * every single block repeats the framing the column already has, and eight pastel fills all claim
 * the same attention. Here the structure is the stack itself — one surface, one rule per row — and
 * the only colour left is the 3-pixel stripe, which says what the block <i>is</i> (the hue comes
 * from the block's own style) rather than how loud it should be.
 * <p>
 * The stripe and the rule are painted, not bordered, so a row costs three primitives on the EDT no
 * matter how long its body is.
 */
public final class TranscriptLayer {

    /** The width of the gutter stripe that carries the block's hue. */
    public static final int STRIPE = 3;

    /** The air between two rows; the rule belongs to the row above it, so this can stay tight. */
    public static final int GAP = 2;

    private TranscriptLayer() {
    }

    /** Padding for a row: clear of the stripe on the left, room for the rule at the bottom. */
    public static Border border() {
        return new EmptyBorder(new Insets(8, STRIPE + 11, 9, 12));
    }

    /**
     * Paint one row.
     *
     * @param stripe the block's hue, or {@code null} for a row that should not claim any colour
     * @param tinted a barely-there wash of the same hue, reserved for the fixed-width dumps
     *               (tool output, command results) that read as one continuous block of data
     */
    public static void paint(JComponent row, Graphics graphics, Color stripe, boolean tinted) {
        int width = row.getWidth();
        int height = row.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
        try {
            Color hue = UiPalette.readable(stripe);
            // The pointer's own row: the fade is driven by HoverFader, and both of its cues stay
            // inside the row's existing geometry — a wider stripe and a glow, never a reflow.
            float hover = HoverFader.level(row);
            if (tinted || hover > 0) {
                int wash = (tinted ? (UiPalette.dark() ? 13 : 10) : 0)
                        + (int) (hover * (UiPalette.dark() ? 22 : 16));
                g.setColor(UiPalette.alpha(hue, wash));
                g.fillRect(STRIPE, 0, width - STRIPE, height);
            }
            g.setColor(hue);
            if (stripe != null) {
                g.fillRect(0, 0, STRIPE + Math.round(hover * 2), height);
            }
            g.setColor(UiPalette.alpha(UiPalette.border(), 160));
            // The rule stops at the gutter: a hairline across the stripe breaks it into dashes,
            // and the stripe is meant to read as one rail the rows hang off.
            g.drawLine(STRIPE, height - 1, width, height - 1);
        } finally {
            g.dispose();
        }
    }
}
