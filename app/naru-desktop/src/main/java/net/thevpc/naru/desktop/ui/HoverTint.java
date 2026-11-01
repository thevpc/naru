package net.thevpc.naru.desktop.ui;

import javax.swing.JButton;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * A control that colours itself while the pointer is on it.
 * <p>
 * The window controls carry one glyph each, so nothing about them says what they do until they are
 * pressed. Painting the action's own hue into a pill under the glyph — and only while the pointer is
 * crossing it — answers that without spending pixels on a label. The fade is {@link HoverFader}'s,
 * so the pill arrives over ~150 ms instead of snapping, exactly like a transcript row lighting up.
 * <p>
 * This does not break the rule that colour carries state rather than decoration. A hover hue exists
 * only under the pointer, so it can never be read as the session list's or the context gauge's
 * meaning of the same colour; and the red kept for the close button is the same red the palette
 * already uses for "look at this", because that control is the one that ends the run.
 * <p>
 * The look-and-feel's own rollover is switched off: a FlatLaf grey wash under this pill would muddy
 * the hue, and the two together read as the button flinching twice.
 */
public final class HoverTint extends JButton {

    /** How opaque the pill is at a full hover: enough to set the button apart, not to fill it. */
    private static final int FILL_ALPHA = 74;
    /** The ring that gives the pill an edge so it does not look like a smudge on the bar. */
    private static final int EDGE_ALPHA = 158;

    private final Color hue;

    /**
     * A borderless control carrying {@code glyph}, tinting to {@code hue} under the pointer.
     * The tip doubles as the accessible name: every control in the chrome is glyph-only.
     */
    public HoverTint(String glyph, Color hue, String tip) {
        super(glyph);
        this.hue = hue;
        setToolTipText(tip);
        getAccessibleContext().setAccessibleName(tip);
        setFocusable(false);
        setOpaque(false);
        // Nothing of the look-and-feel's own behind the pill: a FlatLaf or Metal rollover under this
        // wash would muddy the hue and read as the button flinching twice.
        setContentAreaFilled(false);
        setBorderPainted(false);
        putClientProperty("JButton.buttonType", "borderless");
        HoverFader.install(this);
    }

    /** How far into its hover this control is, for a test or a painter that shares the fade. */
    public float hoverLevel() {
        return HoverFader.level(this);
    }

    /** The hue this control answers with, as the tests see it painted. */
    Color hoverHue() {
        return hue;
    }

    @Override
    protected void paintComponent(Graphics g) {
        float level = HoverFader.level(this);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int arc = pillArc(getWidth(), getHeight());
        if (level > 0f) {
            g2.setColor(UiPalette.alpha(hue, Math.round(level * FILL_ALPHA)));
            g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, arc, arc);
            g2.setColor(UiPalette.alpha(hue, Math.round(level * EDGE_ALPHA)));
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(1, 1, getWidth() - 2, getHeight() - 2, arc, arc);
        }
        // The glyph takes the same hue, walked from the theme's own button text so it never loses
        // contrast with the bar. Set around the superclass call, and handed back after: the paint
        // must not leave the button wearing a colour the model of the control does not have.
        Color before = getForeground();
        setForeground(UiPalette.mix(textColor(), UiPalette.readable(hue), level));
        super.paintComponent(g);
        setForeground(before);
        g2.dispose();
    }

    /** The theme's button text colour, for whatever the theme does not state. */
    private static Color textColor() {
        Color text = UIManager.getColor("Button.foreground");
        if (text == null) text = UIManager.getColor("Label.foreground");
        return text != null ? text : (UiPalette.dark() ? Color.WHITE : Color.BLACK);
    }

    /**
     * The corner radius of the pill: as round as the button is short, so a control stays a lozenge
     * rather than turning into a circle at the smallest interface size or a square at the largest.
     */
    static int pillArc(int width, int height) {
        return Math.max(4, Math.min(width, height) - 3);
    }
}
