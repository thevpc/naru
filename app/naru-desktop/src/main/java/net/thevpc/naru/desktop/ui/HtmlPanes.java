package net.thevpc.naru.desktop.ui;

import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;
import javax.swing.text.View;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * HTML bodies for transcript cards. They wrap at the width the card is given instead of pushing
 * the card wider (so the conversation panel keeps a single vertical scrollbar), and they report
 * the height the wrap needs at that width, re-measuring whenever the card is resized — this is
 * what lets a conversation re-flow its text when the window is resized.
 */
final class HtmlPanes {

    private HtmlPanes() {
    }

    /**
     * A non-selectable-conflicting, read-only HTML pane. {@code fontPointSize} below zero keeps
     * the look-and-feel font; {@code verticalPadding} is the empty top/bottom border, in pixels.
     */
    static JEditorPane create(String html, float fontPointSize, int verticalPadding) {
        JEditorPane pane = new JEditorPane("text/html", html) {
            @Override
            public Dimension getPreferredSize() {
                int width = getWidth();
                View root = getUI() instanceof javax.swing.plaf.TextUI ui ? ui.getRootView(this) : null;
                if (width <= 0 || root == null) {
                    return super.getPreferredSize();
                }
                // Swing's default measurement is done as if the text never wrapped; lay the view
                // tree out at the width this pane really occupies so the height matches the wrap.
                Insets insets = getInsets();
                root.setSize(Math.max(1, width - insets.left - insets.right), Integer.MAX_VALUE);
                return new Dimension(width,
                        (int) Math.ceil(root.getPreferredSpan(View.Y_AXIS)) + insets.top + insets.bottom);
            }
        };
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.setBorder(BorderFactory.createEmptyBorder(verticalPadding, 0, verticalPadding, 0));
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        if (fontPointSize >= 0) {
            pane.setFont(pane.getFont().deriveFont(fontPointSize));
        }
        // A new width changes the height this body needs: ask the card to measure again.
        pane.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                Container parent = pane.getParent();
                if (parent != null) {
                    SwingUtilities.invokeLater(parent::revalidate);
                }
            }
        });
        return pane;
    }
}
