package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.registry.NaruLineCompletion;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.List;

/**
 * The candidate list under the composer, holding exactly what the engine returned.
 * <p>
 * It never takes focus. Selection is driven by {@link #moveSelection(boolean)} from the text area's
 * own key bindings, which is also why this is not a {@code JMenuItem} popup: menu items cannot be
 * moved through with Up/Down while the text area keeps the caret.
 * <p>
 * It is drawn as a card in the window's {@link JLayeredPane} rather than shown as a
 * {@code JPopupMenu}, because a popup is only a lightweight child of that layered pane when it
 * happens to fit inside the window's bounds — otherwise Swing promotes it to its own top-level
 * window, and on X11 that window takes the keyboard. The first real-window run of the composer lost
 * Enter, Shift+Enter and every keystroke after them the moment the list appeared, while the
 * component test (a small frame, so the list fitted) passed. A layered-pane card is always inside
 * the window, so the question cannot come up: it is clipped to the window, it moves with the window,
 * and the text area keeps the caret.
 * <p>
 * The same trap exists one level down, in the mouse path, and a keyboard-only harness cannot see it:
 * {@code BasicListUI} calls {@code SwingUtilities2.adjustFocus(list)} on mouse press, which for a
 * list with {@code isFocusable()==false} hands focus to the window's next focusable component — in
 * the real main window that turned out to be a {@code JComboBox}, and the composer stopped receiving
 * keys: Enter activated the combo instead of taking the row. {@code setRequestFocusEnabled(false)}
 * is what closes it, because that is the other half of the same JDK condition, and the Robot-driven
 * run measured the difference ({@code composer-focus=false global=JComboBox} before, {@code true}
 * after).
 * <p>
 * The replacement span comes from {@link NaruLineCompletion.Result}, not from a word boundary the
 * widget guessed: the engine knows that {@code /model use ol} completes inside the third word and
 * that {@code /cat /tmp/fo} completes across slashes, and a host that re-derived those spans would
 * be a second implementation to keep in step.
 */
final class CompletionPopup {

    /** One cell is enough to read a candidate and stay inside the window; more needs scrolling. */
    private static final int VISIBLE_ROWS = 10;

    /**
     * A candidate row: one line of the list's own font plus the renderer's padding. A constant here
     * reads well at one interface size and clips the command name at every larger one.
     */
    private int cellHeight() {
        Font f = list.getFont() == null ? UiFonts.base() : list.getFont();
        return list.getFontMetrics(f).getHeight() + 4;
    }

    private final JPanel card = new JPanel(new BorderLayout());
    private final DefaultListModel<NaruLineCompletion.Candidate> entries = new DefaultListModel<>();
    private final JList<NaruLineCompletion.Candidate> list = new JList<>(entries);
    private NaruLineCompletion.Result result = NaruLineCompletion.Result.none("", 0, 0);
    /** Set by the composer: a double click on a row inserts it, exactly as Enter does. */
    private java.util.function.Consumer<NaruLineCompletion.Candidate> onPick = c -> {
    };
    /** The text area, so a click can give the keyboard straight back to it. */
    private Component owner;
    private JLayeredPane paneOfCard;
    private boolean shown;

    void setOnPick(java.util.function.Consumer<NaruLineCompletion.Candidate> handler) {
        this.onPick = handler;
    }

