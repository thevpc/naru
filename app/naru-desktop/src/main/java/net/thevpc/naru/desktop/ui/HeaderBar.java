package net.thevpc.naru.desktop.ui;

import javax.swing.BorderFactory;
import javax.swing.AbstractButton;
import javax.swing.JList;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.util.function.BooleanSupplier;

/**
 * The window's own top bar: a brand wash under the chrome and a lit hairline under that.
 * <p>
 * This is the surface that says the window is NARU's. It is a panel rather than a colour handed to
 * the look-and-feel because the wash has to be anchored on the brand at the left and fade towards
 * the window controls at the right — a flat theme colour cannot do that, and a solid brand bar would
 * put a sheet of cyan behind grey menu text.
 * <p>
 * So the wash is a tint of whatever the theme paints, not a hue laid over it: its strongest point is
 * a fraction of the way from the surface to the brand, and it thins to the surface itself before the
 * middle of the bar. Every menu, label and control keeps its own look-and-feel contrast, and a theme
 * change moves the bar with the rest of the interface because the colours are resolved as it paints.
 * <p>
 * The bar is also the window's handle: it drags the frame and answers a double-click with
 * maximize/restore, except over the controls themselves, which keep their own clicks.
 */
public final class HeaderBar extends JPanel {

    /** The lit rule at the bottom of the bar. Two rows: one reads as a shadow, this reads as an edge. */
    static final int HAIRLINE = 2;

    /** How far the wash walks towards the brand hue at its strongest, by background brightness. */
    private static final float WASH_DARK = 0.22f;
    private static final float WASH_LIGHT = 0.13f;

    /**
     * A menu bar that lets the wash reach it.
     * <p>
     * Clearing opacity is not enough: the look-and-feel keeps the window background on a menu bar as
     * a {@code UIResource}, and its paint pass fills that rectangle over whatever the bar drew — a
     * pale box behind File/View/Session/Help. A plain colour with no alpha is what makes that fill a
     * no-op, and it survives a theme change because a look-and-feel only replaces backgrounds it set.
     */
    public static JMenuBar washedMenuBar() {
        JMenuBar menus = new JMenuBar();
        menus.setOpaque(false);
        menus.setBackground(new Color(0, 0, 0, 0));
        return menus;
    }

    /** Whether the frame is at full screen: a maximized undecorated window must not be dragged. */
    private final BooleanSupplier maximized;    /** What a double-click on the bar does. */
    private final Runnable toggleMaximize;

    /** Where the pointer was when the current drag began, in screen coordinates. */
    private Point dragOffset;
    /** The double-click already answered, so its trip up the parent chain is counted once. */
    private MouseEvent handled;

