package net.thevpc.naru.desktop.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A transcript row is a flat layer: a coloured gutter stripe, an optional wash for fixed-width data
 * and a hairline that closes it. Nothing else of the row is painted, so the column reads as one
 * surface instead of a stack of boxes — which is exactly the property these tests pin, because the
 * old chrome failed it silently (a row that fills its own background looks fine until two of them
 * touch).
 */
class TranscriptLayerTest {

    private static final Color BODY = new Color(0xFF, 0xFF, 0xFF);
    private static final Color HUE = new Color(0x7B, 0x1F, 0xA2);

    /** Paint a row of the given size over an opaque surface and hand back what the paint drew. */
    private static BufferedImage paintRow(Color stripe, boolean tinted, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(BODY);
        g.fillRect(0, 0, width, height);
        g.dispose();
        JPanel row = new JPanel();
        row.setSize(width, height);
        Graphics2D paint = image.createGraphics();
        TranscriptLayer.paint(row, paint, stripe, tinted);
        paint.dispose();
        return image;
    }

    private static Color pixel(BufferedImage image, int x, int y) {
        return new Color(image.getRGB(x, y) & 0xFFFFFF);
    }

    @Test
    void aRowPaintsItsStripeItsRuleAndNothingElse() {
        BufferedImage image = paintRow(HUE, false, 120, 40);
        Color stripe = UiPalette.readable(HUE);
        for (int y = 0; y < 40; y++) {
            assertEquals(stripe, pixel(image, 1, y), "the gutter is the hue all the way down, row " + y);
        }
        assertEquals(stripe, pixel(image, TranscriptLayer.STRIPE - 1, 20));
        assertEquals(BODY, pixel(image, TranscriptLayer.STRIPE, 20), "the stripe stops at its width");
        assertEquals(BODY, pixel(image, 60, 20), "an untinted row leaves its body to the column");
        assertNotEquals(BODY, pixel(image, 60, 39), "the row closes with a hairline");
        assertEquals(BODY, pixel(image, 60, 38), "and the hairline is one line, not a border");
    }

    @Test
    void aRowWithNoHueKeepsTheRuleButClaimsNoColour() {
        BufferedImage image = paintRow(null, false, 120, 30);
        assertEquals(BODY, pixel(image, 1, 15), "no stripe where there is no hue to carry");
        assertNotEquals(BODY, pixel(image, 60, 29), "the row is still separated from the next");
    }

    @Test
    void aWashSuggestsTheHueWithoutPaintingIt() {
        BufferedImage plain = paintRow(HUE, false, 120, 40);
        BufferedImage washed = paintRow(HUE, true, 120, 40);
        Color body = pixel(plain, 60, 20);
        Color tinted = pixel(washed, 60, 20);
        assertNotEquals(body, tinted, "a data row is set apart from the prose around it");
        int lift = Math.abs(body.getRed() - tinted.getRed())
                + Math.abs(body.getGreen() - tinted.getGreen())
                + Math.abs(body.getBlue() - tinted.getBlue());
        assertTrue(lift < 90, "the wash stays a suggestion, got a channel change of " + lift);
    }

    @Test
    void theBodyStartsClearOfTheGutter() {
        JPanel row = new JPanel();
        row.setBorder(TranscriptLayer.border());
        int left = row.getBorder().getBorderInsets(row).left;
        assertTrue(left > TranscriptLayer.STRIPE + 4,
                "text must not touch the stripe, got left inset " + left);
        assertTrue(row.getBorder().getBorderInsets(row).bottom >= 4,
                "the hairline needs air below the last line");
    }

    @Test
    void aDeepHueIsLiftedOntoADarkSurfaceAndHeldDownOnALightOne() {
        Object previous = UIManager.put("Panel.background", new Color(0x1E, 0x1E, 0x1E));
        Color onDark;
        try {
            onDark = UiPalette.readable(HUE);
            assertTrue(UiPalette.dark(), "the theme probe must follow the panel colour");
            assertTrue(luminance(onDark) > 110,
                    "a purple chosen for a light theme sinks on a dark one, got " + onDark);
        } finally {
            UIManager.put("Panel.background", previous);
        }
        Color onLight = UiPalette.readable(HUE);
        assertTrue(luminance(onLight) < luminance(onDark),
                "the same hue must read darker on light than on dark: " + onLight + " / " + onLight);
        assertNotEquals(onDark, onLight, "a hue is adapted, not copied through");
    }

    @Test
    void aColourWithNoHueStaysGreyInsteadOfInventingOne() {
        Object previous = UIManager.put("Panel.background", new Color(0x1E, 0x1E, 0x1E));
        try {
            Color grey = UiPalette.readable(new Color(150, 150, 150));
            assertEquals(grey.getRed(), grey.getGreen(), "an achromatic gutter must not turn red");
            assertEquals(grey.getGreen(), grey.getBlue());
            assertTrue(luminance(grey) > luminance(new Color(150, 150, 150)),
                    "and it must still lift off a dark panel");
        } finally {
            UIManager.put("Panel.background", previous);
        }
    }

    private static double luminance(Color color) {
        return 0.299 * color.getRed() + 0.587 * color.getGreen() + 0.114 * color.getBlue();
    }
}