    CompletionPopup() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        UiFonts.applyMono(list, UiFonts.SECONDARY, Font.PLAIN);
        list.setCellRenderer((l, value, index, selected, focus) -> {
            javax.swing.JLabel label = new javax.swing.JLabel(labelOf(value, l));
            label.setOpaque(true);
            label.setBackground(selected ? l.getSelectionBackground() : l.getBackground());
            label.setForeground(selected ? l.getSelectionForeground() : l.getForeground());
            label.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
            return label;
        });
        // Nothing in this card may become the keyboard's destination: not the list, not the scroll
        // pane that wraps it, not the scrollbars the user may grab to reach row 37 of a long list.
        list.setFocusable(false);
        list.setRequestFocusEnabled(false);
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                highlightAt(e.getPoint());
            }

            @Override
            public void mouseDragged(java.awt.event.MouseEvent e) {
                highlightAt(e.getPoint());
            }

            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                highlightAt(e.getPoint());
                // One click chooses, the second commits — the way an IDE list behaves, and the way a
                // user who means "let me look at what I picked before I take it" expects it to.
                if (e.getClickCount() >= 2) {
                    commitSelected();
                }
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setFocusable(false);
        scroll.setVerifyInputWhenFocusTarget(false);
        scroll.getViewport().setFocusable(false);
        scroll.getVerticalScrollBar().setFocusable(false);
        scroll.getHorizontalScrollBar().setFocusable(false);
        card.add(scroll, BorderLayout.CENTER);
        card.setOpaque(true);
        card.setBackground(list.getBackground());
        card.setBorder(BorderFactory.createLineBorder(new Color(110, 110, 110)));
        // the card is decoration, not a destination: it must never be a focus candidate
        card.setFocusable(false);
        card.setName("naru-completion-card");
    }

    private void highlightAt(java.awt.Point p) {
        int i = list.locationToIndex(p);
        if (i >= 0 && i < entries.size()) {
            list.setSelectedIndex(i);
            list.ensureIndexIsVisible(i);
        }
        keepFocusOnOwner();
    }

    /**
     * Insert the highlighted row and hand the keyboard back. Both ways of committing a row come
     * through here — the double click and the composer's Enter — so they cannot drift apart.
     */
    void commitSelected() {
        NaruLineCompletion.Candidate c = list.getSelectedValue();
        keepFocusOnOwner();
        if (c != null) {
            onPick.accept(c);
        }
        hide();
    }

    private void keepFocusOnOwner() {
        if (owner instanceof JComponent j && j.isEnabled() && j.isShowing()) {
            j.requestFocusInWindow();
        }
    }

    /**
     * Show {@code r} under {@code at} (in {@code owner}'s coordinates). Returns false when there is
     * nothing to show, which the caller treats as "this keystroke did nothing", not as an error.
     */
    boolean show(Component owner, Rectangle at, NaruLineCompletion.Result r) {
        this.owner = owner;
        result = r;
        List<NaruLineCompletion.Candidate> candidates = r.candidates();
        if (candidates == null || candidates.isEmpty()) {
            hide();
            return false;
        }
        JLayeredPane pane = JLayeredPane.getLayeredPaneAbove(owner);
        if (pane == null) {
            // no window to draw in yet; the composer is not displayable, so there is nothing to ask
            return false;
        }
        if (pane != paneOfCard) {
            // the card lives in exactly one layered pane until that pane is replaced (a project
            // switch rebuilds the root), so this is the only move it ever makes
            if (card.getParent() != null) {
                card.getParent().remove(card);
            }
            pane.add(card, JLayeredPane.POPUP_LAYER);
            paneOfCard = pane;
        }
        entries.clear();
        // addAll fires a single interval event instead of one per candidate: the
        // per-element path made the list re-layout (and re-walk the layered pane's
        // component tree) for every row, which froze the interface on long transcripts
        entries.addAll(candidates);
        list.setSelectedIndex(0);
        // the row height is taken here, not once at construction: the interface size can change
        // between building the popup and opening it over the composer
        list.setFixedCellHeight(cellHeight());
        int rows = Math.min(candidates.size(), VISIBLE_ROWS);
        int height = rows * cellHeight() + 4;
        // wide enough for the longest candidate: a completion list the user has to scroll sideways
        // to read hides the row they were about to pick, and the rows are single-line by construction
        int widest = list.getPreferredSize().width + 24;
        int width = Math.max(300, Math.min(widest, Math.max(300, pane.getWidth() - 40)));
        card.setSize(width, height);
        card.setPreferredSize(new Dimension(width, height));
        card.setBounds(xInside(pane, owner, at.x, width),
                yInside(pane, owner, at, height), width, height);
        shown = true;
        card.setVisible(true);
        card.revalidate();
        card.repaint();
        return true;
    }

    /** Horizontal placement, kept inside the window so a wide candidate never runs off its edge. */
    private int xInside(JLayeredPane pane, Component owner, int ownerX, int width) {
        Point p = SwingUtilities.convertPoint(owner, new Point(ownerX, 0), pane);
        int max = pane.getWidth() - width;
        return Math.max(0, Math.min(p.x, Math.max(0, max)));
    }

    /**
     * Vertical placement in layered-pane coordinates: below the caret, or hung above it when the
     * list would not clear the window's bottom edge.
     */
    private int yInside(JLayeredPane pane, Component owner, Rectangle at, int height) {
        int below = SwingUtilities.convertPoint(owner, new Point(0, at.y + at.height), pane).y;
        int above = SwingUtilities.convertPoint(owner, new Point(0, at.y), pane).y - height;
        int y = below + height <= pane.getHeight() ? below : above;
        int max = pane.getHeight() - height;
        return max < 0 ? 0 : Math.max(0, Math.min(y, max));
    }

    /**
     * Close the list. Cheap and idempotent on purpose: the composer calls this from its document and
     * caret listeners, so it runs on every keystroke, and a card that was removed and re-added each
     * time made the whole window revalidate for a popup that was already gone.
     */
    void hide() {
        if (!shown && !card.isVisible()) {
            return;
        }
        shown = false;
        card.setVisible(false);
    }

    boolean isVisible() {
        return shown && card.isShowing() && !entries.isEmpty();
    }

    /**
     * Take the card out of the window for good. A composer being detached from its hierarchy — a
     * project switch rebuilding the panes — leaves no reason for its card to sit in a layered pane it
     * can never be shown in again, and a window that gets rebuilt more than once must not accumulate
     * one dead card per composer.
     */
    void detach() {
        hide();
        if (card.getParent() != null) {
            card.getParent().remove(card);
        }
        card.setVisible(false);
        paneOfCard = null;
    }

    /** @param down true for the next candidate, false for the previous; the ends do not wrap. */
    void moveSelection(boolean down) {
        int n = entries.size();
        if (n == 0) {
            return;
        }
        int i = list.getSelectedIndex();
        if (i < 0) {
            i = 0;
        } else {
            i = Math.max(0, Math.min(n - 1, i + (down ? 1 : -1)));
        }
        list.setSelectedIndex(i);
        list.ensureIndexIsVisible(i);
    }

    NaruLineCompletion.Candidate selected() {
        return list.getSelectedValue();
    }

    int replaceStart() {
        return result.replaceStart();
    }

    int replaceEnd() {
        return result.replaceEnd();
    }

    // ── harness-facing reads: a gate run has to measure the list, not assume it drew ──

    int candidateCount() {
        return entries.size();
    }

    /** The window the list is drawn in — layered-pane card, never a separate top-level window. */
    Window hostWindow() {
        return card.getParent() == null ? null
                : SwingUtilities.getWindowAncestor(card.getParent());
    }

    Rectangle boundsInWindow() {
        return card.getParent() == null ? null : card.getBounds();
    }

    String labelAt(int i) {
        return labelOf(entries.get(i), list);
    }

    String selectedLabel() {
        int i = list.getSelectedIndex();
        return i < 0 ? "" : labelOf(entries.get(i), list);
    }

    private String labelOf(NaruLineCompletion.Candidate c, JList<?> l) {
        String kind = c.kind() == null ? "" : "  [" + c.kind().name().toLowerCase() + "]";
        String display = c.display();
        String color = switch (c.kind() == null ? NaruLineCompletion.Kind.NONE : c.kind()) {
            case DIRECTIVE -> "#7c4dff";
            case SUBCOMMAND -> "#1976d2";
            case DIRECTIVE_ARGUMENT -> "#00897b";
            case FILE -> "#c77800";
            case KEYWORD -> "#388e3c";
            default -> "#3949ab";
        };
        String insert = "<span style='color:" + color + ";font-weight:bold'>" + html(c.insert()) + "</span>";
        if (display == null || display.isEmpty()) {
            // the kind only earns its space when the engine says the set is ambiguous
            return "<html>" + insert + (result.ambiguous() ? " <font color='#777777'>" + kind + "</font>" : "") + "</html>";
        }
        // The engine's own directive descriptions already begin with the command — "/assert — assert
        // a condition" — so prefixing the insert again shows the user the same word twice.
        if (display.startsWith(c.insert())) {
            return "<html>" + insert + " <font color='#777777'>" + html(display.substring(c.insert().length())) + (result.ambiguous() ? kind : "") + "</font></html>";
        }
        return "<html>" + insert + "  <font color='#777777'>—  " + html(display) + (result.ambiguous() ? kind : "") + "</font></html>";
    }

    private static String html(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
