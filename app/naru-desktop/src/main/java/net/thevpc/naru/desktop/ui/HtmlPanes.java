package net.thevpc.naru.desktop.ui;

import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicTextUI;
import javax.swing.text.Document;
import javax.swing.text.View;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.Objects;

/**
 * HTML bodies for transcript cards. They wrap at the width the card is given instead of pushing
 * the card wider (so the conversation panel keeps a single vertical scrollbar), and they report
 * the height the wrap needs at that width, re-measuring whenever the card is resized — this is
 * what lets a conversation re-flow its text when the window is resized.
 */
final class HtmlPanes {

    /**
     * Lines a transcript body shows before the rest is folded away. Swing's HTML renderer measures
     * every line of a body on each layout pass, so a four-thousand-line tool log costs seconds per
     * interface size change; a folded head keeps that cost bounded whatever the session accumulates.
     */
    static final int FOLD_AFTER_LINES = 120;

    private HtmlPanes() {
    }

    /** How many lines of {@code text} a folded body would hide, or 0 when it already fits. */
    static int hiddenLines(String text, int maxLines) {
        return Math.max(0, lineCount(text) - maxLines);
    }

    /** The first {@code maxLines} lines of {@code text}, which is what a folded body shows. */
    static String head(String text, int maxLines) {
        for (int i = 0, seen = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && ++seen == maxLines) {
                return text.substring(0, i);
            }
        }
        return text;
    }

    private static int lineCount(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /**
     * A non-selectable-conflicting, read-only HTML pane. {@code ratio} below zero keeps the
     * look-and-feel font; a ladder ratio (see {@link UiFonts}) sets the body on the ladder at that
     * size, so it follows the interface size and the theme like every other text in the window.
     * {@code verticalPadding} is the empty top/bottom border, in pixels.
     */
    static JEditorPane create(String html, float ratio, int verticalPadding) {
        JEditorPane pane = new MeasuredPane(html);
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.setBorder(BorderFactory.createEmptyBorder(verticalPadding, 0, verticalPadding, 0));
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        if (ratio >= 0) {
            UiFonts.apply(pane, ratio, java.awt.Font.PLAIN);
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

    /**
     * A body that lays its view tree out at the width it really occupies and keeps that measurement
     * until the width, the font or the text changes.
     * <p>
     * Measuring is not free: laying the tree out at this width and then asking for its spans walks
     * every logical line of the document. A layout pass wants a component's preferred, minimum and
     * maximum size, and one interface-size change walks the tree several times, so without the
     * cache a long answer is re-measured a dozen times over and the window stops painting while it
     * does.
     */
    private static final class MeasuredPane extends JEditorPane {

        private int measuredWidth = -1;
        private Font measuredFont;
        private Document measuredDocument;
        private Dimension preferred;
        private Dimension minimum;

        MeasuredPane(String html) {
            super("text/html", html);
        }

        @Override
        public void setDocument(Document doc) {
            super.setDocument(doc);
            // Re-texting a body — a streamed answer growing, a refreshed card — changes the height
            // it needs, so the cached measurement is dropped and taken again on the next query.
            measuredDocument = null;
        }

        @Override
        public Dimension getPreferredSize() {
            measure();
            return preferred != null ? preferred : super.getPreferredSize();
        }

        @Override
        public Dimension getMinimumSize() {
            measure();
            return minimum != null ? minimum : super.getMinimumSize();
        }

        private void measure() {
            int width = getWidth();
            Font font = getFont();
            Document document = getDocument();
            if (preferred != null && width == measuredWidth && document == measuredDocument
                    && Objects.equals(font, measuredFont)) {
                return;
            }
            measuredWidth = width;
            measuredFont = font;
            measuredDocument = document;
            View root = getUI() instanceof BasicTextUI ui ? ui.getRootView(this) : null;
            if (width <= 0 || root == null) {
                preferred = null;
                minimum = null;
                return;
            }
            // Swing's default measurement is done as if the text never wrapped; lay the view tree
            // out at the width this pane really occupies so the height matches the wrap.
            Insets insets = getInsets();
            root.setSize(Math.max(1, width - insets.left - insets.right), Integer.MAX_VALUE);
            preferred = new Dimension(width,
                    (int) Math.ceil(root.getPreferredSpan(View.Y_AXIS)) + insets.top + insets.bottom);
            // The minimum costs the same walk as the preferred size — it measures every line to find
            // the widest unbreakable run — so it is taken in this pass and cached with it, and only
            // after the layout above so that it is measured at the same width.
            minimum = super.getMinimumSize();
        }
    }
}
