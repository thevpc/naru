package net.thevpc.naru.desktop.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a glyph button's hover is allowed to look like.
 *
 * <p>The interface rounds buttons with a 16px pill radius. On a text button that is a pill; on a
 * 25x23 icon square FlatLaf clamps the radius to half the height, so the hover becomes a disc — a
 * lit circle stamped over the glyph, which a reader takes for a spinner on the control they are
 * pointing at. The test paints the real rollover and measures how wide the decoration is one pixel
 * below its top edge: a rounded rect is nearly at full width there, a disc is not.
 */
class ActionButtonsTest {

    private static javax.swing.LookAndFeel previousLaf;
    private static Object previousButtonArc;
    private static Object previousComponentArc;
    private static Object previousFocusWidth;

    @BeforeAll
    static void useTheShippedLook() throws Exception {
        previousLaf = UIManager.getLookAndFeel();
        previousButtonArc = UIManager.get("Button.arc");
        previousComponentArc = UIManager.get("Component.arc");
        previousFocusWidth = UIManager.get("Component.focusWidth");
        UIManager.setLookAndFeel(new FlatDarkLaf());
        // the overrides NaruDesktop installs, so the radius under test is the one that ships
        UIManager.put("Button.arc", 16);
        UIManager.put("Component.arc", 14);
        UIManager.put("Component.focusWidth", 1);
    }

    @AfterAll
    static void restoreTheLook() throws Exception {
        UIManager.put("Button.arc", previousButtonArc);
        UIManager.put("Component.arc", previousComponentArc);
        UIManager.put("Component.focusWidth", previousFocusWidth);
        UIManager.setLookAndFeel(previousLaf);
    }

    @Test
    void aGlyphButtonHoverIsARoundedRectNotADisc() throws Exception {
        JButton b = GlyphButton.of("✎", "Rewrite this item");
        JPanel host = new JPanel();
        host.setOpaque(true);
        host.setBackground(new Color(0x4B5060));
        host.add(b);
        JFrame frame = new JFrame("action-buttons-test");
        frame.setUndecorated(true);
        SwingUtilities.invokeAndWait(() -> {
            frame.getContentPane().add(host);
            frame.setSize(140, 70);
            frame.setVisible(true);
            b.setSize(25, 23);
        });
        Thread.sleep(400);
        hover(b);
        Thread.sleep(400);

        BufferedImage img = new BufferedImage(b.getWidth(), b.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x4B5060));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        b.paint(g);
        g.dispose();

        int middle = span(img, b.getHeight() / 2);
        int top = span(img, 1);
        assertTrue(middle > 0, "the hover paints nothing at all");
        assertTrue(middle - top <= 2,
                "the hover is a disc, not a rounded rect: one pixel below its top edge it is " + top
                        + " wide against " + middle + " at the middle");
        SwingUtilities.invokeAndWait(frame::dispose);
    }

    /** How wide the hover decoration is on one row of the button. */
    private static int span(BufferedImage img, int y) {
        Color rest = new Color(0x4B5060);
        int first = -1;
        int last = -1;
        for (int x = 0; x < img.getWidth(); x++) {
            if (!new Color(img.getRGB(x, y)).equals(rest)) {
                if (first < 0) {
                    first = x;
                }
                last = x;
            }
        }
        return first < 0 ? 0 : last - first + 1;
    }

    private static void hover(JButton b) {
        SwingUtilities.invokeLater(() -> {
            int x = b.getWidth() / 2;
            int y = b.getHeight() / 2;
            b.dispatchEvent(new MouseEvent(b, MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(),
                    0, x, y, 0, false));
            b.dispatchEvent(new MouseEvent(b, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(),
                    0, x, y, 0, false));
        });
    }
}
