package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.desktop.model.NaruSessionModel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The tags a task may use, shown as the switches they are.
 *
 * <p>A tag is what decides which tools the model is offered, and until now the only way to change
 * one was to type {@code /tag add write} at the composer. That gap cost real trust: a reader who
 * had turned every tag off watched the desktop call tools anyway, because the core tools — the
 * private think, the permission read and check — are declared tag-less and essential and are
 * therefore always offered. Saying so on the panel is the fix for the surprise; the switches are
 * the fix for the discovery.
 */
public final class TagsPane extends JPanel {

    private final NaruSessionModel model;
    private final JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    private final JLabel note = new JLabel(" ");
    private long taskId = -1;
    /** Guards the programmatic side of a chip change so setting state does not write state back. */
    private boolean syncing;

    public TagsPane(NaruSessionModel model) {
        super(new BorderLayout(0, 6));
        this.model = model;
        setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        chips.setOpaque(false);

        UiFonts.apply(note, UiFonts.CAPTION, Font.PLAIN);
        note.setForeground(UiPalette.muted());
        note.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(note, BorderLayout.NORTH);
        add(south, BorderLayout.SOUTH);

        JPanel scrollHost = new JPanel();
        scrollHost.setLayout(new BoxLayout(scrollHost, BoxLayout.Y_AXIS));
        scrollHost.setOpaque(false);
        scrollHost.add(chips);
        scrollHost.add(Box.createVerticalGlue());
        // the constructor takes the vertical policy first; the horizontal one second
        add(new javax.swing.JScrollPane(scrollHost, javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);

        javax.swing.JButton row = new javax.swing.JButton("↻");
        row.setToolTipText("Re-read the task's tags and the registry's catalog");
        row.getAccessibleContext().setAccessibleName("Refresh tags");
        ActionButtons.glyphSized(row);
        row.addActionListener(e -> refresh());
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(new JLabel("Tools the model may be offered, by tag"), BorderLayout.WEST);
        JPanel quick = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        quick.setOpaque(false);
        // glyphs, like every other action: the words are the first thing this row loses when the
        // interface size grows and the tooltip already says what each one does
        javax.swing.JButton none = new javax.swing.JButton("⊘");
        none.setToolTipText("Withdraw every tag from this task");
        none.getAccessibleContext().setAccessibleName("Withdraw all tool tags");
        javax.swing.JButton all = new javax.swing.JButton("⊕");
        all.setToolTipText("Grant every tag the registry knows");
        all.getAccessibleContext().setAccessibleName("Grant all tool tags");
        for (javax.swing.AbstractButton b : new javax.swing.AbstractButton[]{none, all}) {
            b.setFocusable(false);
            b.putClientProperty("JButton.buttonType", "roundRect");
            b.addActionListener(e -> setAll(b == all));
            quick.add(b);
        }
        ActionButtons.glyphSized(none, all);
        quick.add(row);
        top.add(quick, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

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

    private void setAll(boolean grant) {
        NaruTask t = task();
        if (t == null) {
            return;
        }
        if (!grant) {
            for (String name : new ArrayList<>(t.findToolTagNames())) {
                t.removeToolTag(name);
            }
        } else {
            for (NaruToolTag tag : available(t)) {
                t.addToolTag(tag.name());
            }
        }
        refresh();
    }

    private List<NaruToolTag> available(NaruTask t) {
        List<NaruToolTag> tags = new ArrayList<>();
        try {
            for (NaruToolTag tag : t.session().registry().availableTags().values()) {
                tags.add(tag);
            }
        } catch (RuntimeException ignored) {
            // a registry that cannot answer leaves the panel with the task's own names below
        }
        tags.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return tags;
    }

    /**
     * Rebuild the chips. The catalog and the granted set are read every time: a directive run in
     * the composer can change either, and a panel that kept its own idea of the grant would be a
     * second truth about what the model may call.
     */
    public void refresh() {
        syncing = true;
        try {
            chips.removeAll();
            NaruTask t = task();
            if (t == null) {
                note.setText("Select a task to read or change its tool tags.");
                chips.revalidate();
                chips.repaint();
                return;
            }
            Set<String> granted = t.findToolTagNames();
            List<String> names = new ArrayList<>();
            for (NaruToolTag tag : available(t)) {
                names.add(tag.name());
            }
            // a granted name the registry has no definition for is still a grant, and still has to
            // be visible — otherwise a tag inherited from a spawn looks like it was never set
            for (String name : granted) {
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
            for (String name : names) {
                JToggleButton chip = new JToggleButton(name, granted.contains(name));
                chip.setFocusable(false);
                chip.putClientProperty("JButton.buttonType", "roundRect");
                chip.setToolTipText((name + " — " + description(t, name)).trim());
                chip.addActionListener(e -> {
                    if (syncing) {
                        return;
                    }
                    try {
                        if (chip.isSelected()) {
                            t.addToolTag(name);
                        } else {
                            t.removeToolTag(name);
                        }
                    } catch (RuntimeException ex) {
                        DesktopDialog.showMessage(javax.swing.SwingUtilities.getWindowAncestor(this),
                                "Tool tags", String.valueOf(ex.getMessage()));
                    }
                    refresh();
                });
                chips.add(chip);
            }
            int offered = granted.size();
            note.setText("<html>" + offered + " of " + names.size() + " tags granted to this task."
                    + " Tag-less core tools (the private think, the permission read and check) are always"
                    + " offered, whatever is selected here.</html>");
            chips.revalidate();
            chips.repaint();
        } finally {
            syncing = false;
        }
    }

    private static String description(NaruTask t, String name) {
        try {
            NaruToolTag tag = t.session().registry().findAvailableTag(name).orNull();
            return tag == null ? "declared by a provider that is not installed" : tag.description();
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
