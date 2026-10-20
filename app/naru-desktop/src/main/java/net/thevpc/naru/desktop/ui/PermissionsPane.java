package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruActorKind;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruPermissionStore;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.access.NaruSubject;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.model.PermissionsModel;
import net.thevpc.naru.desktop.util.PermissionsText;
import net.thevpc.nuts.io.NPath;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * The Permissions tab (plan §5, Phase 2 — moved here from Phase 4 by the owner, because §3.3 makes
 * this the v1 answer to "the approval prompt is out of scope" and §6 promises a jump to it from a
 * denial card).
 * <p>
 * What it edits is the live {@code NaruSession.accessPolicy()}, so a change is in force on the next
 * tool call, not after a restart. That is also the reason the panel shows order as a column and lets
 * you move a rule: the policy is first-match-wins, so a {@code DENY} above a broad {@code ALLOW}
 * narrows it, and the same rule below does nothing at all.
 * <p>
 * Two scopes are visible because the engine keeps two homes: {@code perm:project} rules come from the
 * committed {@code .naru/permissions.tson} and are only written back when you press Save, while
 * {@code perm:session} rules are runtime state that the session resumes with. A file the project
 * commits is not something a panel should rewrite by accident.
 */
public final class PermissionsPane extends JPanel implements PermissionsModel.Listener {

    private static final String[] RESOURCE_TYPES = {
            "*", "file", "dir", "url", "host", "tool", "directive", "model",
            "mcp-tool", "shell", "process", "env", "task", "session", "skill",
    };

    private final NaruSessionModel session;
    private final PermissionsModel model;
    private final DefaultListModel<PermissionsModel.Row> rows = new DefaultListModel<>();
    private final JList<PermissionsModel.Row> list = new JList<>(rows);
    private final JTextArea detail = TextAreas.readOnly();
    private final JLabel note = new JLabel(" ");
    private final JComboBox<String> defaultEffect = new JComboBox<>(new String[]{"allow", "deny"});
    private final JButton remove = new JButton("Remove");
    private final JButton edit = new JButton("Edit…");
    private final JButton up = new JButton("↑");
    private final JButton down = new JButton("↓");
    private final JButton toggle = new JButton("Disable");
    /** Guards the combo: a repaint must not look like the user changing the fallback effect. */
    private boolean loading;

