package net.thevpc.naru.desktop.ui;

import com.formdev.flatlaf.FlatLightLaf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The title bar's chrome, read off the pixels it paints and the events it answers to.
 * <p>
 * Three promises are worth holding: the wash is a tint of the theme's own surface that fades out
 * before the window controls, so nothing in the bar costs a menu its contrast; a control carries a
 * hue only while the pointer is on it; and the bar is still the window's handle, with the controls
 * left out of it and a double-click counted once.
 * <p>
 * The pointer is one the test places, through {@link HoverFader}'s seam, because a test run has no
 * hand on a mouse.
 */
class HeaderBarTest {

    private static final int WIDTH = 240;
    private static final int HEIGHT = 34;
    /** A row inside the wash, above the rule the bar ends with. */
    private static final int BODY_ROW = HEIGHT - HeaderBar.HAIRLINE - 1;
    /** A control painted at the size {@link ActionButtons} gives a glyph button. */
    private static final int CONTROL_W = 26;
    private static final int CONTROL_H = 24;
    /** A point inside the pill but outside the glyph, where only the hover paints. */
    private static final int PILL_X = 4;

    private static final Set<JComponent> UNDER =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    private HoverFader.Pointer realPointer;
    private Color surface;

    @BeforeEach
    void holdThePointer() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "the bar is judged by what it paints, and painting needs a graphics environment");
        realPointer = HoverFader.pointerAt;
        HoverFader.pointerAt = UNDER::contains;
        // The theme's own surface, not one the test invents: the wash is defined as a tint of it.
        surface = UIManager.getColor("Panel.background");
        if (surface == null) surface = UIManager.getColor("control");
    }

    @AfterEach
    void giveTheMouseBack() {
        HoverFader.pointerAt = realPointer;
        UNDER.clear();
    }

    // --- the wash -----------------------------------------------------------------------------

    @Test
    void theBarIsWashedTowardsTheBrandAtItsLeftEnd() throws Exception {
        BufferedImage image = paint(new HeaderBar(() -> false, () -> { }), WIDTH, HEIGHT);
        Color left = pixel(image, 2, BODY_ROW);
        assertNotEquals(surface, left, "the bar is not left on the bare surface where the mark sits");
        assertEquals(UiPalette.BRAND, movedTowards(left), "and it walks towards the brand hue, no other");
    }

    @Test
    void theWashFadesAcrossTheBarInsteadOfPaintingItSolid() throws Exception {
        BufferedImage image = paint(new HeaderBar(() -> false, () -> { }), WIDTH, HEIGHT);
        int left = distance(pixel(image, 2, BODY_ROW), surface);
        int middle = distance(pixel(image, WIDTH / 2, BODY_ROW), surface);
        int right = distance(pixel(image, WIDTH - 2, BODY_ROW), surface);
        assertTrue(left > middle, "strongest under the mark: " + left + " against " + middle);
        assertTrue(middle > right, "thinning towards the controls: " + middle + " against " + right);
        assertTrue(right <= 8, "the window controls keep a plain surface to sit on, got " + right);
    }

    @Test
    void theWashIsATintOfTheSurfaceAndNotASecondBackground() throws Exception {
        BufferedImage image = paint(new HeaderBar(() -> false, () -> { }), WIDTH, HEIGHT);
        Color left = pixel(image, 2, BODY_ROW);
        assertTrue(distance(left, surface) <= 120,
                "a sheet of cyan behind grey menu text would be a contrast failure, got " + left);
        assertTrue(distance(left, surface) >= 20, "but it still has to read as a coloured bar");
    }

    /**
     * The look-and-feel, not this bar, decides what a menu bar paints behind its titles. FlatLaf keeps
     * the window background on it as a {@code UIResource} and fills that rectangle even when the bar
     * is non-opaque, which cuts a pale box out of the wash. So the bar is handed a plain transparent
     * colour, and this is what it has to look like when that works.
     */
    @Test
    void menusLetTheWashRunUnderThemInsteadOfFillingTheirOwnRectangle() throws Exception {
        LookAndFeel previous = UIManager.getLookAndFeel();
        try {
            look(FlatLightLaf.class);
            onEdt(() -> surface = UIManager.getColor("Panel.background"));
            BufferedImage bare = paint(new HeaderBar(() -> false, () -> { }), WIDTH, HEIGHT);
            assertTrue(kept(bare, withMenus(HeaderBar.washedMenuBar())) > 0.9f,
                    "the wash reaches the whole rectangle the menus sit in");
            assertTrue(kept(bare, withMenus(new JMenuBar())) < 0.5f,
                    "a menu bar left as it comes from the look-and-feel fills its own rectangle — "
                            + "the pale box this guards, and why the app builds one through washedMenuBar()");
        } finally {
            onEdt(() -> {
                try {
                    UIManager.setLookAndFeel(previous);
                } catch (UnsupportedLookAndFeelException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    private static void look(Class<? extends LookAndFeel> laf) throws Exception {
        onEdt(() -> {
            try {
                UIManager.setLookAndFeel(laf.getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException | UnsupportedLookAndFeelException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private HeaderBar withMenus(JMenuBar menus) {
        HeaderBar bar = new HeaderBar(() -> false, () -> { });
        menus.add(new JMenu("File"));
        bar.add(menus, BorderLayout.WEST);
        return bar;
    }

    /**
     * The share of a bare bar's pixels that a menu bar laid over the same place left untouched.
     * <p>
     * Only the bar itself is laid out, so its menu titles stay where an unparented component leaves
     * them and the rectangle being judged is the menu bar's own surface — text would only add pixels
     * both variants draw the same way.
     */
    private float kept(BufferedImage bare, HeaderBar withMenus) throws Exception {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Rectangle[] bounds = new Rectangle[1];
        onEdt(() -> {
            Graphics2D background = image.createGraphics();
            background.setColor(surface);
            background.fillRect(0, 0, WIDTH, HEIGHT);
            background.dispose();
            withMenus.setSize(WIDTH, HEIGHT);
            withMenus.doLayout();
            bounds[0] = ((JMenuBar) withMenus.getComponent(0)).getBounds();
            withMenus.paint(image.createGraphics());
        });
        int same = 0;
        int total = 0;
        for (int y = bounds[0].y; y < bounds[0].y + bounds[0].height && y < HEIGHT - HeaderBar.HAIRLINE; y++) {
            for (int x = bounds[0].x; x < bounds[0].x + bounds[0].width && x < WIDTH; x++) {
                total++;
                if (pixel(bare, x, y).equals(pixel(image, x, y))) {
                    same++;
                }
            }
        }
        assertTrue(total > 200, "the menus covered an area worth comparing, got " + total);
        return (float) same / total;
    }

    // --- the rule -----------------------------------------------------------------------------
    @Test
    void theBottomRuleIsLitWithTheBrandOnItsLeftEnd() throws Exception {
        BufferedImage image = paint(new HeaderBar(() -> false, () -> { }), WIDTH, HEIGHT);
        Color edge = pixel(image, 2, HEIGHT - 1);
        assertNotEquals(pixel(image, 2, BODY_ROW), edge, "the rule is drawn, not the wash run on down");
        assertEquals(UiPalette.BRAND, movedTowards(edge), "and it carries the brand's light");
    }

    @Test
    void theRuleBelongsToTheBottomRowsOnly() throws Exception {
        BufferedImage image = paint(new HeaderBar(() -> false, () -> { }), WIDTH, HEIGHT);
        Color body = pixel(image, 2, BODY_ROW);
        for (int y = 0; y < HEIGHT - HeaderBar.HAIRLINE; y++) {
            assertEquals(body, pixel(image, 2, y), "row " + y + " is wash, not rule");
        }
    }

    // --- the handle ---------------------------------------------------------------------------

    @Test
    void aDoubleClickOnTheBarTogglesFullScreenOnce() throws Exception {
        AtomicInteger toggles = new AtomicInteger();
        HeaderBar bar = new HeaderBar(() -> false, toggles::incrementAndGet);
        JLabel label = new JLabel("File");
        onEdt(() -> bar.add(label));
        // A real double-click on that label is one event object handed to the label and then to the
        // bar; answered twice it maximizes and restores in the same beat, which reads as a dead bar.
        onEdt(() -> {
            MouseEvent click = new MouseEvent(label, MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, 4, 4, 2, false, MouseEvent.BUTTON1);
            fire(label, click, true);
            fire(bar, click, true);
        });
        assertEquals(1, toggles.get(), "one press pair, one toggle");
    }

    @Test
    void aDoubleClickOnAControlIsNotTheBarsDoubleClick() throws Exception {
        AtomicInteger toggles = new AtomicInteger();
        HeaderBar bar = new HeaderBar(() -> false, toggles::incrementAndGet);
        HoverTint control = new HoverTint("×", UiPalette.ATTENTION, "Close");
        onEdt(() -> bar.add(control));
        onEdt(() -> {
            MouseEvent click = new MouseEvent(control, MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, 4, 4, 2, false, MouseEvent.BUTTON1);
            fire(control, click, true);
            fire(bar, click, true);
        });
        assertEquals(0, toggles.get(), "the bar does not steal the control's own press");
    }

    @Test
    void theHandleReachesEveryPartOfTheBarExceptTheControls() throws Exception {
        HeaderBar bar = new HeaderBar(() -> false, () -> { });
        JPanel strip = new JPanel();
        HoverTint control = new HoverTint("□", UiPalette.LIVE, "Maximize / restore");
        JButton plain = new JButton("×");
        onEdt(() -> {
            strip.add(control);
            bar.add(strip);
            bar.add(plain);
        });
        assertTrue(bar.isHandle(bar), "the bare bar drags the window");
        assertTrue(bar.isHandle(strip), "so does the strip beside it");
        assertFalse(bar.isHandle(control), "a control keeps its clicks to itself");
        assertFalse(bar.isHandle(plain), "and so does any other button");
    }

    // --- the hover tint -----------------------------------------------------------------------

    @Test
    void anUntouchedControlCarriesNoColour() throws Exception {
        HoverTint quit = new HoverTint("×", UiPalette.ATTENTION, "Close");
        Color idle = pixel(paint(quit, CONTROL_W, CONTROL_H), PILL_X, CONTROL_H / 2);
        assertEquals(surface, idle, "no pointer, no pill: that pixel is the bar showing through");
    }

    @Test
    void eachControlTintsToItsOwnHue() throws Exception {
        assertTint(new HoverTint("×", UiPalette.ATTENTION, "Close"), UiPalette.ATTENTION);
        assertTint(new HoverTint("—", UiPalette.PENDING, "Minimize"), UiPalette.PENDING);
        assertTint(new HoverTint("□", UiPalette.LIVE, "Maximize / restore"), UiPalette.LIVE);
        assertTint(new HoverTint("⚙", UiPalette.accent(), "Settings"), UiPalette.accent());
    }

    @Test
    void aLitControlColoursItsGlyphToo() throws Exception {
        HoverTint quit = new HoverTint("×", UiPalette.ATTENTION, "Close");
        Color idleGlyph = darkest(paint(quit, CONTROL_W, CONTROL_H));
        assertEquals(UiPalette.IDLE, movedTowards(idleGlyph),
                "untouched, the glyph is simply the theme's text colour: " + describe(idleGlyph));
        Color litGlyph = darkest(paint(lit(quit), CONTROL_W, CONTROL_H));
        assertEquals(UiPalette.ATTENTION, movedTowards(litGlyph),
                "crossed, the glyph is drawn in the control's hue as well as sat on it: "
                        + describe(litGlyph));
    }

    @Test
    void aPaintedControlHandsItsColourBack() throws Exception {
        HoverTint quit = lit(new HoverTint("×", UiPalette.ATTENTION, "Close"));
        Color before = onEdtReturn(quit::getForeground);
        paint(quit, CONTROL_W, CONTROL_H);
        assertEquals(before, onEdtReturn(quit::getForeground),
                "the hover borrows the foreground for one paint and does not keep it");
    }

    @Test
    void thePillIsARoundedLozengeThatTheHeightRules() {
        // A square radius would outlive the glyph it sits under as the interface size grows; the
        // shorter side decides, so a control stays a lozenge at the smallest and the largest size.
        assertEquals(HoverTint.pillArc(CONTROL_W, CONTROL_H), HoverTint.pillArc(400, CONTROL_H));
        assertTrue(HoverTint.pillArc(20, 14) >= 4, "and never turns square");
    }

    // --- the brand mark -----------------------------------------------------------------------

    @Test
    void theMarkIsABadgeThatRulesTheBar() throws Exception {
        // The plate and its rim are what make the banner read as a name. Measured against the line the
        // menus are written on, they collapse into a dark smudge, so the mark sets the bar's height.
        BrandMark mark = new BrandMark();
        int[] measured = onEdtReturn(() -> {
            java.awt.FontMetrics fm = mark.getFontMetrics(mark.getFont());
            Dimension preferred = mark.getPreferredSize();
            return new int[]{fm.getHeight(), BrandMark.edge(fm), preferred.width, preferred.height};
        });
        assertTrue(measured[1] > measured[0], "the badge outgrows the line it sits on: "
                + measured[1] + " over " + measured[0]);
        assertEquals(measured[1], measured[3], "and the bar is measured to the badge, not the reverse");
        assertTrue(measured[2] > 3 * measured[1] && measured[2] < 5 * measured[1],
                "the width is the banner's own aspect, not a square: " + measured[2] + "x" + measured[1]);
    }

    @Test
    void theMarkIsDrawnInsideItsOwnBounds() throws Exception {
        BrandMark mark = new BrandMark();
        int width = onEdtReturn(() -> mark.getPreferredSize().width);
        // Painted onto nothing, because the question is where the mark's own alpha stops: on a
        // filled surface an empty corner and a pasted box look the same.
        BufferedImage image = new BufferedImage(Math.max(width, 60), 30, BufferedImage.TYPE_INT_ARGB);
        onEdt(() -> {
            mark.setSize(image.getWidth(), image.getHeight());
            mark.validate();
            mark.paint(image.createGraphics());
        });
        assertEquals(0, image.getRGB(0, image.getHeight() - 1) >>> 24,
                "the corner is empty, not a pasted white box");
        assertTrue(hasPainted(image), "and the mark itself lands inside those bounds");
    }

    @Test
    void theMarkGrowsWithTheFontItIsWrittenIn() throws Exception {
        BrandMark small = new BrandMark();
        BrandMark large = new BrandMark();
        onEdt(() -> {
            small.setFont(Typefaces.ui(java.awt.Font.BOLD, 9));
            large.setFont(Typefaces.ui(java.awt.Font.BOLD, 22));
        });
        int smallWidth = onEdtReturn(() -> small.getPreferredSize().width);
        int largeWidth = onEdtReturn(() -> large.getPreferredSize().width);
        int smallEdge = onEdtReturn(() -> BrandMark.edge(small.getFontMetrics(small.getFont())));
        int largeEdge = onEdtReturn(() -> BrandMark.edge(large.getFontMetrics(large.getFont())));
        assertTrue(largeEdge > smallEdge, "the artwork is the line height, so the slider moves it");
        assertTrue(largeWidth > smallWidth, "and the banner is given the room its own aspect needs");
    }

    // --- helpers ------------------------------------------------------------------------------

    private void assertTint(HoverTint control, Color hue) throws Exception {
        Color idle = pixel(paint(control, CONTROL_W, CONTROL_H), PILL_X, CONTROL_H / 2);
        Color hovered = pixel(paint(lit(control), CONTROL_W, CONTROL_H), PILL_X, CONTROL_H / 2);
        assertEquals(surface, idle, "before the pointer: " + describe(idle));
        assertNotEquals(surface, hovered, "the pill did not arrive: " + describe(hovered));
        assertEquals(hue, movedTowards(hovered), "and it arrived in the control's own hue: "
                + describe(hovered));
    }

    /**
     * Which hue a painted pixel walked towards from the surface. The pill and the wash are both
     * translucent tints, so their strength is not the point of these tests — only the direction,
     * which is what says "this is the brand's cyan" rather than "this is some blue".
     */
    private Color movedTowards(Color painted) {
        Color[] hues = {UiPalette.BRAND, UiPalette.LIVE, UiPalette.PENDING, UiPalette.ATTENTION,
                UiPalette.IDLE, UiPalette.accent()};
        Color best = null;
        double bestAngle = Double.MAX_VALUE;
        double px = painted.getRed() - surface.getRed();
        double py = painted.getGreen() - surface.getGreen();
        double pz = painted.getBlue() - surface.getBlue();
        double pm = Math.sqrt(px * px + py * py + pz * pz);
        assertTrue(pm > 1, "a pixel identical to the surface walks nowhere: " + describe(painted));
        for (Color hue : hues) {
            double hx = hue.getRed() - surface.getRed();
            double hy = hue.getGreen() - surface.getGreen();
            double hz = hue.getBlue() - surface.getBlue();
            double hm = Math.sqrt(hx * hx + hy * hy + hz * hz);
            if (hm < 1) continue;
            double cosine = (px * hx + py * hy + pz * hz) / (pm * hm);
            // A tint is the hue mixed towards the surface, so the walk is from the hue to the
            // surface: the painted pixel sits on that ray, and the shortest turn to it wins.
            double angle = Math.acos(Math.max(-1, Math.min(1, cosine)));
            if (angle < bestAngle - 1e-9) {
                bestAngle = angle;
                best = hue;
            }
        }
        return best;
    }

    private static String describe(Color c) {
        return "#" + Integer.toHexString(c.getRGB()) + " rgb(" + c.getRed() + "," + c.getGreen()
                + "," + c.getBlue() + ")";
    }

    /** The pixel furthest from the surface: the glyph, not the wash behind it. */
    private Color darkest(BufferedImage image) {
        Color painted = null;
        int best = -1;
        for (int y = 4; y < image.getHeight() - 4; y++) {
            for (int x = 8; x < image.getWidth() - 8; x++) {
                Color c = pixel(image, x, y);
                int d = distance(c, surface);
                if (d > best) {
                    best = d;
                    painted = c;
                }
            }
        }
        assertTrue(best > 40, "nothing was drawn there: " + describe(painted));
        return painted;
    }

    /** Put the pointer on a control and wait for its hover to cross and settle. */
    private HoverTint lit(HoverTint control) throws Exception {
        onEdt(() -> {
            control.setSize(CONTROL_W, CONTROL_H);
            UNDER.add(control);
            control.dispatchEvent(new MouseEvent(control, MouseEvent.MOUSE_MOVED,
                    System.currentTimeMillis(), 0, CONTROL_W / 2, CONTROL_H / 2, 0, false));
        });
        await("the pill to cross", () -> control.hoverLevel() >= 1f);
        return control;
    }

    /** The component painted at a laid-out size onto the surface it will be judged against. */
    private BufferedImage paint(JComponent c, int w, int h) throws Exception {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D background = image.createGraphics();
        background.setColor(surface);
        background.fillRect(0, 0, w, h);
        background.dispose();
        onEdt(() -> {
            c.setSize(w, h);
            c.validate();
            c.paint(image.createGraphics());
        });
        return image;
    }

    private static Color pixel(BufferedImage image, int x, int y) {
        return new Color(image.getRGB(x, y), true);
    }

    private static int distance(Color a, Color b) {
        return Math.abs(a.getRed() - b.getRed()) + Math.abs(a.getGreen() - b.getGreen())
                + Math.abs(a.getBlue() - b.getBlue());
    }

    private static boolean hasPainted(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > 200) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Hand {@code c} the listener call an event bubbling from it would have produced. */
    private static void fire(JComponent c, MouseEvent e, boolean clicks) {
        if (clicks) {
            for (MouseListener l : c.getMouseListeners()) {
                l.mouseClicked(e);
            }
        }
    }

    private static void onEdt(Runnable work) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            work.run();
        } else {
            SwingUtilities.invokeAndWait(work);
        }
    }

    private static <T> T onEdtReturn(Callable<T> work) throws Exception {
        final Object[] box = new Object[1];
        onEdt(() -> {
            try {
                box[0] = work.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        @SuppressWarnings("unchecked")
        T value = (T) box[0];
        return value;
    }

    private static void await(String what, Callable<Boolean> done) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(onEdtReturn(done))) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
