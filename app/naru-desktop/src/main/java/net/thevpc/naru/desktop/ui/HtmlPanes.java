package net.thevpc.naru.desktop.ui;

import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicTextUI;
import javax.swing.text.DefaultCaret;
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
     * <p>
     * These four numbers are one ladder, not four preferences: a preview must be shorter than a
     * folded head, and a folded head must be shorter than an expanded body however it is hosted.
     * At the measured 18px a line, twelve lines is a fifth of the transcript pane, so folding an
     * item always makes it smaller — which is what the control on the card promises.
     */
    static final int FOLD_AFTER_LINES = 12;

    /** Lines a collapsed item shows when the reader has explicitly put it away. */
    static final int PREVIEW_LINES = 3;

    /**
     * An expanded body longer than this gets its own vertical scroll instead of the whole column.
     * Kept at the height of {@link #SCROLL_HEIGHT} in lines so switching a body from inline to
     * scrolled is a wash, not a jump.
     */
    static final int SCROLL_AFTER_LINES = 22;

    /** The height cap for an expanded body's own scrollport, in pixels. */
    static final int SCROLL_HEIGHT = 420;

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

    /** Logical line count; a body that fits on one line is never worth a scrollbar of its own. */
    static int lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /**
     * Host {@code body} in a capped vertical scroller. The viewport keeps the HTML pane at the width
     * of the card, so lines still wrap; only the height is bounded. The scroller is transparent so
     * the card's own tint and gutter show through.
     */
    static JScrollPane scroller(JEditorPane body) {
        JScrollPane scroller = new JScrollPane(body,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroller.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        scroller.setViewportBorder(BorderFactory.createEmptyBorder());
        scroller.setOpaque(false);
        scroller.getViewport().setOpaque(false);
        scroller.getVerticalScrollBar().setUnitIncrement(16);
        scroller.setPreferredSize(new Dimension(120, SCROLL_HEIGHT));
        scroller.putClientProperty("naru.body.scroller", Boolean.TRUE);
        forwardWheelAtEdges(scroller);
        return scroller;
    }

    /**
     * A wheel over a nested scroller has to keep moving the column once that scroller reaches its
     * end; otherwise the gesture is trapped in the capped body and the reader has to find the
     * outer scrollbar to continue. The nearest enclosing scroll pane is the transcript column.
     */
    private static void forwardWheelAtEdges(JScrollPane inner) {
        inner.addMouseWheelListener(e -> {
            JScrollBar body = inner.getVerticalScrollBar();
            boolean up = e.getWheelRotation() < 0;
            boolean atTop = body.getValue() <= body.getMinimum();
            boolean atBottom = body.getValue() + body.getVisibleAmount() >= body.getMaximum();
            if (!((up && atTop) || (!up && atBottom))) {
                return;
            }
            Container parent = inner.getParent();
            while (parent != null && !(parent instanceof JScrollPane)) {
                parent = parent.getParent();
            }
            if (!(parent instanceof JScrollPane outer) || outer == inner) {
                return;
            }
            JScrollBar column = outer.getVerticalScrollBar();
            if (!column.isVisible()) {
                return;
            }
            int before = column.getValue();
            int steps = Math.max(1, e.getScrollAmount());
            column.setValue(before + e.getWheelRotation() * steps * column.getUnitIncrement());
            // At the column's own end, leave the event alone: the transcript's wheel listener
            // still has to load the next page when the gesture asks for one.
            if (column.getValue() == before) {
                return;
            }
            e.consume();
        });
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
        // A read-only body is read, not written to, so it must never scroll itself. The HTML kit
        // parses into the document after setText returns, and a caret sitting at position 0 in an
        // empty document rides every insertion to the end — which drags a card's own scrollport to
        // the last line of a body the reader just asked to see the start of, and yanks it there
        // again on each streamed batch.
        if (pane.getCaret() instanceof DefaultCaret caret) {
            caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        }
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
        /** The document {@link #contentChanged} is registered against; re-taken on every re-text. */
        private Document tracked;

        private final DocumentListener contentChanged = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                forget();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                forget();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                forget();
            }
        };

        MeasuredPane(String html) {
            super("text/html", html);
            followDocument();
        }

        @Override
        public void setText(String t) {
            forget();
            super.setText(t);
            followDocument();
        }

        @Override
        public void setDocument(Document doc) {
            super.setDocument(doc);
            // Re-texting a body — a streamed answer growing, a refreshed card — changes the height
            // it needs, so the cached measurement is dropped and taken again on the next query.
            measuredDocument = null;
            forget();
        }

        /**
         * Watch the document this pane is now showing. Swing's HTML kit parses into the document
         * after {@code setText} returns, so a body measured on the layout pass that follows the
         * call has only its first screen in the view tree; the rest arrives as document events.
         * Without this the measurement taken too early is cached forever, and an unfolded body
         * keeps the height its folded head had — a tall card showing twelve lines and blank paper.
         */
        private void followDocument() {
            Document doc = getDocument();
            if (tracked == doc) {
                return;
            }
            if (tracked != null) {
                tracked.removeDocumentListener(contentChanged);
            }
            tracked = doc;
            if (doc != null) {
                doc.addDocumentListener(contentChanged);
            }
        }

        private void forget() {
            preferred = null;
            minimum = null;
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