    private final MouseAdapter drag = new MouseAdapter() {
        @Override
        public void mousePressed(MouseEvent e) {
            Window window = window();
            if (e.getButton() != MouseEvent.BUTTON1 || window == null || maximized.getAsBoolean()) {
                return;
            }
            Point onScreen = e.getLocationOnScreen();
            Point here = window.getLocation();
            dragOffset = new Point(onScreen.x - here.x, onScreen.y - here.y);
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            Window window = window();
            if (dragOffset == null || window == null || maximized.getAsBoolean()) {
                return;
            }
            Point onScreen = e.getLocationOnScreen();
            window.setLocation(onScreen.x - dragOffset.x, onScreen.y - dragOffset.y);
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            dragOffset = null;
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() != 2 || e.getButton() != MouseEvent.BUTTON1) {
                return;
            }
            // One real double-click travels up from the component under the pointer, and the same
            // event object is offered to each of them. Answering twice would toggle twice and the
            // bar would look dead.
            if (handled == e) {
                return;
            }
            handled = e;
            // Events from the controls arrive here by propagation too; a double-click on the
            // maximize button has to stay that button's own single press.
            for (Component c = e.getComponent(); c != null && c != HeaderBar.this; c = c.getParent()) {
                if (c instanceof AbstractButton) {
                    return;
                }
            }
            toggleMaximize.run();
        }
    };

    /**
     * @param maximized      whether the frame is at full screen, so a drag is refused there
     * @param toggleMaximize what a double-click on the bar does
     */
    public HeaderBar(BooleanSupplier maximized, Runnable toggleMaximize) {
        super(new BorderLayout());
        this.maximized = maximized;
        this.toggleMaximize = toggleMaximize;
        // Bottom room for the hairline, so the chrome never sits on top of the rule it is drawn under.
        setBorder(BorderFactory.createEmptyBorder(3, 8, HAIRLINE + 1, 8));
        addMouseListener(drag);
        addMouseMotionListener(drag);
    }

    @Override
    protected void addImpl(Component comp, Object constraints, int index) {
        super.addImpl(comp, constraints, index);
        // Anything added later — a menu, the brand mark, a control panel — has to be part of the
        // handle, or the bar would drag in some places and stick in others.
        watch(comp, drag);
    }

    /** Attach the handle to {@code c} and everything under it that is not itself a control. */
    private static void watch(Component c, MouseAdapter handler) {
        if (c instanceof JMenuBar || c instanceof AbstractButton || c instanceof JList<?>) {
            return;
        }
        c.addMouseListener(handler);
        c.addMouseMotionListener(handler);
        if (c instanceof Container container) {
            for (Component nested : container.getComponents()) {
                watch(nested, handler);
            }
        }
    }

    private Window window() {
        return SwingUtilities.getWindowAncestor(this);
    }

    /**
     * True where the bar's own handle was attached. A control is not: it keeps its clicks, which is
     * the difference between a bar that drags and a bar whose buttons still do their jobs.
     */
    public boolean isHandle(Component c) {
        for (MouseMotionListener listener : c.getMouseMotionListeners()) {
            if (listener == drag) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= HAIRLINE) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        Color surface = surface();
        float wash = UiPalette.dark() ? WASH_DARK : WASH_LIGHT;
        Color brand = UiPalette.mix(surface, UiPalette.BRAND, wash);
        Color accent = UiPalette.mix(surface, UiPalette.accent(), wash * 0.45f);
        int mid = Math.max(1, Math.round(w * 0.45f));
        // Two segments, because one left-to-right gradient would put the accent at the far end —
        // behind the window controls, where it competes with the hover hues those buttons carry.
        g2.setPaint(new GradientPaint(0, 0, brand, mid, 0, accent, false));
        g2.fillRect(0, 0, mid, h - HAIRLINE);
        g2.setPaint(new GradientPaint(mid, 0, accent, w, 0, surface, false));
        g2.fillRect(mid, 0, w - mid, h - HAIRLINE);
        paintHairline(g2, w, h, surface);
        g2.dispose();
    }

    /** The lit rule: the brand at the left, thinning to the interface's own accent at the right. */
    private void paintHairline(Graphics2D g2, int w, int h, Color surface) {
        Color hot = UiPalette.BRAND;
        Color cold = UiPalette.accent();
        for (int row = 0; row < HAIRLINE; row++) {
            // The lower row is the lit edge and the upper one its soft shadow, so the bar reads as
            // raised over the content rather than as having a stripe of colour pasted on its bottom.
            float level = row == HAIRLINE - 1 ? 0.7f : 0.28f;
            g2.setPaint(new GradientPaint(0, 0, UiPalette.mix(surface, hot, level),
                    w, 0, UiPalette.mix(surface, cold, level * 0.8f), false));
            g2.fillRect(0, h - HAIRLINE + row, w, 1);
        }
    }

    /** The theme's own surface, which the wash is a tint of rather than a replacement for. */
    private static Color surface() {
        Color surface = UIManager.getColor("Panel.background");
        if (surface == null) surface = UIManager.getColor("control");
        return surface != null ? surface : Color.WHITE;
    }
}
