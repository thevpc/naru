package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.desktop.NaruDesktop;

import javax.swing.JComponent;
import java.awt.Font;
import java.awt.FontMetrics;

/**
 * The one font ladder the whole window is measured on.
 * <p>
 * Sizes used to be written as points — 11 here, 12 there, 13, 14, 18, 24 — and each of those
 * literals then grew by the interface scale from its <i>own</i> number. A 0.85× caption and a 1.0×
 * body start 2 points apart at 100 % and 6 points apart at 300 %, which is what made the
 * conversation, the session list and the rest of the app look like three different applications as
 * the reader pressed Ctrl+-.
 * <p>
 * So a size here is a <b>ratio of the theme's own base font size</b>, never a point size: the ladder
 * is resolved from the size the current theme and interface scale give, which keeps the parts in
 * proportion to each other at every size and in every theme. The family comes from
 * {@link Typefaces} — bundled with the app, not looked up on the host. Components remember their
 * ratio, not a font, so a scale pass or a theme swap re-resolves them from scratch instead of
 * compounding on whatever font they happened to be wearing.
 */
public final class UiFonts {

    /** Caption text: a label above something, a state word, a row's metadata line. */
    public static final float CAPTION = 0.85f;
    /** Small but readable secondary text: the status footer, tooltips, the filter field. */
    public static final float SECONDARY = 0.92f;
    /** The base itself: conversation bodies, list titles, the composer. */
    public static final float BODY = 1f;
    /** A row's heading: "Sessions", a card's speaker. */
    public static final float HEADING = 1.08f;
    /** A section heading inside a page. */
    public static final float SUBTITLE = 1.25f;
    /** A page title: the welcome screen, the settings header. */
    public static final float TITLE = 1.45f;

    private static final String RATIO = "naru.font.ratio";
    private static final String STYLE = "naru.font.style";
    private static final String FAMILY = "naru.font.family";

    private UiFonts() {
    }

    /**
     * Give {@code component} the ladder size {@code ratio}, in the bundled UI typeface. Call this
     * once when the component is built; a later scale or theme pass re-resolves it from the ratio,
     * so the component never has to be told again.
     */
    public static void apply(JComponent component, float ratio, int style) {
        apply(component, ratio, style, null);
    }

    /** As {@link #apply}, pinned to the bundled monospaced face — for the surfaces that show data. */
    public static void applyMono(JComponent component, float ratio, int style) {
        apply(component, ratio, style, Typefaces.MONO);
    }

    private static void apply(JComponent component, float ratio, int style, String family) {
        component.putClientProperty(RATIO, ratio);
        component.putClientProperty(STYLE, style);
        if (family != null) {
            component.putClientProperty(FAMILY, family);
        }
        component.setFont(resolved(component));
    }

    /** True when {@code component} is on the ladder, so a caller can leave it to the ladder. */
    public static boolean isManaged(JComponent component) {
        return component.getClientProperty(RATIO) instanceof Float;
    }

    /** The component's font right now: its ladder ratio at the current theme and interface size. */
    public static Font resolved(JComponent component) {
        float ratio = component.getClientProperty(RATIO) instanceof Float f ? f : 1f;
        int style = component.getClientProperty(STYLE) instanceof Integer s ? s : Font.PLAIN;
        String family = component.getClientProperty(FAMILY) instanceof String s ? s : null;
        return resolve(ratio, style, family);
    }

    /**
     * A ladder font, without putting a component on the ladder: for painting text with
     * {@link java.awt.Graphics}, or for handing one size to several components at once. A caller
     * that does the latter should still put the components on the ladder so a size change reaches
     * them.
     */
    public static Font font(float ratio, int style) {
        return resolve(ratio, style, null);
    }

    /** As {@link #font}, in the monospaced face the data surfaces use. */
    public static Font monoFont(float ratio, int style) {
        return resolve(ratio, style, Typefaces.MONO);
    }

    /** The point size a ladder ratio is worth at the current theme and interface size. */
    public static float pointSize(float ratio) {
        return resolve(ratio, Font.PLAIN, null).getSize2D();
    }

    /** The UI face at the theme's base size and the current interface size — what an unscaled component wears. */
    public static Font base() {
        return resolve(1f, Font.PLAIN, null);
    }

    public static FontMetrics metrics(java.awt.Graphics g) {
        return g.getFontMetrics(base());
    }

    private static Font resolve(float ratio, int style, String family) {
        Font theme = NaruDesktop.themeBaseFont();
        float at = (theme == null ? 13f : theme.getSize2D()) * ratio * NaruDesktop.fontScale() / 100f;
        // Always a composite font: the bundled face draws the Latin text, the platform supplies
        // anything it lacks (emoji, CJK, Arabic) instead of a missing-glyph box.
        return Typefaces.composite(family != null ? family : Typefaces.UI, style, at);
    }
}
