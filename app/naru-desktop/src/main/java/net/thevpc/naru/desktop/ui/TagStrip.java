package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.desktop.model.NaruSessionModel;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The selected task's tool tags, always one glance away.
 *
 * <p>Tags are the permission floor of the engine — they decide which tools the model is offered
 * and whether a skill body is injected — so the place a reader looks first, above the composer,
 * has to show them. This strip is that glance: granted tags lit, everything the registry declares
 * present, each chip a switch. It re-reads the task and the registry on every model event, so a
 * {@code /tags} directive run in the composer repaints the same truth the engine sees; it is a
 * mirror of the grant, never a second copy of it.
 *
 * <p>A prompt mode sits above the gate: {@code plan} strips {@code exec} and {@code write} tools
 * even when the task holds those tags. A lit chip whose mode vetoes its tag says so in the tooltip
 * — granted is not the same as visible, and hiding that seam is how the desktop lost trust once.
 */
public final class TagStrip extends JPanel {

    private final NaruSessionModel model;
    private final Runnable showAll;
    private final JPanel chips = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
    private final JLabel count = new JLabel(" ");
    private long taskId = -1;
    /** The name list the chips currently hold; equal to the next read, the chips are only re-lit. */
    private List<String> shown = List.of();
    /** Guards the programmatic side of a chip change so setting state does not write state back. */
    private boolean syncing;