    public PermissionsPane(NaruSessionModel session) {
        super(new BorderLayout());
        this.session = session;
        this.model = session.permissions();
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new RowRenderer());
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        list.addListSelectionListener(e -> showDetail());
        note.setFont(note.getFont().deriveFont(Font.PLAIN, 11f));
        note.setForeground(Color.GRAY);
        note.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bar.add(new JLabel("Default"));
        defaultEffect.setToolTipText("What happens when no rule matches — deny means fail closed");
        defaultEffect.addActionListener(e -> {
            if (!loading && model.isAttached()) {
                model.setDefaultEffect(parseEffect((String) defaultEffect.getSelectedItem()));
            }
        });
        bar.add(defaultEffect);
        bar.add(new JButton(new javax.swing.AbstractAction("New…") {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                createRule();
            }
        }));
        bar.add(edit);
        bar.add(remove);
        bar.add(up);
        bar.add(down);
        bar.add(toggle);
        bar.add(new JButton(new javax.swing.AbstractAction("Reload") {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                reloadFile();
            }
        }));
        bar.add(new JButton(new javax.swing.AbstractAction("Save file") {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                saveFile();
            }
        }));
        edit.addActionListener(e -> editSelected());
        remove.addActionListener(e -> removeSelected());
        up.addActionListener(e -> moveSelected(-1));
        down.addActionListener(e -> moveSelected(1));
        toggle.addActionListener(e -> toggleSelected());

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(list), new JScrollPane(detail));
        split.setResizeWeight(0.55);

        add(bar, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(note, BorderLayout.SOUTH);
        model.addListener(this);
        permissionsChanged();
    }

    // ── model listener ────────────────────────────────────────────────────────

    @Override
    public void permissionsChanged() {
        PermissionsModel.Row wasSelected = list.getSelectedValue();
        String selectedId = wasSelected == null ? null : wasSelected.id();
        rows.clear();
        for (PermissionsModel.Row r : model.rows()) {
            rows.addElement(r);
        }
        if (selectedId != null) {
            selectRule(selectedId);
        }
        loading = true;
        defaultEffect.setSelectedItem(model.defaultEffect().name().toLowerCase());
        loading = false;
        int n = rows.size();
        long blocked = model.rows().stream().mapToLong(PermissionsModel.Row::blocks).sum();
        note.setText(!model.isAttached()
                ? "not attached — the session has no live policy yet (it binds when the session starts)"
                : n == 0
                ? "no rules — every action falls through to the default"
                : n + " rule" + (n == 1 ? "" : "s") + " · " + blocked + " blocked by a rule"
                        + " · " + model.totalDecisions() + " decisions");
        updateButtons();
        if (list.getSelectedIndex() < 0) {
            showDetail();
        }
    }

    /** Select a rule by id, as the denial card's jump asks. Returns false when it no longer exists. */
    public boolean selectRule(String id) {
        if (id == null) {
            return false;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (id.equals(rows.get(i).id())) {
                list.setSelectedIndex(i);
                Rectangle r = list.getCellBounds(i, i);
                if (r != null) {
                    list.ensureIndexIsVisible(i);
                }
                showDetail();
                return true;
            }
        }
        return false;
    }

    /** The id currently selected, or null. The gate harness reads it to prove a jump landed. */
    public String selectedRuleId() {
        PermissionsModel.Row r = list.getSelectedValue();
        return r == null ? null : r.id();
    }

    public void dispose() {
        model.removeListener(this);
    }

    // ── actions ───────────────────────────────────────────────────────────────

    private void createRule() {
        NaruPermissionRule r = RuleDialog.edit(this, null, scopeOf(null));
        if (r == null) {
            return;
        }
        model.putRule(r);
        selectRule(r.id());
    }

    private void editSelected() {
        PermissionsModel.Row row = list.getSelectedValue();
        if (row == null) {
            return;
        }
        NaruPermissionRule updated = RuleDialog.edit(this, row.rule(), row.rule().source());
        if (updated != null) {
            // putRule replaces in place, so the edit keeps the rule's precedence
            model.putRule(updated);
            selectRule(updated.id());
        }
    }

    private void removeSelected() {
        PermissionsModel.Row row = list.getSelectedValue();
        if (row == null) {
            return;
        }
        boolean yes = DesktopDialog.confirm(SwingUtilities.getWindowAncestor(this),
                "Remove permission rule", "Remove " + row.id() + "?\n" + PermissionsText.describe(row.rule())
                        + "\n\nThe change applies to the live policy immediately.","Remove");
        if (yes) {
            model.removeRule(row.id());
        }
    }

    private void moveSelected(int delta) {
        PermissionsModel.Row row = list.getSelectedValue();
        if (row == null) {
            return;
        }
        model.move(row.id(), delta);
        selectRule(row.id());
    }

    private void toggleSelected() {
        PermissionsModel.Row row = list.getSelectedValue();
        if (row == null) {
            return;
        }
        model.setEnabled(row.id(), !row.rule().enabled());
        selectRule(row.id());
    }

    private void reloadFile() {
        if (model.projectStore() == null) {
            note.setText(model.reloadFromProject());
            return;
        }
        NPath file = model.projectFile();
        if (file == null) {
            note.setText("no project directory");
            return;
        }
        note.setText(model.reloadFromProject());
    }

    private void saveFile() {
        if (model.projectStore() == null) {
            note.setText(model.saveToProject());
            return;
        }
        NPath file = model.projectFile();
        if (file == null) {
            note.setText("no project directory");
            return;
        }
        int projectRules = 0;
        for (PermissionsModel.Row r : model.rows()) {
            if (PermissionsModel.SOURCE_PROJECT.equals(r.rule().source())) {
                projectRules++;
            }
        }
        boolean yes = DesktopDialog.confirm(SwingUtilities.getWindowAncestor(this),
                "Save project permissions", "Write " + projectRules + " project rule(s) to\n" + file
                        + "\n\nThis replaces a file the project commits, so it is versioned with the"
                        + " code. Session rules are never written here.","Save");
        if (yes) {
            note.setText(model.saveToProject());
        }
    }

    private void updateButtons() {
        PermissionsModel.Row row = list.getSelectedValue();
        boolean has = row != null;
        edit.setEnabled(has);
        remove.setEnabled(has);
        up.setEnabled(has && list.getSelectedIndex() > 0);
        down.setEnabled(has && list.getSelectedIndex() >= 0 && list.getSelectedIndex() < rows.size() - 1);
        toggle.setEnabled(has);
        toggle.setText(has && row.rule().enabled() ? "Disable" : "Enable");
        defaultEffect.setEnabled(model.isAttached());
    }

    private void showDetail() {
        PermissionsModel.Row r = list.getSelectedValue();
        if (r == null) {
            detail.setText(rows.isEmpty()
                    ? "No rules. The policy is empty, so every action falls through to the default effect."
                    : "Select a rule to see its fields and how often it fired.");
            detail.setCaretPosition(0);
            updateButtons();
            return;
        }
        NaruPermissionRule x = r.rule();
        StringBuilder sb = new StringBuilder();
        sb.append("id          ").append(x.id()).append('\n');
        sb.append("effect      ").append(x.effect()).append('\n');
        sb.append("position    ").append(list.getSelectedIndex() + 1).append(" of ").append(rows.size())
                .append("   (first match wins)\n");
        sb.append("enabled     ").append(x.enabled()).append('\n');
        sb.append("actor kind  ").append(x.actorKind() == null ? "(any)" : x.actorKind()).append('\n');
        sb.append("type        ").append(x.resourceType() == null ? "*" : x.resourceType().id()).append('\n');
        sb.append("patterns    ").append(x.patterns()).append('\n');
        sb.append("actions     ").append(x.actions()).append('\n');
        sb.append("subjects    ").append(x.subjects()).append('\n');
        sb.append("source      ").append(x.source()).append('\n');
        sb.append("created     ").append(x.createdAt()).append('\n');
        sb.append("fired       ").append(r.hits()).append(" time(s) · allowed ").append(r.grants())
                .append(" · blocked ").append(r.blocks()).append('\n');
        if (x.description() != null && !x.description().isBlank()) {
            sb.append("\n").append(x.description());
        }
        if (PermissionsModel.SOURCE_PROJECT.equals(x.source())) {
            sb.append("\n\nfrom the project file — editing changes the live policy now, and "
                    + NaruPermissionStore.PROJECT_FILE + " only when you press Save file");
        }
        detail.setText(sb.toString());
        detail.setCaretPosition(0);
        updateButtons();
    }

    private static NaruAccessEffect parseEffect(String s) {
        return s != null && s.trim().equalsIgnoreCase("deny")
                ? NaruAccessEffect.DENY : NaruAccessEffect.ALLOW;
    }

    private static String scopeOf(String source) {
        return PermissionsModel.SOURCE_PROJECT.equals(source)
                ? PermissionsModel.SOURCE_PROJECT : PermissionsModel.SOURCE_SESSION;
    }

    private static final class RowRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                      boolean selected, boolean focus) {
            PermissionsModel.Row r = (PermissionsModel.Row) value;
            JLabel label = (JLabel) super.getListCellRendererComponent(l,
                    (index + 1) + ". " + PermissionsText.describe(r.rule()), index, selected, focus);
            label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            label.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
            if (!selected) {
                Color base = r.rule().effect() == NaruAccessEffect.DENY
                        ? new Color(190, 80, 70) : new Color(60, 140, 90);
                label.setForeground(dark(l) ? lighten(base) : base);
                if (!r.rule().enabled()) {
                    label.setForeground(dark(l) ? Color.LIGHT_GRAY : Color.GRAY);
                }
            }
            return label;
        }

        private boolean dark(JList<?> l) {
            Color c = l.getBackground();
            return c.getRed() + c.getGreen() + c.getBlue() < 384;
        }

        private Color lighten(Color c) {
            return new Color(Math.min(255, c.getRed() + 90), Math.min(255, c.getGreen() + 90),
                    Math.min(255, c.getBlue() + 90));
        }
    }

    /**
     * The rule form. Fields are the engine's own vocabulary and are parsed with the engine's own
     * helpers ({@code NaruAction.parse}, {@code NaruSubject.parse}, {@code NaruResourceType.of}), so
     * what you can type here is exactly what {@code /permission add} accepts — the CLI and this panel
     * cannot drift apart.
     */
    static final class RuleDialog {

        static NaruPermissionRule edit(Component parent, NaruPermissionRule base, String source) {
            boolean isNew = base == null;
            NaruPermissionRule r = base == null ? new NaruPermissionRule() : base;

            JTextFieldHolder h = new JTextFieldHolder(r);
            boolean accepted=DesktopDialog.confirmComponent(SwingUtilities.getWindowAncestor(parent),
                    isNew ? "New permission rule" : "Edit " + r.id(),h.panel());
            return accepted ? h.build() : null;
        }

        /** Holds the widgets and turns them back into a rule. */
        private static final class JTextFieldHolder {
            private final JTextArea id = new JTextArea(1, 22);
            private final JComboBox<String> effect = new JComboBox<>(new String[]{"deny", "allow"});
            private final JComboBox<String> kind = new JComboBox<>(
                    new String[]{"(any)", "USER", "AGENT", "SYSTEM"});
            private final JComboBox<String> type = new JComboBox<>(RESOURCE_TYPES);
            private final JTextArea patterns = new JTextArea(2, 22);
            private final JTextArea actions = new JTextArea(1, 22);
            private final JTextArea subjects = new JTextArea(1, 22);
            private final JTextArea description = new JTextArea(2, 22);
            private final JCheckBox enabled = new JCheckBox("enabled", true);
            private final JComboBox<String> scope = new JComboBox<>(
                    new String[]{"this session only", "project file (committed)"});

            JTextFieldHolder(NaruPermissionRule r) {
                id.setText(r.id() == null ? "" : r.id());
                effect.setSelectedItem(r.effect() == NaruAccessEffect.DENY ? "deny" : "allow");
                kind.setSelectedItem(r.actorKind() == null ? "(any)" : r.actorKind().name());
                type.setSelectedItem(r.resourceType() == null ? "*" : r.resourceType().id());
                type.setEditable(true);
                patterns.setText(String.join(",", r.patterns()));
                List<String> act = new ArrayList<>();
                for (NaruAction a : r.actions()) {
                    act.add(a.id());
                }
                actions.setText(String.join(",", act));
                List<String> sub = new ArrayList<>();
                for (NaruSubject s : r.subjects()) {
                    sub.add(s.type().name().toLowerCase() + ":" + s.id());
                }
                subjects.setText(String.join(",", sub));
                description.setText(r.description() == null ? "" : r.description());
                enabled.setSelected(r.enabled());
                scope.setSelectedItem(PermissionsModel.SOURCE_PROJECT.equals(r.source())
                        ? scope.getItemAt(1) : scope.getItemAt(0));
            }

            JPanel panel() {
                JPanel p = new JPanel(new BorderLayout());
                JPanel grid = new JPanel(new java.awt.GridBagLayout());
                java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
                c.anchor = java.awt.GridBagConstraints.WEST;
                c.insets = new java.awt.Insets(2, 4, 2, 4);
                int y = 0;
                add(grid, c, y++, "id", id, "blank lets the engine assign one; it is how you address the rule later");
                add(grid, c, y++, "effect", effect, "allow or deny when this rule matches");
                add(grid, c, y++, "actor kind", kind, "who the rule applies to: a human, an agent, the runtime");
                add(grid, c, y++, "type", type, "file, dir, url, tool, directive, shell, model, * …");
                add(grid, c, y++, "patterns", patterns, "comma-separated globs, project-relative (src/**), blank = any");
                add(grid, c, y++, "actions", actions, "r,w,x,read,write,execute,call,delete,… blank = any");
                add(grid, c, y++, "subjects", subjects, "user:alice,group:devs,agent,* — blank = anyone");
                add(grid, c, y++, "description", description, "shown in the CLI and the transcript card");
                JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
                bottom.add(enabled);
                bottom.add(scope);
                p.add(grid, BorderLayout.NORTH);
                p.add(bottom, BorderLayout.SOUTH);
                return p;
            }

            private static void add(JPanel grid, java.awt.GridBagConstraints c, int y,
                                    String label, JComponent field, String tip) {
                JLabel l = new JLabel(label);
                l.setFont(l.getFont().deriveFont(Font.PLAIN));
                field.setToolTipText(tip);
                c.gridx = 0;
                c.gridy = y;
                grid.add(l, c);
                c.gridx = 1;
                grid.add(field instanceof JCheckBox ? field : new JScrollPane(field,
                        JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                        JScrollPane.VERTICAL_SCROLLBAR_NEVER), c);
            }

            NaruPermissionRule build() {
                NaruPermissionRule r = new NaruPermissionRule();
                if (!id.getText().isBlank()) {
                    r = r.id(id.getText().trim());
                }
                r = r.effect("deny".equals(effect.getSelectedItem())
                        ? NaruAccessEffect.DENY : NaruAccessEffect.ALLOW);
                String k = (String) kind.getSelectedItem();
                r = r.actorKind(k == null || k.equals("(any)") ? null : NaruActorKind.valueOf(k));
                String t = (String) type.getEditor().getItem();
                r = r.resourceType(NaruResourceType.of(t == null || t.isBlank() ? "*" : t.trim()));
                r = r.patterns(split(patterns.getText()));
                List<NaruAction> acts = new ArrayList<>();
                for (String s : split(actions.getText())) {
                    acts.add(NaruAction.parse(s));
                }
                r = r.actions(acts);
                List<NaruSubject> subs = new ArrayList<>();
                for (String s : split(subjects.getText())) {
                    subs.add(NaruSubject.parse(s));
                }
                r = r.subjects(subs);
                r = r.description(description.getText().isBlank() ? null : description.getText().trim());
                r = r.enabled(enabled.isSelected());
                r = r.source(scope.getSelectedIndex() == 1
                        ? PermissionsModel.SOURCE_PROJECT : PermissionsModel.SOURCE_SESSION);
                return r;
            }

            private static List<String> split(String s) {
                List<String> out = new ArrayList<>();
                for (String p : s.split(",")) {
                    String t = p.trim();
                    if (!t.isEmpty()) {
                        out.add(t);
                    }
                }
                return out;
            }
        }
    }

    /** Unused: kept so the dialog can be opened without a mouse for the gate harness. */
    public void focusFirstRule() {
        SwingUtilities.invokeLater(() -> {
            if (!rows.isEmpty()) {
                list.setSelectedIndex(0);
                list.requestFocusInWindow();
            }
        });
    }
}
