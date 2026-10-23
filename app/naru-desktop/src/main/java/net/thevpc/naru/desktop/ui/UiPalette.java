package net.thevpc.naru.desktop.ui;

import javax.swing.UIManager;
import java.awt.Color;

/**
 * The four hues the desktop paints, and nothing else.
 * <p>
 * Colour carries state here, not decoration: a reader should be able to tell an open session from a
 * closed one without reading the word beside it. That only works while the same hue means the same
 * thing in the session list, the window title and the context gauge, so the four states live here
 * instead of being re-spelled as literals at each of those places. Everything else stays in the
 * look-and-feel's own palette — greys, borders and the accent — so a theme change still moves the
 * interface as a whole and only the state hues, which are deliberately theme independent, stay put.
 */
public final class UiPalette {

    /** Doing its work now: an open session, a live task, a context gauge with room left. */
    public static final Color LIVE = new Color(0x10, 0xB9, 0x81);
    /** Getting there, or waiting on something: a session that is still opening. */
    public static final Color PENDING = new Color(0xF5, 0x9E, 0x0B);
    /** Something the reader has to look at: a failed task, a nearly full context window. */
    public static final Color ATTENTION = new Color(0xEF, 0x44, 0x44);
    /** No state worth reporting: a closed session, a gauge that has nothing to measure yet. */
    public static final Color IDLE = new Color(0x9C, 0xA3, 0xAF);

    private UiPalette() {
    }

    /** The interface's own accent, for the ordinary case where a hue carries no state. */
    public static Color accent() {
        Color accent = UIManager.getColor("Component.accentColor");
        if (accent == null) accent = UIManager.getColor("Component.focusColor");
        return accent != null ? accent : new Color(45, 115, 205);
    }

    /** The colour of text that is there to be found, not read first. */
    public static Color muted() {
        Color muted = UIManager.getColor("Label.disabledForeground");
        return muted != null ? muted : new Color(130, 140, 150);
    }

    /** The hairline colour: panel dividers, gauge tracks, unfocused outlines. */
    public static Color border() {
        Color border = UIManager.getColor("Component.borderColor");
        if (border == null) border = UIManager.getColor("Separator.foreground");
        return border != null ? border : new Color(210, 215, 225);
    }

    /**
     * The hue for a gauge reading: the accent while there is room, amber as it runs out, red when
     * the next request may not fit.
     */
    public static Color level(double fraction) {
        if (fraction >= 0.9) return ATTENTION;
        if (fraction >= 0.75) return PENDING;
        return accent();
    }

    /**
     * A hue from a fixed table (a {@code BlockStyle} gutter, a role colour) adjusted so it still
     * reads on whatever background the current theme paints.
     * <p>
     * The per-mode colours were chosen for a light interface. Dropped on a dark one they sink —
     * {@code 0x7B1FA2} purple is barely distinguishable from the panel — and a colour chosen for a
     * dark one would glare on a light one. A hue is only ever a stripe or a caption here, so the
     * fix is brightness and a little saturation, never the hue itself.
     */
    public static Color readable(Color hue) {
        if (hue == null) return muted();
        int max = Math.max(hue.getRed(), Math.max(hue.getGreen(), hue.getBlue()));
        int min = Math.min(hue.getRed(), Math.min(hue.getGreen(), hue.getBlue()));
        if (max == min) {
            // Achromatic: HSB would invent a hue out of the zero saturation. Move the grey instead.
            int step = dark() ? 96 : -48;
            int level = Math.max(0, Math.min(255, (int) Math.round(luminance(hue)) + step));
            return new Color(level, level, level);
        }
        float[] hsb = Color.RGBtoHSB(hue.getRed(), hue.getGreen(), hue.getBlue(), null);
        float brightness = dark() ? Math.max(hsb[2], 0.82f) : Math.min(hsb[2], 0.62f);
        float saturation = dark() ? Math.min(hsb[1], 0.68f) : Math.max(hsb[1], 0.5f);
        return Color.getHSBColor(hsb[0], saturation, brightness);
    }

    /** True while the interface is painted on a dark background. */
    public static boolean dark() {
        Color background = UIManager.getColor("Panel.background");
        if (background == null) background = UIManager.getColor("control");
        return background != null && luminance(background) < 128;
    }

    private static double luminance(Color color) {
        return 0.299 * color.getRed() + 0.587 * color.getGreen() + 0.114 * color.getBlue();
    }

    /** A colour as a CSS {@code #rrggbb} literal, for the HTML the renderers build. */
    public static String css(Color color) {
        if (color == null) return "#000000";
        return String.format(java.util.Locale.ROOT, "#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    /** The same hue with its alpha replaced, for a track or a glow that must not compete with it. */
    public static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    /** {@code from} walked towards {@code to}: {@code 0} keeps the surface, {@code 1} is the hue. */
    public static Color mix(Color from, Color to, float fraction) {
        float t = Math.max(0, Math.min(1, fraction));
        return new Color(Math.round(from.getRed() + (to.getRed() - from.getRed()) * t),
                Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
                Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t));
    }
}
