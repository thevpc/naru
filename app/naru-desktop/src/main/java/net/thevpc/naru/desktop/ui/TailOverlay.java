package net.thevpc.naru.desktop.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLayeredPane;
import javax.swing.JScrollPane;
import javax.swing.UIManager;
import java.awt.Component;

/**
 * The transcript's scroll pane with a floating "jump to latest" button over its lower-right
 * corner.
 * <p>
 * The button is a sibling of the scroll pane, not a child of it. A viewport's optimized scroll
 * path blits its backing store straight onto the screen; anything painted inside the scroll pane
 * (including a child added above the viewport) can be erased by that blit and is only restored by
 * a full repaint. Keeping the button in a layer above the scroll pane means the viewport cannot
 * paint over it, while the button still follows the scroll pane's size.
 */
final class TailOverlay extends JLayeredPane {

    private static final int MARGIN = 14;

    private final JScrollPane scroll;
    private final JButton tail = new JButton("↓");
    private final Runnable onLayout;

    TailOverlay(Component view, Runnable onJump, Runnable onLayout) {
        this.onLayout = onLayout;
        scroll = new JScrollPane(view);
        setLayout(null);
        tail.setToolTipText("Jump to the newest message");
        tail.getAccessibleContext().setAccessibleName("Jump to the newest message");
        tail.setFocusable(false);
        // FlatLaf draws a usable rounded button from this hint; plain look-and-feels still get the
        // opaque background below, so the glyph stays visible over the cards.
        tail.putClientProperty("JButton.buttonType", "roundRect");
        tail.setBackground(UIManager.getColor("Panel.background"));
        tail.setForeground(UiPalette.readable(UiPalette.accent()));
        tail.setOpaque(true);
        tail.setBorder(BorderFactory.createLineBorder(UiPalette.border()));
        ActionButtons.glyphSized(tail);
        tail.setVisible(false);
        tail.addActionListener(e -> onJump.run());
        add(scroll, Integer.valueOf(DEFAULT_LAYER));
        add(tail, Integer.valueOf(POPUP_LAYER));
    }

    JScrollPane scrollPane() {
        return scroll;
    }

    void setTailButtonVisible(boolean visible) {
        if (tail.isVisible() != visible) {
            tail.setVisible(visible);
            // The overlay already gave the button its bounds; repainting only the button is enough
            // and avoids asking the transcript for a wider repaint.
            tail.repaint();
        }
    }

    boolean isTailButtonVisible() {
        return tail.isVisible();
    }

    /** Exposed for the headless scroll test: the click goes through the real button. */
    JButton tailButton() {
        return tail;
    }

    @Override
    public boolean isOptimizedDrawingEnabled() {
        // The button always overlaps the scroll pane; Swing must not treat these children as a
        // tiled, non-overlapping set.
        return false;
    }

    @Override
    public void doLayout() {
        super.doLayout();
        scroll.setBounds(0, 0, getWidth(), getHeight());
        // Reserve the scrollbar's width even while it is hidden. The scrollbar can appear when the
        // first batch of cards makes the column taller; the overlay is not re-laid out for that, so
        // measuring only a visible scrollbar would leave the button overlapping it afterwards.
        int scrollbar = scroll.getVerticalScrollBar().isVisible()
                && scroll.getVerticalScrollBar().getWidth() > 0
                ? scroll.getVerticalScrollBar().getWidth()
                : scroll.getVerticalScrollBar().getPreferredSize().width;
        int width = tail.getPreferredSize().width;
        int height = tail.getPreferredSize().height;
        int x = Math.max(MARGIN, getWidth() - scrollbar - width - MARGIN);
        int y = Math.max(MARGIN, getHeight() - height - MARGIN);
        tail.setBounds(x, y, width, height);
        // A resize can change how much of the transcript fits; the button must follow that geometry
        // even when no scrollbar adjustment follows the layout.
        onLayout.run();
    }
}
