package net.thevpc.naru.desktop.ui;

import javax.swing.JButton;
import javax.swing.border.Border;

/**
 * The desktop's glyph buttons: an icon-only control with the words carried by its tooltip, and the
 * accessible name tests and screen readers key on. Borders are set explicitly because the two-tone
 * flat button decoration reads as a box around a control that only toggles a view.
 */
public final class GlyphButton {

    private GlyphButton() {
    }

    public static JButton of(String glyph, String tip) {
        return of(glyph, tip, false);
    }

    /** {@code flat} for buttons in a bar that should only show chrome while pressed. */
    public static JButton of(String glyph, String tip, boolean flat) {
        JButton b = new JButton(glyph);
        b.setToolTipText(tip);
        b.getAccessibleContext().setAccessibleName(tip);
        b.setFocusable(false);
        ActionButtons.glyphSized(b);
        Border none = javax.swing.BorderFactory.createEmptyBorder();
        b.setBorder(flat ? none : javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(UiPalette.border()),
                javax.swing.BorderFactory.createEmptyBorder(1, 1, 1, 1)));
        if (flat) b.setBorderPainted(false);
        return b;
    }
}
