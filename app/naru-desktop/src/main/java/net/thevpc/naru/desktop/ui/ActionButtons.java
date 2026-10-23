package net.thevpc.naru.desktop.ui;

import javax.swing.JButton;
import java.awt.Dimension;
import java.awt.FontMetrics;

/**
 * Sizing for buttons that carry a single glyph — the window controls, the task actions.
 * <p>
 * A glyph is measured in the button's own font but is frequently drawn from a fallback font, so a
 * width derived from the text is narrower than the shape it reserves room for: as soon as the
 * interface size grows past the point where the fallback wins, the button elides its glyph to an
 * ellipsis. These buttons are therefore sized from the font's height, which every glyph at that
 * size fits, and re-measured whenever their font changes — which is exactly what an interface-size
 * change does, so the square follows the slider instead of being frozen at the size the window was
 * built with.
 */
public final class ActionButtons {

    private ActionButtons() {
    }

    /** Keep each button a font-sized square, now and after every font change. */
    public static void glyphSized(JButton... buttons) {
        for (JButton b : buttons) {
            b.setMargin(new java.awt.Insets(0, 0, 0, 0));
            b.addPropertyChangeListener("font", e -> fit((JButton) e.getSource()));
            fit(b);
        }
    }

    /** The square that shows the glyph this button carries at the size it is drawn. */
    public static void fit(JButton b) {
        FontMetrics fm = b.getFontMetrics(b.getFont());
        Dimension square = new Dimension(fm.getHeight() + 8, fm.getHeight() + 6);
        b.setPreferredSize(square);
        b.setMinimumSize(square);
    }
}
