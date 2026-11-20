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
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * The product's own mark in the title bar: the banner — the raven, the name and the kana, as one
 * piece of artwork.
 * <p>
 * Nothing in the window says what the window <i>is</i> — the session is named in the status footer
 * and the taskbar, and the menus are generic. The mark is the one place the chrome can carry the
 * brand, and it is drawn here rather than dropped in as a file icon so that it scales with the
 * interface-size slider instead of freezing at whatever pixel height the artwork ships at.
 * <p>
 * The size therefore comes from the font the bar is written in, at a badge's ratio rather than a
 * glyph's: the banner is that much taller than the line, keeps its own proportions beside it, and so
 * rules the height of the bar. The master is cropped to its artwork and resampled once into a small
 * cache per height, never on every paint.
 */
public final class BrandMark extends JLabel {

    private static final String RESOURCE = "/net/thevpc/naru/desktop/naru-logo-banner.png";
    /** The name the mark answers to, for the case where the artwork cannot be read. */
    private static final String WORDMARK = "NARU";
    /** The working height the master is brought down to once, for every bar height in use. */
    private static final int BASE = 256;
    /** The badge off the line the bar is written in, and the smallest it is ever drawn. */
    private static final float BADGE = 1.55f;
    private static final int MIN_EDGE = 26;

    /** Resampled artwork, shared for the life of the process. Null when the resource is absent. */
    private static BufferedImage base;
    private static boolean baseTried;

    private int cachedEdge = -1;
    private BufferedImage cachedArtwork;

    public BrandMark() {
        // A label, because the mark is a name: it carries the accessible name for free and keeps one
        // even where the artwork cannot be read. The banner is drawn by hand below, so the
        // look-and-feel is given nothing to paint.
        super(WORDMARK, SwingConstants.LEFT);
        setOpaque(false);
        // On the font ladder: the interface-size pass re-resolves this component's font, and the
        // mark's whole size — artwork included — is measured from it.
        UiFonts.apply(this, UiFonts.HEADING, Font.BOLD);
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        // The mark's height is what the bar is measured against: it is a badge, so it rules the chrome
        // it sits in instead of shrinking to the line the menus are written on.
        return new Dimension(width(fm), edge(fm));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        FontMetrics fm = g2.getFontMetrics(getFont());
        int edge = edge(fm);
        int top = (getHeight() - edge) / 2;
        BufferedImage artwork = artwork(edge);
        if (artwork != null) {
            // Left-flushed, whatever room the bar gives the label: the wash under the mark starts at
            // its left edge, so the artwork is what the gradient is anchored on.
            g2.drawImage(artwork, 0, top, null);
        } else {
            paintFallbackMark(g2, top, edge);
        }
        g2.dispose();
    }

    /**
     * The artwork's height: a badge measured off the line the bar is written in, not a glyph set on
     * it. The plate and its rim are the readable part of the banner, and they need more than the
     * menus' line to hold together — below this, the cyan edge collapses into the dark plate and the
     * mark stops reading as a name. The interface-size slider still moves it, through the same font.
     */
    static int edge(FontMetrics fm) {
        return Math.max(MIN_EDGE, Math.round(fm.getHeight() * BADGE));
    }

    /** The room the artwork needs at that height, once its own proportions are kept. */
    private int width(FontMetrics fm) {
        BufferedImage source = base();
        int edge = edge(fm);
        if (source == null) {
            return edge;
        }
        return Math.max(edge, Math.round(edge * source.getWidth() / (float) source.getHeight()));
    }

    /** The banner at {@code edge} px tall, resampled from the process's one small copy of the artwork. */
    private BufferedImage artwork(int edge) {
        BufferedImage source = base();
        if (source == null) {
            return null;
        }
        if (cachedEdge != edge || cachedArtwork == null) {
            cachedArtwork = resample(source, edge);
            cachedEdge = edge;
        }
        return cachedArtwork;
    }

    /**
     * A mark for the case where the artwork cannot be read — a broken install, or a test run without
     * the resource. The bar still has to say what it belongs to, so it draws the brand's own shape:
     * a filled disc, the same hue the rest of the chrome is lit with.
     */
    private void paintFallbackMark(Graphics2D g2, int top, int edge) {
        g2.setColor(UiPalette.readable(UiPalette.BRAND));
        g2.fillOval(0, top, edge, edge);
        g2.setColor(surface());
        int inset = Math.max(2, edge / 4);
        g2.fillOval(inset, top + inset, edge - 2 * inset, edge - 2 * inset);
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
                BufferedImage master = ImageIO.read(in);
                Rectangle bounds = contentBounds(master);
                // Brought down once, and to its own bounds: the master ships a transparent margin,
                // and a mark measured against the canvas would sit small and off-centre in the bar.
                base = resample(master.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height), BASE);
            }
        } catch (Exception unreadable) {
            base = null;
        }
        return base;
    }

    /**
     * The rectangle the artwork actually paints, in a master that is otherwise transparent. An
     * artwork with no alpha at all — a flattened export — is its own bounds.
     */
    private static Rectangle contentBounds(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        int[] row = new int[w];
        int top = h, bottom = -1, left = w, right = -1;
        for (int y = 0; y < h; y++) {
            source.getRGB(0, y, w, 1, row, 0, w);
            int first = -1;
            int last = -1;
            for (int x = 0; x < w; x++) {
                // Anything but a bare silhouette counts: the soft glow around the raven is part of
                // the artwork, and cropping at full opacity would cut it off.
                if ((row[x] >>> 24) > 8) {
                    if (first < 0) {
                        first = x;
                    }
                    last = x;
                }
            }
            if (first < 0) {
                continue;
            }
            if (top == h) {
                top = y;
            }
            bottom = y;
            left = Math.min(left, first);
            right = Math.max(right, last);
        }
        if (bottom < top) {
            return new Rectangle(0, 0, w, h);
        }
        return new Rectangle(left, top, right - left + 1, bottom - top + 1);
    }

    /** {@code source} drawn {@code height} px tall at its own aspect, with the interpolation a downscale needs. */
    private static BufferedImage resample(BufferedImage source, int height) {
        int width = Math.max(1, Math.round(height * source.getWidth() / (float) source.getHeight()));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return out;
    }
}
