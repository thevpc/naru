package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.registry.NaruLineCompletion;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
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
 * The replacement span comes from {@link NaruLineCompletion.Result}, not from a word boundary the
 * widget guessed: the engine knows that {@code /model use ol} completes inside the third word and
 * that {@code /cat /tmp/fo} completes across slashes, and a host that re-derived those spans would
 * be a second implementation to keep in step.
 */
final class CompletionPopup {

    /** One cell is enough to read a candidate and stay inside the window; more needs scrolling. */
    private static final int VISIBLE_ROWS = 10;
    private static final int CELL_HEIGHT = 20;

    private final JPanel card = new JPanel(new BorderLayout());
    private final DefaultListModel<NaruLineCompletion.Candidate> entries = new DefaultListModel<>();
    private final JList<NaruLineCompletion.Candidate> list = new JList<>(entries);
    private NaruLineCompletion.Result result = NaruLineCompletion.Result.none("", 0, 0);
    /** Set by the composer: a click on a row inserts it, so the mouse and Enter do the same thing. */
    private java.util.function.Consumer<NaruLineCompletion.Candidate> onPick = c -> {
    };

    void setOnPick(java.util.function.Consumer<NaruLineCompletion.Candidate> handler) {
        this.onPick = handler;
    }

    CompletionPopup() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(CELL_HEIGHT);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        list.setCellRenderer((l, value, index, selected, focus) -> {
            javax.swing.JLabel label = new javax.swing.JLabel(labelOf(value, l));
            label.setOpaque(true);
            label.setBackground(selected ? l.getSelectionBackground() : l.getBackground());
            label.setForeground(selected ? l.getSelectionForeground() : l.getForeground());
            label.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
            return label;
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0) {
                    list.setSelectedIndex(i);
                    onPick.accept(entries.get(i));
                }
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        card.add(scroll, BorderLayout.CENTER);
        card.setOpaque(true);
        card.setBackground(list.getBackground());
        card.setBorder(BorderFactory.createLineBorder(new Color(110, 110, 110)));
        // the card is decoration, not a destination: it must never be a focus candidate
        card.setFocusable(false);
        list.setFocusable(false);
    }

    /**
     * Show {@code r} under {@code at} (in {@code owner}'s coordinates). Returns false when there is
     * nothing to show, which the caller treats as "this keystroke did nothing", not as an error.
     */
    boolean show(Component owner, Rectangle at, NaruLineCompletion.Result r) {
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
        entries.clear();
        for (NaruLineCompletion.Candidate c : candidates) {
            entries.addElement(c);
        }
        list.setSelectedIndex(0);
        int rows = Math.min(candidates.size(), VISIBLE_ROWS);
        int height = rows * CELL_HEIGHT + 4;
        // wide enough for the longest candidate: a completion list the user has to scroll sideways
        // to read hides the row they were about to pick, and the rows are single-line by construction
        int widest = list.getPreferredSize().width + 24;
        int width = Math.max(300, Math.min(widest, Math.max(300, pane.getWidth() - 40)));
        card.setSize(width, height);
        card.setPreferredSize(new Dimension(width, height));
        pane.add(card, JLayeredPane.POPUP_LAYER);
        card.setBounds(xInside(pane, owner, at.x, width),
                yInside(pane, owner, at, height), width, height);
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

    void hide() {
        if (card.getParent() != null) {
            card.getParent().remove(card);
        }
        card.setVisible(false);
    }

    boolean isVisible() {
        return card.isShowing() && !entries.isEmpty();
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
        if (c.display() == null || c.display().isEmpty()) {
            // the kind only earns its space when the engine says the set is ambiguous
            return c.insert() + (result.ambiguous() ? kind : "");
        }
        return c.insert() + "  —  " + c.display() + (result.ambiguous() ? kind : "");
    }
}
