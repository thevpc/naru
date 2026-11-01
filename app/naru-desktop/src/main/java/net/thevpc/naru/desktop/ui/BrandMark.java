package net.thevpc.naru.desktop.ui;

import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * The product's own mark in the title bar: the app icon and the wordmark beside it.
 * <p>
 * Nothing in the window says what the window <i>is</i> — the session is named in the status footer
 * and the taskbar, and the menus are generic. The mark is the one place the chrome can carry the
 * brand, and it is drawn here rather than dropped in as a file icon so that it scales with the
 * interface-size slider instead of freezing at whatever pixel height the artwork ships at.
 * <p>
 * The size therefore comes from the font the bar is written in: the artwork is the height of that
 * font's line, and the wordmark is tracked out to sit beside it. The 1254&#215;1254 master is
 * resampled once into a small cache per size, never on every paint.
 */
public final class BrandMark extends JLabel {

    private static final String RESOURCE = "/net/thevpc/naru/desktop/naru.png";
    private static final String WORDMARK = "NARU";
    /** The space between the artwork and the word: a gutter, not a gap the two can drift into. */
    private static final int GAP = 7;
    /** The working resolution the master is brought down to once, for every bar height in use. */
    private static final int BASE = 160;

    /** Resampled artwork, shared for the life of the process. Null when the resource is absent. */
    private static BufferedImage base;
    private static boolean baseTried;

    private int cachedEdge = -1;
    private BufferedImage cachedIcon;

    public BrandMark() {
        // A label, because the mark is a name: it carries the accessible name for free and keeps one
        // even where the artwork cannot be read. The word itself is drawn by hand below, tracked out
        // to sit beside the artwork, so the look-and-feel is given nothing to paint.
        super(WORDMARK, SwingConstants.LEFT);
        setOpaque(false);
        // On the font ladder: the interface-size pass re-resolves this component's font, and the
        // mark's whole size — artwork included — is measured from it.
        UiFonts.apply(this, UiFonts.HEADING, Font.BOLD);
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        int word = fm.stringWidth(WORDMARK) + tracking(fm) * (WORDMARK.length() - 1);
        return new Dimension(edge(fm) + GAP + word, fm.getHeight());
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(getFont());
        FontMetrics fm = g2.getFontMetrics();
        int edge = edge(fm);
        int top = (getHeight() - edge) / 2;
        BufferedImage icon = icon(edge);
        if (icon != null) {
            g2.drawImage(icon, 0, top, null);
        } else {
            paintFallbackMark(g2, top, edge);
        }
        g2.setColor(wordmarkColor());
        // Drawn glyph by glyph because the word is tracked: a wordmark set solid reads as body text,
        // and the space between the letters is what makes it a name rather than a sentence.
        int x = edge + GAP;
        int baseline = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
        int track = tracking(fm);
        for (int i = 0; i < WORDMARK.length(); i++) {
            String letter = String.valueOf(WORDMARK.charAt(i));
            g2.drawString(letter, x, baseline);
            x += fm.charWidth(letter.charAt(0)) + track;
        }
        g2.dispose();
    }

    /** The artwork's height: the line the bar is written in, so the mark grows with the interface. */
    static int edge(FontMetrics fm) {
        return Math.max(12, Math.round(fm.getHeight() * 0.92f));
    }

    /** The space added after each letter of the wordmark. */
    static int tracking(FontMetrics fm) {
        return Math.max(1, Math.round(fm.charWidth('M') * 0.09f));
    }

    /** The icon at {@code edge} px, resampled from the process's one small copy of the artwork. */
    private BufferedImage icon(int edge) {
        BufferedImage source = base();
        if (source == null) {
            return null;
        }
        if (cachedEdge != edge || cachedIcon == null) {
            cachedIcon = resample(source, edge);
            cachedEdge = edge;
        }
        return cachedIcon;
    }

    /**
     * A mark for the case where the artwork cannot be read — a broken install, or a test run without
     * the resource. The bar still has to say what it belongs to, so it draws the brand's own shape:
     * a filled disc, the same hue the wordmark is written in.
     */
    private void paintFallbackMark(Graphics2D g2, int top, int edge) {
        g2.setColor(UiPalette.readable(UiPalette.BRAND));
        g2.fillOval(0, top, edge, edge);
        g2.setColor(surface());
        int inset = Math.max(2, edge / 4);
        g2.fillOval(inset, top + inset, edge - 2 * inset, edge - 2 * inset);
    }

    /** The wordmark: the brand hue, held back towards the theme's text so it stays readable. */
    private Color wordmarkColor() {
        Color text = UIManager.getColor("Label.foreground");
        if (text == null) text = UiPalette.dark() ? Color.WHITE : Color.BLACK;
        return UiPalette.mix(UiPalette.readable(UiPalette.BRAND), text, 0.3f);
    }

    private static Color surface() {
        Color surface = UIManager.getColor("Panel.background");
        return surface != null ? surface : Color.WHITE;
    }

    private static synchronized BufferedImage base() {
        if (baseTried) {
            return base;
        }
        baseTried = true;
        try (InputStream in = BrandMark.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                // Brought down once: keeping a 1254px master resident to answer a 24px question
                // would spend six megabytes of the cockpit's heap on a strip of chrome.
                base = resample(ImageIO.read(in), BASE);
            }
        } catch (Exception unreadable) {
            base = null;
        }
        return base;
    }

    /** {@code source} drawn at {@code edge} square, with the interpolation a downscale needs. */
    private static BufferedImage resample(BufferedImage source, int edge) {
        BufferedImage out = new BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, 0, 0, edge, edge, null);
        g.dispose();
        return out;
    }
}
