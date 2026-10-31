package net.thevpc.naru.desktop.ui;

import javax.swing.JButton;

/**
 * The desktop's glyph buttons: an icon-only control with the words carried by its tooltip, and the
 * accessible name tests and screen readers key on. Icon-only actions use toolbar treatment so a
 * row of small controls does not turn into a row of little outlined boxes.
 */
public final class GlyphButton {

    private GlyphButton() {
    }

    public static JButton of(String glyph, String tip) {
        JButton b = new JButton(glyph);
        b.setToolTipText(tip);
        b.getAccessibleContext().setAccessibleName(tip);
        b.setFocusable(false);
        ActionButtons.glyphSized(b);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setBorderPainted(false);
        return b;
    }
}
