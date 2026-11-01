package net.thevpc.naru.desktop.ui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rail's tab glyphs, judged the way the header is judged: by what they put on the surface.
 */
class TabGlyphsTest {

    /** Partial alpha is an antialiased edge; anything above this is ink the shape laid down. */
    private static final int INK = 40;

    private JLabel tab;

    @BeforeEach
    void haveSomethingToPaintOnAndWith() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "a glyph is only worth asserting anything about once it has been drawn");
        tab = new JLabel();
        tab.setForeground(Color.BLACK);
    }

    @Test
    void everyGlyphStaysInsideTheBoxItToldTheTabStripItWould() throws Exception {
        for (TabGlyphs.Kind kind : TabGlyphs.Kind.values()) {
            Icon glyph = TabGlyphs.of(kind);
            BufferedImage image = paint(glyph, new Color(0, 0, 0, 0));
            int edge = glyph.getIconWidth();
            assertTrue(glyph.getIconHeight() == edge, kind + " is square, or the strip rows it uneven");
            int inked = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    boolean ink = (image.getRGB(x, y) >>> 24) > INK;
                    if (!ink) {
                        continue;
                    }
                    inked++;
                    assertTrue(x < edge && y < edge,
                            kind + " drew outside its own box at " + x + "," + y);
                }
            }
            assertTrue(inked > 30, kind + " painted almost nothing: " + inked + " pixels");
        }
    }

    /**
     * Four tabs the same grey word each is the problem being solved, so the shapes have to be
     * distinguishable from one another — measured, not admired.
     */
    @Test
    void theFourShapesAreNotFourNamesForTheSameShape() throws Exception {
        TabGlyphs.Kind[] kinds = TabGlyphs.Kind.values();
        BufferedImage[] painted = new BufferedImage[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            painted[i] = paint(TabGlyphs.of(kinds[i]), new Color(0, 0, 0, 0));
        }
        for (int a = 0; a < kinds.length; a++) {
            for (int b = a + 1; b < kinds.length; b++) {
                double apart = differingShare(painted[a], painted[b]);
                assertTrue(apart > 0.4, kinds[a] + " and " + kinds[b]
                        + " agree on " + Math.round((1 - apart) * 100) + "% of their ink");
            }
        }
    }

    /**
     * The tab strip owns the colour of a tab's label — selected, hovered, light theme, dark — and a
     * glyph that carried its own would sit in the wrong colour half the time.
     */
    @Test
    void theGlyphIsPaintedInTheColoursTheTabIsAlreadyWearing() throws Exception {
        for (Color worn : new Color[]{new Color(200, 30, 30), new Color(30, 60, 220)}) {
            tab.setForeground(worn);
            BufferedImage image = paint(TabGlyphs.of(TabGlyphs.Kind.INSPECT), new Color(0, 0, 0, 0));
            Color darkest = null;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    if ((argb >>> 24) > 240) {
                        darkest = new Color(argb, true);
                    }
                }
            }
            assertNotNull(darkest, "nothing was drawn at full alpha");
            assertEquals(worn.getRed(), darkest.getRed(), 12, "the red stayed with the glyph");
            assertEquals(worn.getGreen(), darkest.getGreen(), 12);
            assertEquals(worn.getBlue(), darkest.getBlue(), 12);
        }
    }

    /** The box follows the type ladder, so a bigger interface does not shrink the glyph beside it. */
    @Test
    void theGlyphGrowsWithTheTypeItSharesItsRowWith() {
        assertEquals(16, TabGlyphs.edgeFor(13f), "the plan's 16 px at the theme's own size");
        assertTrue(TabGlyphs.edgeFor(17f) > TabGlyphs.edgeFor(13f), "and larger as the ladder climbs");
        assertEquals(12, TabGlyphs.edgeFor(6f), "never so small that a shield stops reading as one");
        assertEquals(TabGlyphs.edgeFor((float) UiFonts.base().getSize2D()), TabGlyphs.edge(),
                "the box the strip is given is the ladder size, not a number held elsewhere");
    }

    /**
     * The titles are how the window, the harness and a screen reader name a tab. A decoration pass
     * that renamed one would break selection while looking entirely correct.
     */
    @Test
    void decoratingAddsAGlyphAndATipAndLeavesTheNameAlone() {
        JTabbedPane tabs = new JTabbedPane();
        String[] titles = {"Plan", "Permissions", "Inspect", "Workspace"};
        TabGlyphs.Kind[] kinds = TabGlyphs.Kind.values();
        for (int i = 0; i < titles.length; i++) {
            tabs.addTab(titles[i], new JLabel(titles[i] + " body"));
        }
        for (int i = 0; i < titles.length; i++) {
            TabGlyphs.decorate(tabs, i, kinds[i], "what it holds");
        }
        for (int i = 0; i < titles.length; i++) {
            assertEquals(titles[i], tabs.getTitleAt(i), "the title is still the selector");
            assertNotNull(tabs.getIconAt(i), titles[i] + " got no glyph");
            String tip = tabs.getToolTipTextAt(i);
            assertNotNull(tip, titles[i] + " got no tooltip");
            assertTrue(tip.startsWith(titles[i]), "a tip that drops the name reads as a stray: " + tip);
            assertTrue(tip.contains("what it holds"), titles[i] + "'s tip says nothing: " + tip);
        }
    }

    private BufferedImage paint(Icon glyph, Color background) {
        int edge = Math.max(glyph.getIconWidth(), glyph.getIconHeight());
        BufferedImage image = new BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(background);
        g.fillRect(0, 0, edge, edge);
        glyph.paintIcon(tab, g, 0, 0);
        g.dispose();
        return image;
    }

    /** The share of the two shapes' combined ink that only one of them has. */
    private static double differingShare(BufferedImage a, BufferedImage b) {
        int union = 0;
        int apart = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                boolean inkA = (a.getRGB(x, y) >>> 24) > INK;
                boolean inkB = (b.getRGB(x, y) >>> 24) > INK;
                if (inkA || inkB) {
                    union++;
                }
                if (inkA != inkB) {
                    apart++;
                }
            }
        }
        return apart / (double) union;
    }
}
