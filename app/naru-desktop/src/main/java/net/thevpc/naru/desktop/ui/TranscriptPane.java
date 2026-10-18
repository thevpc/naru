package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.desktop.model.BlockStyle;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.model.TranscriptBlock;
import net.thevpc.naru.desktop.model.TranscriptModel;
import net.thevpc.naru.desktop.render.MarkdownRenderer;
import net.thevpc.naru.desktop.util.PermissionsText;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * The transcript: one row per block, in a {@code JList} so only visible cells are laid out.
 * <p>
 * Row rendering follows the Phase 0 budget (plan §4.3): a live block is a plain-text tail on one
 * line, a closed block is the cached HTML that a background thread produced. Nothing expensive
 * happens in {@link #getListCellRendererComponent} beyond reading a field that is already there,
 * and a block whose HTML has not arrived yet degrades to plain text rather than blocking.
 * <p>
 * Scroll follows output only while the user is at the bottom; scrolling up to read history pins
 * the viewport until they return, which is the difference between a transcript and a screensaver.
 */
public final class TranscriptPane extends JPanel implements TranscriptModel.Listener {

    /** Bottom tolerance for "the user is watching the tail", in pixels of scrollbar. */
    private static final int STICK_MARGIN = 48;

    private final TranscriptModel model;
    private final DefaultListModel<TranscriptBlock> rows = new DefaultListModel<>();
    private final JList<TranscriptBlock> list = new JList<>(rows);
    private final JScrollPane scroll = new JScrollPane(list);
    private final JLabel counter = new JLabel(" ");
    private final CellRenderer renderer = new CellRenderer();
    private MarkdownRenderer markdown;
    private int wrapWidth = 900;
    private long taskFilter = Long.MIN_VALUE;
    /** Set by the window: jump from a denial card to the rule that caused it (plan §6). */
    private java.util.function.Consumer<String> onJumpToPermissions;
    private final javax.swing.JPopupMenu denialMenu = new javax.swing.JPopupMenu();

    public TranscriptPane(NaruSessionModel session) {
        super(new BorderLayout());
        this.model = session.transcript();
        this.markdown = new MarkdownRenderer(this::htmlArrived);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(renderer);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        list.setOpaque(true);
        syncRows();

        JPanel bar = new JPanel(new BorderLayout());
        javax.swing.JToggleButton verbose = new javax.swing.JToggleButton("Verbose");
        verbose.setToolTipText("Show TRACE / DEBUG / SCHEDULER / PROGRESS / RAW blocks too");
        verbose.setSelected(model.isVerbose());
        verbose.addActionListener(e -> {
            model.setVerbose(verbose.isSelected());
            syncRows();
        });
        bar.add(verbose, BorderLayout.WEST);
        bar.add(counter, BorderLayout.EAST);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        add(bar, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        // A JList is as wide as its widest cell, and an untruncated tool result is a very wide cell.
        // That preferred width is also the split pane's floor for this side, so the divider ends up
        // past the right edge and the tab panel — Tools, Permissions, the §6 jump — is laid out
        // outside the window. The horizontal bar already handles the overflow, so bound the minimum
        // rather than the preferred size and the divider can be placed where the user put it.
        scroll.setMinimumSize(new java.awt.Dimension(240, 120));

        // clicking a closed row collapses or expands it
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int idx = list.locationToIndex(e.getPoint());
                if (idx < 0) {
                    return;
                }
                TranscriptBlock b = rows.get(idx);
                if (b.isClosed()) {
                    b.setCollapsed(!b.isCollapsed());
                    Rectangle r = list.getCellBounds(idx, idx);
                    list.invalidate();
                    list.repaint(r);
                    if (b.isClosed() && !b.htmlIsCurrent()) {
                        markdown.request(b);
                    }
                }
            }

            /**
             * The §6 jump. A denial card is where a user first learns something was refused, so the
             * way out of it has to be here rather than only in the Tools tab.
             */
            @Override
            public void mousePressed(MouseEvent e) {
                maybeMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeMenu(e);
            }

            private void maybeMenu(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int idx = list.locationToIndex(e.getPoint());
                TranscriptBlock b = idx < 0 ? null : rows.get(idx);
                if (b == null || !b.isToolCard() || !PermissionsText.isDenial(b.text())) {
                    return;
                }
                list.setSelectedIndex(idx);
                String id = PermissionsText.matchedRuleId(b.text());
                javax.swing.JMenuItem item = new javax.swing.JMenuItem(id == null
                        ? "Open Permissions (denied by the default effect)"
                        : "Open rule " + id + " in Permissions");
                item.setEnabled(onJumpToPermissions != null);
                item.addActionListener(x -> {
                    if (onJumpToPermissions != null) {
                        onJumpToPermissions.accept(id);
                    }
                });
                denialMenu.removeAll();
                denialMenu.add(item);
                denialMenu.show(list, e.getX(), e.getY());
            }
        });
        // rendering is viewport-driven: only what can be seen is worth parsing
        scroll.getVerticalScrollBar().addAdjustmentListener(new AdjustmentListener() {
            @Override
            public void adjustmentValueChanged(AdjustmentEvent e) {
                requestVisibleRows();
            }
        });
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                wrapWidth = Math.max(320, scroll.getViewport().getWidth() - 44);
                requestVisibleRows();
            }
        });
        model.addListener(this);
    }

    /** Rows that just became closed need HTML; ask for it from here rather than in the renderer. */
    private void requestVisibleRows() {
        int first = list.getFirstVisibleIndex();
        int last = list.getLastVisibleIndex();
        if (first < 0) {
            return;
        }
        markdown.requestVisible(model, first, last);
    }

    private void htmlArrived(List<TranscriptBlock> changed) {
        for (TranscriptBlock b : changed) {
            int idx = rows.indexOf(b);
            if (idx >= 0) {
                list.invalidate();
                Rectangle r = list.getCellBounds(idx, idx);
                if (r != null) {
                    list.repaint(r);
                }
            }
        }
    }

    @Override
    public void transcriptChanged(boolean structural) {
        if (structural) {
            syncRows();
        } else {
            int last = rows.size() - 1;
            if (last >= 0) {
                Rectangle r = list.getCellBounds(last, last);
                list.repaint(r == null ? list.getBounds() : r);
            }
            // a long stream grows one block without ever changing the row set, so the counter
            // has to be refreshed here too; it read 171 842 chars while the model held 338 482
            // at the end of the Phase 1 gate run for exactly this reason
            updateCounter();
        }
        // the tail just moved; ask for its HTML if it closed
        requestVisibleRows();
    }

    private void syncRows() {
        boolean stick = isNearBottom();
        List<TranscriptBlock> visible = model.visible().stream()
                .filter(b -> taskFilter == Long.MIN_VALUE || b.taskId() == taskFilter).toList();
        // cheap diff: the transcript only ever grows at the end, or is fully reset
        int common = 0;
        while (common < visible.size() && common < rows.size()
                && rows.get(common) == visible.get(common)) {
            common++;
        }
        while (rows.size() > common) {
            rows.remove(rows.size() - 1);
        }
        for (int i = common; i < visible.size(); i++) {
            rows.addElement(visible.get(i));
        }
        updateCounter();
        list.revalidate();
        if (stick && !rows.isEmpty()) {
            list.ensureIndexIsVisible(rows.size() - 1);
        }
    }

    /** The header line: how much of the transcript is there, and how much of it was dropped. */
    private void updateCounter() {
        int n = rows.size();
        int chars = 0;
        for (int i = 0; i < rows.size(); i++) chars += rows.get(i).length();
        counter.setText(n + " block" + (n == 1 ? "" : "s")
                + (model.trimmedBlocks() > 0 ? " · " + model.trimmedBlocks() + " trimmed" : "")
                + " · " + chars + " chars");
    }

    private boolean isNearBottom() {
        JScrollBar bar = scroll.getVerticalScrollBar();
        if (bar == null || bar.getMaximum() == 0) {
            return true;
        }
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - STICK_MARGIN;
    }

    public void dispose() {
        model.removeListener(this);
        if (markdown != null) {
            markdown.shutdown();
            markdown = null;
        }
    }

    /** Install the window's jump so a denial card's menu can reach the Permissions tab. */
    public void setJumpToPermissions(java.util.function.Consumer<String> jump) {
        this.onJumpToPermissions = jump;
    }

    public void setTaskFilter(long taskId) {
        taskFilter = taskId;
        syncRows();
    }

    /**
     * Cells are a shared {@code JLabel}: the expensive HTML is looked up on the block, never
     * rebuilt here, and the only work per paint is choosing between the plain tail and the cache.
     */
    private final class CellRenderer extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                      boolean selected, boolean focus) {
            TranscriptBlock b = (TranscriptBlock) value;
            BlockStyle style = BlockStyle.forBlock(b);
            JLabel label = (JLabel) super.getListCellRendererComponent(l, "", index, selected, focus);
            label.setFont(new Font(style.monospace() ? Font.MONOSPACED : Font.SANS_SERIF,
                    Font.PLAIN, 13));
            label.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 3, 0, 0, fade(style.gutter(), selected ? 1.0f : 0.75f)),
                    BorderFactory.createEmptyBorder(3, 6, 3, 6)));
            label.setForeground(selected ? l.getSelectionForeground() : textColour(style, l));

            if (!b.isClosed()) {
                label.setText(tail(b.text(), 400));
                label.setToolTipText("streaming…");
                return label;
            }
            if (b.isCollapsed()) {
                String elapsed = b.elapsedLabel();
                label.setText("▸ " + style.label()
                        + (elapsed != null ? " · " + elapsed : "")
                        + " · " + b.length() + " chars (click to expand)");
                return label;
            }
            String html = b.html();
            if (html == null) {
                // not rendered yet — plain text now, HTML when the worker gets to it
                label.setText(tail(b.text(), 600));
                label.setToolTipText(style.label());
                return label;
            }
            label.setText("<html><body style='width:" + wrapWidth + "px;margin:0;padding:0'>"
                    + html + "</body></html>");
            label.setToolTipText(null);
            return label;
        }

        private String tail(String s, int max) {
            int cut = s.lastIndexOf('\n', Math.max(0, s.length() - 2));
            String line = cut >= 0 ? s.substring(cut + 1) : s;
            line = line.replace("\r", "");
            if (line.length() > max) {
                line = "…" + line.substring(line.length() - max);
            }
            return line.isEmpty() ? " " : line;
        }

        private Color textColour(BlockStyle style, JList<?> l) {
            Color c = style.foreground();
            boolean dark = l.getBackground().getRed() + l.getBackground().getGreen()
                    + l.getBackground().getBlue() < 384;
            return dark ? lighten(c) : c;
        }

        private Color fade(Color c, float factor) {
            return new Color(Math.min(255, (int) (c.getRed() * factor)),
                    Math.min(255, (int) (c.getGreen() * factor)),
                    Math.min(255, (int) (c.getBlue() * factor)));
        }

        private Color lighten(Color c) {
            return new Color(Math.min(255, c.getRed() + 110),
                    Math.min(255, c.getGreen() + 110),
                    Math.min(255, c.getBlue() + 110));
        }
    }

    /** Scroll to the newest row, e.g. when the user sends input. */
    public void followTail() {
        SwingUtilities.invokeLater(() -> {
            if (!rows.isEmpty()) {
                list.ensureIndexIsVisible(rows.size() - 1);
            }
        });
    }
}