    public TagStrip(NaruSessionModel model, Runnable showAll) {
        super(new BorderLayout(6, 0));
        this.model = model;
        this.showAll = showAll;
        setBorder(BorderFactory.createEmptyBorder(2, 10, 2, 10));
        chips.setOpaque(false);

        JLabel caption = new JLabel("Tags");
        UiFonts.apply(caption, UiFonts.CAPTION, Font.PLAIN);
        caption.setForeground(UiPalette.muted());
        add(caption, BorderLayout.WEST);

        // a wrap row, not a scroll pane: at a one-line strip height a horizontal scrollbar ate
        // the chips' ascenders, and a tag you cannot read is worse than a tag row that wraps
        add(chips, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.setOpaque(false);
        UiFonts.apply(count, UiFonts.CAPTION, Font.PLAIN);
        count.setForeground(UiPalette.muted());
        right.add(count);
        JButton more = new JButton("⊞");
        more.setToolTipText("Open the tool list this task is offered, with per-tool exclusions");
        more.getAccessibleContext().setAccessibleName("Open the tool list");
        more.setFocusable(false);
        more.putClientProperty("JButton.buttonType", "roundRect");
        more.addActionListener(e -> showAll.run());
        JButton row = new JButton("↻");
        row.setToolTipText("Re-read the task's tags and the registry's catalog");
        row.getAccessibleContext().setAccessibleName("Refresh tags");
        row.setFocusable(false);
        row.addActionListener(e -> refresh());
        ActionButtons.glyphSized(more, row);
        right.add(more);
        right.add(row);
        add(right, BorderLayout.EAST);

        model.addListener(this::refresh);
        refresh();
    }

    public void setSelectedTaskId(long id) {
        taskId = id;
        refresh();
    }

    private NaruTask task() {
        NaruSession s = model.session();
        if (s == null || taskId < 0) {
            return null;
        }
        try {
            return s.findTask(taskId).orNull();
        } catch (RuntimeException stopped) {
            return null;
        }
    }

    /**
     * Re-read the chips. The catalog and the granted set are re-read on every model event: a
     * directive or a {@code tag_add} from the model can move either. When the name list is
     * unchanged the existing buttons are only re-lit — a rebuild would reset the strip's scroll
     * every time the model granted a tag mid-turn and yank the reader back to the first chip.
     */
    private void refresh() {
        if (syncing) {
            return;
        }
        NaruTask t = task();
        List<String> names = new ArrayList<>();
        Set<String> granted = Set.of();
        if (t != null) {
            granted = t.findToolTagNames();
            try {
                for (NaruToolTag tag : t.session().registry().availableTags().values()) {
                    names.add(tag.name());
                }
            } catch (RuntimeException ignored) {
                // a registry that cannot answer still shows the task's own grants below
            }
            for (String name : granted) {
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
            names.sort(String::compareToIgnoreCase);
        }
        syncing = true;
        try {
            if (!names.equals(shown)) {
                shown = names;
                chips.removeAll();
                for (String name : names) {
                    TagChip chip = new TagChip(name);
                    chip.addActionListener(e -> {
                        if (syncing) {
                            return;
                        }
                        try {
                            NaruTask current = task();
                            if (current == null) {
                                return;
                            }
                            if (!chip.granted()) {
                                current.addToolTag(name);
                            } else {
                                current.removeToolTag(name);
                            }
                        } catch (RuntimeException ex) {
                            DesktopDialog.showMessage(SwingUtilities.getWindowAncestor(this),
                                    "Tool tags", String.valueOf(ex.getMessage()));
                        }
                        refresh();
                    });
                    chips.add(chip);
                }
            }
            for (java.awt.Component c : chips.getComponents()) {
                TagChip chip = (TagChip) c;
                boolean on = granted.contains(chip.tagName());
                chip.setGranted(on);
                if (t != null) {
                    chip.setToolTipText(tip(t, chip.tagName(), on));
                }
            }
            count.setText(t == null ? "no task"
                    : names.isEmpty() ? "none registered" : granted.size() + "/" + names.size());
            chips.revalidate();
            chips.repaint();
        } finally {
            syncing = false;
        }
    }

    private String tip(NaruTask t, String name, boolean on) {
        String description;
        NaruToolTag def = null;
        try {
            def = t.session().registry().findAvailableTag(name).orNull();
        } catch (RuntimeException ignored) {
        }
        description = def == null ? "declared by a provider that is not installed" : def.description();
        String veto = "";
        if (def != null && on) {
            try {
                if (!t.promptMode().acceptToolTags(Set.of(name))) {
                    veto = " · hidden by prompt mode " + t.promptMode().name();
                }
            } catch (RuntimeException ignored) {
            }
        }
        return name + " — " + description + (on ? " (granted)" : " (not granted)") + veto;
    }

    /**
     * A chip whose grant is carried entirely by its colours: an ungranted tag wears the button's own
     * face, a granted one the accent walked into that face with ink chosen to read on it. The label
     * never changes — no glyph, no bold, no reserved icon — so a chip is exactly as wide granted as
     * not, and toggling cannot reflow the row.
     *
     * <p>Deliberately a {@code JButton} and not a toggle: FlatLaf paints a selected button in the
     * accent itself and ignores the background installed here, which turned a granted chip into
     * accent-on-accent text. The granted flag is the chip's own, and the colours are plain
     * {@code Color}s — FlatLaf treats a {@code ColorUIResource} as its own default and paints that
     * instead.
     */
    private static final class TagChip extends JButton {

        private final String tagName;
        private boolean granted;

        TagChip(String tagName) {
            super(tagName);
            this.tagName = tagName;
            putClientProperty("JButton.buttonType", "roundRect");
            setFocusable(false);
            UiFonts.apply(this, UiFonts.CAPTION, Font.PLAIN);
            setMargin(new Insets(0, 4, 0, 4));
            setBackground(surface());
            setForeground(UiPalette.muted());
        }

        boolean granted() {
            return granted;
        }

        void setGranted(boolean granted) {
            if (this.granted == granted) {
                return;
            }
            this.granted = granted;
            Color fill = granted ? grantFill() : surface();
            setBackground(fill);
            setForeground(granted ? inkOn(fill) : UiPalette.muted());
            repaint();
        }

        String tagName() {
            return tagName;
        }
    }

    /** The button's own face, for a chip that grants nothing. */
    private static Color surface() {
        Color face = UIManager.getColor("Button.background");
        return face != null ? face : UiPalette.muted();
    }

    /** The face, walked into the accent: enough to read as on, and only on granted chips. */
    private static Color grantFill() {
        return UiPalette.mix(surface(), UiPalette.accent(), 0.35f);
    }

    /** Ink that reads on a fill: the look's light text on a dark face, its dark text on a light one. */
    private static Color inkOn(Color fill) {
        double luminance = 0.299 * fill.getRed() + 0.587 * fill.getGreen() + 0.114 * fill.getBlue();
        Color ink = UIManager.getColor(luminance < 140 ? "Button.lightText" : "Button.darkText");
        return ink != null ? ink : (luminance < 140 ? Color.WHITE : Color.BLACK);
    }

    /** FlowLayout that hands back the height its rows actually need, so nothing is clipped. */
    private static final class WrapLayout extends FlowLayout {

        WrapLayout(int align, int hGap, int vGap) {
            super(align, hGap, vGap);
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            return layoutRows(target, target.getWidth(), true);
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            return layoutRows(target, target.getWidth(), false);
        }

        @Override
        public void layoutContainer(Container target) {
            synchronized (target.getTreeLock()) {
                Insets insets = target.getInsets();
                int maxWidth = target.getWidth() - insets.left - insets.right;
                int x = insets.left;
                int y = insets.top;
                int rowHeight = 0;
                List<List<Component>> rows = rows(target, maxWidth, true);
                for (List<Component> row : rows) {
                    rowHeight = 0;
                    for (Component c : row) {
                        Dimension d = c.getPreferredSize();
                        c.setBounds(x, y, d.width, d.height);
                        rowHeight = Math.max(rowHeight, d.height);
                        x += d.width + getHgap();
                    }
                    x = insets.left;
                    y += rowHeight + getVgap();
                }
            }
        }

        /** Groups the visible components into the rows a given width produces. */
        private List<List<Component>> rows(Container target, int maxWidth, boolean preferred) {
            List<List<Component>> rows = new ArrayList<>();
            List<Component> row = new ArrayList<>();
            int x = 0;
            for (Component c : target.getComponents()) {
                if (!c.isVisible()) {
                    continue;
                }
                Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
                if (x > 0 && x + d.width > maxWidth) {
                    rows.add(row);
                    row = new ArrayList<>();
                    x = 0;
                }
                row.add(c);
                x += d.width + getHgap();
            }
            if (!row.isEmpty() || rows.isEmpty()) {
                rows.add(row);
            }
            return rows;
        }

        /** Measures the rows the given width produces; the numbers are also what lays them out. */
        private Dimension layoutRows(Container target, int width, boolean preferred) {
            Insets insets = target.getInsets();
            int maxWidth = Math.max(1, width - insets.left - insets.right);
            int height = 0;
            int totalWidth = 0;
            for (List<Component> row : rows(target, maxWidth, preferred)) {
                int rowHeight = 0;
                int rowWidth = 0;
                for (Component c : row) {
                    Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
                    rowHeight = Math.max(rowHeight, d.height);
                    rowWidth += d.width + getHgap();
                }
                height += rowHeight + getVgap();
                totalWidth = Math.max(totalWidth, rowWidth - getHgap());
            }
            return new Dimension(totalWidth + insets.left + insets.right,
                    height - getVgap() + insets.top + insets.bottom);
        }
    }
}
