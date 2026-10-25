package net.thevpc.naru.desktop.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.UIManager;
import java.awt.Component;

/**
 * The transcript's scroll pane with a floating "jump to latest" button over its lower-right
 * corner.
 * <p>
 * The button is an ordinary child of the scroll pane, not part of its view: it must stay put while
 * the cards scroll and must not take width from them. {@link javax.swing.ScrollPaneLayout} does
 * not manage an unconstrained child, so {@link #doLayout()} places it after the viewport and the
 * scrollbars have been laid out. That keeps the scroll pane itself at component index 1 of the
 * transcript, which is what the existing scroll tests address.
 */
final class TailScrollPane extends JScrollPane {

    private static final int MARGIN = 14;

    private final JButton tail = new JButton("↓");

    TailScrollPane(Component view, Runnable onJump) {
        super(view);
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
        add(tail);
    }

    void setTailButtonVisible(boolean visible) {
        if (tail.isVisible() != visible) {
            tail.setVisible(visible);
            // A newly visible child needs a layout pass before doLayout can place it over the
            // viewport; without this the first time the reader scrolls away it stays at 0×0.
            revalidate();
            repaint();
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
    public void doLayout() {
        super.doLayout();
        int scrollbar = getVerticalScrollBar() != null && getVerticalScrollBar().isVisible()
                ? getVerticalScrollBar().getWidth() : 0;
        int width = tail.getPreferredSize().width;
        int height = tail.getPreferredSize().height;
        int x = Math.max(MARGIN, getWidth() - scrollbar - width - MARGIN);
        int y = Math.max(MARGIN, getHeight() - height - MARGIN);
        tail.setBounds(x, y, width, height);
    }
}
