package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruTaskConfig;
import net.thevpc.naru.api.model.NaruModelRegistration;
import net.thevpc.naru.api.model.NaruRegistrationFields;
import net.thevpc.naru.api.model.NaruRegistrationOrigin;
import net.thevpc.naru.api.model.NaruRegistrationScope;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NLiteral;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The settings surface for registered model providers, and for what the picker shows.
 *
 * <p>Two axes, deliberately in two halves of this one pane. The listing and the form below it write
 * {@code registrations.tson} — the endpoint definitions, in the project's files or the user's own,
 * through the same rules the {@code /model} command uses. The hidden list at the bottom is the
 * <b>view</b> axis: it changes what this reader sees in the model picker on this machine, and writes
 * no project file at all. Keeping both here, rather than folding hiding into the policy keys, is the
 * point: a provider you never use is not a provider your teammates may not run.
 *
 * <p>The form is the vocabulary drawn out, not a key/value dialog. {@link
 * NaruRegistrationFields#fields()} decides the rows, their order and their headings, so a parameter
 * the store learns tomorrow appears here as a named field without anything in this file being told
 * about it. The widget follows {@code Kind} — a flag is picked, a list is comma-separated, a duration
 * is typed — the candidates come from {@code suggestedValues()} and from what the live session offers
 * for {@code provider} and {@code protocol}, and the badge beside each row names the file that owns
 * <b>that</b> parameter, which is the one thing a merged endpoint cannot show any other way. A blank
 * field means the parameter is unset, and clearing one is a null edit, so the same engine rules that
 * refuse to strip a live endpoint's provider refuse it here too.
 *
 * <p>Parameters the vocabulary does not declare get no fake row: they keep their own table and are
 * edited one cell at a time, because their names and shapes belong to whoever registered them.
 */
public final class ModelProvidersPane extends JPanel {

    /** Prefix of every field input's component name, so a test and the a11y tree can point at one. */
    static final String FIELD_NAME = "naru.registration.field.";

    private final ModelRegistrationGateway gateway;
    /** The project whose view settings this pane reads and writes; null when none is open. */
    private final Supplier<NPath> projectDirectory;

    private final JLabel heading = new JLabel("Models & providers");
    private final JLabel copy = new JLabel("Every registration parameter is a field of its own below; "
            + "the scope chosen next to it is the file a write goes to.");
    private final RegistrationModel model = new RegistrationModel();
    private final JTable table = new JTable(model);
    private final JTextField idField = new JTextField();
    private final JRadioButton projectChoice = new JRadioButton("Project", true);
    private final JRadioButton userChoice = new JRadioButton("User");
    private final JLabel detail = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private final List<Row> rows = new ArrayList<>();
    private final JPanel form = new JPanel(new GridBagLayout());
    private final List<FieldRow> fieldRows = new ArrayList<>();
    /** The same rows by canonical name: what {@link #fieldInput} and {@link #fieldBadge} read. */
    private final Map<String, FieldRow> rowsByKey = new LinkedHashMap<>();
    private final FieldRow providerRow;
    private final FieldRow protocolRow;
    private final ExtrasModel extrasModel = new ExtrasModel();
    private final JTable extrasTable = new JTable(extrasModel);
    private final JTextField hideField = new JTextField();
    private final DefaultListModel<String> hiddenRows = new DefaultListModel<>();
    private final JList<String> hiddenList = new JList<>(hiddenRows);

    /** The registration the form currently shows, or null while nothing is bound to it. */
    private String editingId;
    /** Set while the pane fills the widgets itself, so filling is never read as editing. */
    private boolean loading;
    /** Set while the pane moves the table selection, so a refresh is never read as a switch. */
    private boolean syncingSelection;

    private JButton saveButton;
    private JButton toggleButton;
    private JButton deleteButton;
    private JButton reloadButton;
    private JButton extraRemoveButton;
    private JButton hideAddButton;
    private JButton hideRemoveButton;

    /** One registration as the table shows it: its id, its merged fields, and who owns them. */
    private static final class Row {
        final String id;
        final NaruModelRegistration registration;
        final NaruRegistrationOrigin origin;

        Row(String id, NaruModelRegistration registration, NaruRegistrationOrigin origin) {
            this.id = id;
            this.registration = registration;
            this.origin = origin;
        }

        String where() {
            if (origin == null) {
                return "?";
            }
            boolean project = origin.isDeclaredIn(NaruRegistrationScope.PROJECT);
            boolean user = origin.isDeclaredIn(NaruRegistrationScope.USER);
            if (project && user) {
                return "project over user";
            }
            return project ? "project" : "user";
        }
    }

    public ModelProvidersPane(Supplier<NPath> projectDirectory, ModelRegistrationGateway gateway) {
        super(new BorderLayout(0, 12));
        this.projectDirectory = projectDirectory;
        this.gateway = gateway;
        UiFonts.apply(heading, UiFonts.SUBTITLE, Font.BOLD);
        UiFonts.apply(copy, UiFonts.CAPTION, Font.PLAIN);
        UiFonts.apply(detail, UiFonts.CAPTION, Font.PLAIN);
        UiFonts.apply(status, UiFonts.CAPTION, Font.PLAIN);

        JPanel texts = new JPanel(new BorderLayout(0, 6));
        texts.add(heading, BorderLayout.NORTH);
        texts.add(copy, BorderLayout.SOUTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.setName("naru.registration.list");
        UiFonts.apply(table, UiFonts.BODY, Font.PLAIN);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                rowSelected();
            }
        });
        JScrollPane scroll = new JScrollPane(table);
        UiFonts.apply(scroll.getViewport(), UiFonts.BODY, Font.PLAIN);
        scroll.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        // the form is the body of this pane now, so the listing that only picks which endpoint to
        // edit gets a fixed band at the top instead of half the window
        scroll.setPreferredSize(new Dimension(10, 140));

        providerRow = new FieldRow(NaruRegistrationFields.PROVIDER);
        protocolRow = new FieldRow(NaruRegistrationFields.PROTOCOL);
        buildForm();

        JPanel editor = new JPanel(new BorderLayout(0, 8));
        editor.add(buildHead(), BorderLayout.NORTH);
        JScrollPane formScroll = new JScrollPane(form);
        formScroll.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        UiFonts.apply(formScroll.getViewport(), UiFonts.BODY, Font.PLAIN);
        editor.add(formScroll, BorderLayout.CENTER);
        editor.add(buildExtras(), BorderLayout.SOUTH);

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.add(texts, BorderLayout.NORTH);
        top.add(scroll, BorderLayout.SOUTH);
        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.add(top, BorderLayout.NORTH);
        center.add(editor, BorderLayout.CENTER);

        add(center, BorderLayout.CENTER);
        add(buildHiddenPanel(), BorderLayout.SOUTH);
        reload();
    }

    // ── the form ──────────────────────────────────────────────────────────────

    /** One declared parameter: its row label, its input, and the badge naming the file that owns it. */
    private final class FieldRow {
        final NaruRegistrationFields.Field<?> field;
        final JLabel label;
        final JComponent widget;
        final JLabel badge = new JLabel(" ");
        /** What the store holds, in the widget's own spelling: what "changed" is measured against. */
        private String storedText = "";

        FieldRow(NaruRegistrationFields.Field<?> f) {
            field = f;
            label = new JLabel(f.key());
            UiFonts.apply(label, UiFonts.CAPTION, Font.PLAIN);
            UiFonts.apply(badge, UiFonts.CAPTION, Font.PLAIN);
            widget = newWidget(f);
            UiFonts.apply(widget, UiFonts.BODY, Font.PLAIN);
            widget.setName(FIELD_NAME + f.key());
            String tip = f.key() + ": " + wordsFor(f) + ".";
            if (!f.aliases().isEmpty()) {
                tip += " Reads " + String.join(", ", f.aliases()) + ", stored as " + f.key() + ".";
            }
            if (f.isSecret()) {
                tip += " A literal credential is kept in the private file and is never shown back;"
                        + " typing replaces it, blanking clears it.";
            }
            if (f.isIdentity()) {
                tip += " Identity: a layer that rewrites this replaces the registration"
                        + " rather than overlaying it.";
            }
            label.setToolTipText(tip);
            widget.setToolTipText(tip);
            bindChanges(widget);
        }

        String text() {
            return widgetText(widget);
        }

        void load(NaruModelRegistration masked, NaruRegistrationOrigin origin) {
            storedText = masked == null ? "" : displayOf(field, masked);
            setWidgetText(widget, storedText);
            badge.setText(ownerOf(field.key(), origin, storedText));
        }

        boolean isDirty() {
            return !text().equals(storedText);
        }

        /** An untouched row says where its value lives; a changed one says where the write goes. */
        void showState() {
            if (isDirty()) {
                badge.setText("-> " + currentScope().word());
            } else {
                badge.setText(ownerOf(field.key(), currentOrigin(), storedText));
            }
        }

        /**
         * Merge this field into {@code edits} when it changed. A blank that used to hold a value is a
         * null edit — the store's own way of removing a key — and a value that cannot be read as the
         * declared kind is refused at the widget rather than filed as a string that reads back unset.
         */
        String apply(Map<String, NElement> edits) {
            if (!isDirty()) {
                return null;
            }
            String v = text();
            if (v.isEmpty()) {
                edits.put(field.key(), null);
                return null;
            }
            NElement parsed;
            try {
                parsed = parseAs(field, v);
            } catch (RuntimeException e) {
                return field.key() + " could not be read: " + e.getMessage();
            }
            if (parsed == null) {
                return field.key() + " wants " + wordsFor(field) + ", not '" + v + "'";
            }
            edits.put(field.key(), parsed);
            return null;
        }

        @SuppressWarnings("unchecked")
        void candidates(List<String> values) {
            if (widget instanceof JComboBox<?> box) {
                setCandidates((JComboBox<String>) box, values);
            }
        }

        private JComponent newWidget(NaruRegistrationFields.Field<?> f) {
            if (f == NaruRegistrationFields.PROVIDER || f == NaruRegistrationFields.PROTOCOL
                    || f.kind() == NaruRegistrationFields.Kind.BOOL) {
                JComboBox<String> box = new JComboBox<>();
                box.setEditable(true);
                JTextField editor = (JTextField) box.getEditor().getEditorComponent();
                editor.setColumns(24);
                editor.putClientProperty("JTextField.placeholderText", f.key());
                return box;
            }
            JTextField tf = new JTextField();
            tf.setColumns(24);
            tf.putClientProperty("JTextField.placeholderText", f.key());
            return tf;
        }

        private void bindChanges(JComponent w) {
            JTextField editor = w instanceof JComboBox<?> box
                    ? (JTextField) box.getEditor().getEditorComponent()
                    : (JTextField) w;
            editor.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { edited(); }
                @Override public void removeUpdate(DocumentEvent e) { edited(); }
                @Override public void changedUpdate(DocumentEvent e) { edited(); }
            });
            if (w instanceof JComboBox<?> box) {
                box.addActionListener(e -> edited());
            }
        }

        private void edited() {
            if (loading) {
                return;
            }
            if (field == NaruRegistrationFields.PROVIDER) {
                // which wires a provider speaks is not something this form can guess, and choosing
                // the provider is exactly what makes the protocol list worth opening
                protocolRow.candidates(sorted(gateway.protocols(text())));
            }
            refreshStates();
        }
    }

    private void buildForm() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 0, 3, 10);
        c.anchor = GridBagConstraints.WEST;
        NaruRegistrationFields.Group group = null;
        int y = 0;
        for (NaruRegistrationFields.Field<?> f : NaruRegistrationFields.fields()) {
            if (f.group() != group) {
                group = f.group();
                JLabel header = new JLabel(group.label());
                UiFonts.apply(header, UiFonts.CAPTION, Font.BOLD);
                c.gridx = 0;
                c.gridy = y;
                c.gridwidth = 3;
                c.weightx = 0;
                c.fill = GridBagConstraints.NONE;
                form.add(header, c);
                c.gridwidth = 1;
                y++;
            }
            FieldRow row = f == NaruRegistrationFields.PROVIDER ? providerRow
                    : f == NaruRegistrationFields.PROTOCOL ? protocolRow : new FieldRow(f);
            fieldRows.add(row);
            rowsByKey.put(f.key(), row);
            c.gridy = y;
            c.gridx = 0;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            form.add(row.label, c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            form.add(row.widget, c);
            c.gridx = 2;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            form.add(row.badge, c);
            y++;
        }
        // keeps the blocks at the top of a tall page instead of letting the grid space them out
        c.gridx = 0;
        c.gridy = y;
        c.gridwidth = 3;
        c.weightx = 1;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        form.add(Box.createGlue(), c);
    }

    private JPanel buildHead() {
        idField.setColumns(18);
        UiFonts.apply(idField, UiFonts.BODY, Font.PLAIN);
        idField.setToolTipText("Registration id — the instance name a model key is built from,"
                + " e.g. fast/llama3. Type a new one to add an endpoint.");
        idField.putClientProperty("JTextField.placeholderText", "registration id");
        projectChoice.setToolTipText("Write this project's .naru/config/registrations.tson (checked in)");
        userChoice.setToolTipText("Write ~/.naru/config/registrations.tson (this machine, every project)");
        projectChoice.getAccessibleContext().setAccessibleName("Project scope");
        userChoice.getAccessibleContext().setAccessibleName("User scope");
        javax.swing.ButtonGroup scopeGroup = new javax.swing.ButtonGroup();
        scopeGroup.add(projectChoice);
        scopeGroup.add(userChoice);
        // a changed row's badge names the file its write lands in, so moving that choice repaints
        projectChoice.addActionListener(e -> refreshStates());
        userChoice.addActionListener(e -> refreshStates());

        saveButton = GlyphButton.of("✓", "Write every changed field into the chosen scope");
        toggleButton = GlyphButton.of("⏻", "Enable or disable the selected registration");
        deleteButton = GlyphButton.of("×", "Delete the selected registration from the chosen scope");
        reloadButton = GlyphButton.of("⟳", "Reload the registrations from disk");
        saveButton.addActionListener(e -> saveForm());
        toggleButton.addActionListener(e -> toggleEnabled());
        deleteButton.addActionListener(e -> deleteRegistration());
        reloadButton.addActionListener(e -> reload());

        JPanel scopes = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        scopes.add(projectChoice);
        scopes.add(userChoice);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.add(saveButton);
        buttons.add(toggleButton);
        buttons.add(deleteButton);
        buttons.add(reloadButton);
        JPanel idRow = new JPanel(new BorderLayout(8, 0));
        idRow.add(idField, BorderLayout.CENTER);
        idRow.add(scopes, BorderLayout.EAST);
        JPanel line = new JPanel(new BorderLayout(8, 0));
        line.add(idRow, BorderLayout.CENTER);
        line.add(buttons, BorderLayout.EAST);
        JPanel foot = new JPanel(new BorderLayout(0, 2));
        foot.add(detail, BorderLayout.NORTH);
        foot.add(status, BorderLayout.SOUTH);
        JPanel head = new JPanel(new BorderLayout(0, 6));
        head.add(line, BorderLayout.NORTH);
        head.add(foot, BorderLayout.SOUTH);
        return head;
    }

    private JPanel buildExtras() {
        JLabel label = new JLabel("Undeclared parameters — a provider extension's own knobs");
        UiFonts.apply(label, UiFonts.CAPTION, Font.BOLD);
        UiFonts.apply(extrasTable, UiFonts.CAPTION, Font.PLAIN);
        extrasTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        extrasTable.getTableHeader().setReorderingAllowed(false);
        extrasTable.setName("naru.registration.extras");
        extraRemoveButton = GlyphButton.of("⌫", "Remove the selected undeclared parameter");
        extraRemoveButton.addActionListener(e -> removeExtra());
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        controls.add(extraRemoveButton);
        JPanel line = new JPanel(new BorderLayout());
        line.add(label, BorderLayout.WEST);
        line.add(controls, BorderLayout.EAST);
        JScrollPane scroll = new JScrollPane(extrasTable);
        scroll.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        // two or three rows is the whole story here; past that the table scrolls
        scroll.setPreferredSize(new Dimension(10, 78));
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.add(line, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    /**
     * The table over {@link NaruModelRegistration#extras()}, with one blank row that adds a key. A
     * committed cell is one write: these names belong to whoever registered them, so the form cannot
     * batch them against a vocabulary it does not hold.
     */
    private final class ExtrasModel extends AbstractTableModel {
        private final String[] columns = {"Parameter", "Value", "Owner"};
        private List<Map.Entry<String, NElement>> held = List.of();
        private NaruRegistrationOrigin origin;
        /** Typed into the blank row's key cell; nothing is written until its value is. */
        private String typedKey = "";

        void load(NaruModelRegistration masked, NaruRegistrationOrigin origin) {
            held = masked == null ? List.of() : new ArrayList<>(masked.extras().entrySet());
            this.origin = origin;
            typedKey = "";
            fireTableDataChanged();
        }

        boolean isBlankRow(int r) {
            return r >= held.size();
        }

        /** The stored rows plus the one blank row that adds another — nothing while no id is bound. */
        @Override
        public int getRowCount() {
            return editingId == null ? 0 : held.size() + 1;
        }

        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int c) { return columns[c]; }
        @Override public Class<?> getColumnClass(int c) { return String.class; }

        @Override
        public Object getValueAt(int r, int c) {
            boolean blank = isBlankRow(r);
            Map.Entry<String, NElement> e = blank ? null : held.get(r);
            switch (c) {
                case 0:
                    return blank ? typedKey : e.getKey();
                case 1:
                    return blank ? "" : NaruTaskConfig.toText(e.getValue());
                default:
                    if (blank) {
                        return currentScope().word() + " (new)";
                    }
                    NaruRegistrationScope from = origin == null ? null : origin.scopeOf(e.getKey());
                    return from == null ? "?" : from.word();
            }
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            // a declared parameter has its row in the form; only an undeclared one has a name the
            // reader is free to invent. Renaming a stored key is not offered: the store can only add
            // and remove, and a half-applied rename is worse than none.
            return c == 1 || (c == 0 && isBlankRow(r));
        }

        @Override
        public void setValueAt(Object value, int r, int c) {
            String text = value == null ? "" : String.valueOf(value);
            boolean blank = isBlankRow(r);
            String key = blank ? typedKey : held.get(r).getKey();
            if (c == 0) {
                typedKey = text;
                fireTableRowsUpdated(r, r);
                return;
            }
            if (c != 1) {
                return;
            }
            if (!blank && text.trim().equals(NaruTaskConfig.toText(held.get(r).getValue()))) {
                return;
            }
            writeExtra(key.trim(), text.trim());
        }
    }

    private void writeExtra(String key, String value) {
        String id = currentId();
        if (id.isEmpty()) {
            status.setText("select or type a registration first");
            return;
        }
        if (key.isEmpty()) {
            status.setText("type the parameter name before its value");
            return;
        }
        NaruRegistrationScope scope = currentScope();
        Map<String, NElement> edits = new LinkedHashMap<>();
        edits.put(key, value.isEmpty() ? null : NaruTaskConfig.parseValue(value));
        String problem = gateway.save(id, edits, hasRow(id), scope);
        if (problem != null) {
            status.setText("not saved: " + problem);
            return;
        }
        reload();
        status.setText("'" + key + "' " + (value.isEmpty() ? "removed from" : "written into")
                + " '" + id + "' in the " + scope.word() + " files");
    }

    private void removeExtra() {
        int r = extrasTable.getSelectedRow();
        if (r < 0 || extrasModel.isBlankRow(r)) {
            status.setText("select an undeclared parameter first");
            return;
        }
        writeExtra(extrasModel.held.get(r).getKey(), "");
    }

    // ── actions ───────────────────────────────────────────────────────────────

    private NaruRegistrationScope currentScope() {
        return userChoice.isSelected() ? NaruRegistrationScope.USER : NaruRegistrationScope.PROJECT;
    }

    private String currentId() {
        return idField.getText() == null ? "" : idField.getText().trim();
    }

    private Row selected() {
        int i = table.getSelectedRow();
        return i < 0 || i >= rows.size() ? null : rows.get(i);
    }

    private NaruRegistrationOrigin currentOrigin() {
        Row row = selected();
        return row == null ? null : row.origin;
    }

    private boolean hasRow(String id) {
        return rows.stream().anyMatch(r -> r.id.equalsIgnoreCase(id));
    }

    private void rowSelected() {
        if (syncingSelection) {
            return;
        }
        Row row = selected();
        if (row == null) {
            return;
        }
        if (editingId != null && !editingId.equalsIgnoreCase(row.id) && hasDirty()) {
            // a form that refilled itself quietly would throw away what the reader just typed
            if (!DesktopDialog.confirm(SwingUtilities.getWindowAncestor(this), "Discard changes",
                    "'" + editingId + "' has unsaved changes."
                            + "\nSwitching to '" + row.id + "' throws them away.", "Discard")) {
                restoreSelection();
                return;
            }
        }
        bindForm(row);
    }

    private void restoreSelection() {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id.equalsIgnoreCase(editingId)) {
                selectQuietly(i);
                return;
            }
        }
    }

    private void selectQuietly(int index) {
        syncingSelection = true;
        try {
            table.setRowSelectionInterval(index, index);
        } finally {
            syncingSelection = false;
        }
    }

    /** Fill every widget from one registration — masked, so a literal key never reaches the screen. */
    private void bindForm(Row row) {
        loading = true;
        try {
            editingId = row == null ? null : row.id;
            if (row != null) {
                idField.setText(row.id);
            }
            NaruModelRegistration masked = row == null ? null : row.registration.masked();
            NaruRegistrationOrigin origin = row == null ? null : row.origin;
            for (FieldRow fr : fieldRows) {
                fr.load(masked, origin);
            }
            extrasModel.load(masked, origin);
            protocolRow.candidates(sorted(gateway.protocols(
                    row == null ? null : row.registration.endpointType())));
            updateDetail(row);
            if (row != null) {
                // the hide field starts one provider away from what the reader is looking at: hiding
                // the endpoint just selected is the only reason the field is next to the table
                String provider = row.registration.endpointType();
                hideField.setText(provider == null ? row.id : provider);
            }
        } finally {
            loading = false;
        }
        refreshStates();
    }

    private boolean hasDirty() {
        return fieldRows.stream().anyMatch(FieldRow::isDirty);
    }

    /** Repaint the per-row state after an edit: which rows will be written, and into which file. */
    private void refreshStates() {
        List<String> changed = new ArrayList<>();
        for (FieldRow fr : fieldRows) {
            fr.showState();
            if (fr.isDirty()) {
                changed.add(fr.text().isEmpty() ? fr.field.key() + " (cleared)" : fr.field.key());
            }
        }
        saveButton.setEnabled(gateway.isAvailable() && !changed.isEmpty());
        saveButton.setToolTipText(changed.isEmpty()
                ? "Write every changed field into the chosen scope"
                : "Write " + String.join(", ", changed) + " into the "
                        + currentScope().word() + " registrations");
        if (!changed.isEmpty()) {
            status.setText(changed.size() + " unsaved change"
                    + (changed.size() == 1 ? "" : "s") + ": " + String.join(", ", changed));
        }
    }

    private void saveForm() {
        String id = currentId();
        if (id.isEmpty()) {
            status.setText("type or select a registration id first");
            return;
        }
        Map<String, NElement> edits = new LinkedHashMap<>();
        for (FieldRow fr : fieldRows) {
            String problem = fr.apply(edits);
            if (problem != null) {
                status.setText("not saved: " + problem);
                return;
            }
        }
        if (edits.isEmpty()) {
            status.setText("nothing to write — no field changed");
            return;
        }
        write(id, edits, hasRow(id), w -> describe(edits) + " in the " + w + " registrations");
    }

    /** What a set of edits did, said field by field rather than as a dump of pairs. */
    private static String describe(Map<String, NElement> edits) {
        List<String> set = new ArrayList<>();
        List<String> cleared = new ArrayList<>();
        for (Map.Entry<String, NElement> e : edits.entrySet()) {
            (e.getValue() == null ? cleared : set).add(e.getKey());
        }
        StringBuilder sb = new StringBuilder();
        if (!set.isEmpty()) {
            sb.append(String.join(", ", set)).append(" saved");
        }
        if (!cleared.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(String.join(", ", cleared)).append(" cleared");
        }
        return sb.toString();
    }

    /** One write and its answer; the success line is built once the scope is known. */
    private void write(String id, Map<String, NElement> edits, boolean update,
                       Function<String, String> success) {
        NaruRegistrationScope scope = currentScope();
        String problem = gateway.save(id, edits, update, scope);
        if (problem != null) {
            status.setText("not saved: " + problem);
            return;
        }
        reload();
        Row row = selected();
        if (row != null) {
            bindForm(row);
        }
        status.setText(success.apply(scope.word()));
    }

    private void toggleEnabled() {
        Row row = selected();
        if (row == null) {
            status.setText("select a registration first");
            return;
        }
        boolean enabled = !Boolean.FALSE.equals(row.registration.enabled().orNull());
        Map<String, NElement> edits = new LinkedHashMap<>();
        edits.put("enabled", NElement.of(!enabled));
        write(row.id, edits, true,
                w -> "'" + row.id + "' " + (enabled ? "disabled" : "enabled")
                        + " in the " + w + " registrations");
    }

    private void deleteRegistration() {
        Row row = selected();
        if (row == null) {
            status.setText("select a registration first");
            return;
        }
        NaruRegistrationScope scope = currentScope();
        NaruRegistrationOrigin origin = gateway.origin(row.id);
        if (origin == null || !origin.isDeclaredIn(scope)) {
            status.setText("the " + scope.word() + " files do not declare '" + row.id
                    + "' — it stays, change the scope to delete where it lives");
            return;
        }
        NaruRegistrationScope other = scope == NaruRegistrationScope.PROJECT
                ? NaruRegistrationScope.USER : NaruRegistrationScope.PROJECT;
        String survives = origin.isDeclaredIn(other)
                ? "\nThe " + other.word() + " layer declares it as well, and will still apply after"
                        + " this delete."
                : "\nNothing else declares it: the endpoint is gone.";
        if (!DesktopDialog.confirm(SwingUtilities.getWindowAncestor(this), "Delete registration",
                "Delete '" + row.id + "' from the " + scope.word() + " files?" + survives, "Delete")) {
            return;
        }
        String problem = gateway.remove(row.id, scope);
        if (problem == null) {
            reload();
            status.setText("'" + row.id + "' removed from the " + scope.word() + " files");
        } else {
            status.setText(problem);
        }
    }

    private void hideTyped(boolean hidden) {
        String text = hideField.getText() == null ? "" : hideField.getText().trim();
        if (text.isEmpty()) {
            status.setText("type a provider, or a provider/model to hide one model");
            return;
        }
        int slash = text.indexOf('/');
        if (slash < 0) {
            prefs().hideProvider(text, hidden);
        } else {
            prefs().hideModel(text.substring(0, slash).trim(), text.substring(slash + 1).trim(), hidden);
        }
        reloadHidden();
        status.setText(text + (hidden ? " hidden from the picker" : " shown in the picker again"));
    }

    private void unhideSelected() {
        String entry = hiddenList.getSelectedValue();
        if (entry == null) {
            status.setText("select a hidden entry first");
            return;
        }
        int slash = entry.indexOf('/');
        if (slash < 0) {
            prefs().hideProvider(entry, false);
        } else {
            prefs().hideModel(entry.substring(0, slash), entry.substring(slash + 1), false);
        }
        reloadHidden();
        status.setText(entry + " is shown in the picker again");
    }

    // ── reload ────────────────────────────────────────────────────────────────

    private void reload() {
        // the form keeps its binding across a rebuild: a write reloads the store, and a pane that
        // refilled from an empty selection would erase what the reader is working on
        String keepId = editingId;
        rows.clear();
        for (Map.Entry<String, NaruModelRegistration> e
                : new TreeMap<>(gateway.registrations()).entrySet()) {
            rows.add(new Row(e.getKey(), e.getValue(), gateway.origin(e.getKey())));
        }
        model.setRows(rows);
        if (selected() == null && keepId != null) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id.equalsIgnoreCase(keepId)) {
                    selectQuietly(i);
                    break;
                }
            }
        }
        Row row = selected();
        if (row == null) {
            bindForm(null);
        } else if (!row.id.equalsIgnoreCase(editingId == null ? "" : editingId)) {
            bindForm(row);
        }
        reloadHidden();
        // an editor that writes through a session has nothing to say without one: leaving the buttons
        // live would promise a save that can only ever report an error
        boolean available = gateway.isAvailable();
        for (JButton b : new JButton[]{extraRemoveButton, toggleButton, deleteButton}) {
            b.setEnabled(available);
        }
        providerRow.candidates(sorted(gateway.providerTypes()));
        NPath dir = projectDirectory == null ? null : projectDirectory.get();
        if (!available) {
            copy.setText("Open a session in this project to read or edit its registered endpoints.");
        } else if (rows.isEmpty()) {
            copy.setText("No endpoint is registered yet. Add one here, or with "
                    + "'/model add <id> --provider=<type>'.");
        } else {
            copy.setText(rows.size() + " registered endpoint" + (rows.size() == 1 ? "" : "s")
                    + " · editing " + (dir == null ? "no project" : dir));
        }
        refreshStates();
    }

    private void reloadHidden() {
        List<String> entries = new ArrayList<>(prefs().hiddenProviders());
        entries.addAll(prefs().hiddenModels());
        hiddenRows.clear();
        for (String s : entries) {
            hiddenRows.addElement(s);
        }
    }

    private ModelViewPreferences prefs() {
        NPath dir = projectDirectory == null ? null : projectDirectory.get();
        return ModelViewPreferences.of(dir);
    }

    /** The merge story in one line: which file owns which field, instead of one dump of pairs. */
    private void updateDetail(Row row) {
        if (row == null) {
            detail.setText(" ");
            detail.setToolTipText(null);
            return;
        }
        NaruModelRegistration masked = row.registration.masked();
        Map<String, List<String>> byScope = new LinkedHashMap<>();
        for (NaruRegistrationFields.Field<?> f : NaruRegistrationFields.fields()) {
            if (f.get(masked) == null) {
                continue;
            }
            NaruRegistrationScope from = row.origin == null ? null : row.origin.scopeOf(f.key());
            byScope.computeIfAbsent(from == null ? "?" : from.word(), k -> new ArrayList<>())
                    .add(f.key());
        }
        for (String key : masked.extras().keySet()) {
            NaruRegistrationScope from = row.origin == null ? null : row.origin.scopeOf(key);
            byScope.computeIfAbsent((from == null ? "?" : from.word()) + " (undeclared)",
                    k -> new ArrayList<>()).add(key);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : byScope.entrySet()) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append(String.join(", ", e.getValue())).append(" from ").append(e.getKey());
        }
        if (masked.hasLiteralApiKey()) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append("the api key is a literal: stored privately, shown masked");
        }
        detail.setText(sb.length() == 0 ? "nothing set yet" : sb.toString());
        detail.setToolTipText(sb.length() == 0 ? null : sb.toString());
    }

    // ── values across the widget boundary ─────────────────────────────────────

    /** The words that tell a reader what to type, taken from the declared kind and nowhere else. */
    private static String wordsFor(NaruRegistrationFields.Field<?> f) {
        switch (f.kind()) {
            case BOOL:
                return "true or false";
            case INT:
                return "a whole number";
            case FLOAT:
                return "a number";
            case TEXT_LIST:
                return "a comma-separated list";
            case RAW:
                return "an open,close pair or a {openTag, closeTag} object, stored as written";
            default:
                return f == NaruRegistrationFields.PROVIDER
                        ? "a provider type this build offers"
                        : "text";
        }
    }

    /** The value as the widget shows it: a list joins with commas, a raw element prints as tson. */
    private static String displayOf(NaruRegistrationFields.Field<?> f, NaruModelRegistration reg) {
        Object v = f.get(reg);
        if (v == null) {
            return "";
        }
        if (v instanceof List<?> items) {
            StringBuilder sb = new StringBuilder();
            for (Object o : items) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(NaruTaskConfig.toText(o));
            }
            return sb.toString();
        }
        String s = NaruTaskConfig.toText(v);
        return s == null ? "" : s;
    }

    /**
     * Typed text as the declared kind, or null when it is not that kind. A number field that filed
     * {@code "abc"} as a string would read back as unset, so it is refused at the widget instead.
     */
    private static NElement parseAs(NaruRegistrationFields.Field<?> f, String v) {
        switch (f.kind()) {
            case INT:
                return NLiteral.of(v).asLong().isPresent() ? NaruTaskConfig.parseValue(v) : null;
            case FLOAT:
                return NLiteral.of(v).asDouble().isPresent() ? NaruTaskConfig.parseValue(v) : null;
            case RAW: {
                // the one parameter whose shape is not core's to decide: an object typed here stays
                // an object rather than becoming a string that reads as no tags at all
                char head = v.charAt(0);
                return head == '{' || head == '['
                        ? NElementReader.ofTson().read(v)
                        : NaruTaskConfig.parseValue(v);
            }
            default:
                return NaruTaskConfig.parseValue(v);
        }
    }

    private static String ownerOf(String key, NaruRegistrationOrigin origin, String storedText) {
        if (storedText.isEmpty()) {
            return "";
        }
        NaruRegistrationScope from = origin == null ? null : origin.scopeOf(key);
        return from == null ? "?" : from.word();
    }

    private static List<String> sorted(Collection<String> values) {
        // the registry hands back whatever order its own map had; a list that reorders itself while
        // the reader is choosing from it reads as a bug in the field
        List<String> out = new ArrayList<>(values);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static String widgetText(JComponent w) {
        if (w instanceof JComboBox<?> box) {
            Object item = box.getEditor() == null ? box.getSelectedItem() : box.getEditor().getItem();
            return item == null ? "" : String.valueOf(item).trim();
        }
        return ((JTextField) w).getText().trim();
    }

    private static void setWidgetText(JComponent w, String text) {
        if (w instanceof JComboBox<?> box) {
            box.getEditor().setItem(text);
        } else {
            ((JTextField) w).setText(text);
        }
    }

    /** Replace a combo's list without touching what the reader typed into it. */
    private static void setCandidates(JComboBox<String> box, List<String> values) {
        String keep = box.getEditor() == null || box.getEditor().getItem() == null
                ? "" : box.getEditor().getItem().toString();
        DefaultComboBoxModel<String> next = new DefaultComboBoxModel<>(values.toArray(new String[0]));
        next.setSelectedItem(keep);
        box.setModel(next);
    }

    /** The input for one declared parameter, by its canonical name; null when there is no such row. */
    JComponent fieldInput(String key) {
        FieldRow row = rowsByKey.get(key);
        return row == null ? null : row.widget;
    }

    /** The badge beside that input: which file owns the value, or where an unsaved write goes. */
    JLabel fieldBadge(String key) {
        FieldRow row = rowsByKey.get(key);
        return row == null ? null : row.badge;
    }

    /** What one field's widget holds right now, as the reader sees it. */
    String fieldText(String key) {
        FieldRow row = rowsByKey.get(key);
        return row == null ? null : row.text();
    }

    private JPanel buildHiddenPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        JLabel label = new JLabel("Hidden from the model picker (this project, this machine)");
        UiFonts.apply(label, UiFonts.CAPTION, Font.BOLD);
        UiFonts.apply(hideField, UiFonts.CAPTION, Font.PLAIN);
        hideField.setToolTipText("A provider name, or a full provider/model key to hide one model");
        hideField.putClientProperty("JTextField.placeholderText", "provider, or provider/model");
        JPanel controls = new JPanel(new BorderLayout(6, 0));
        controls.add(hideField, BorderLayout.CENTER);
        hideAddButton = GlyphButton.of("＋", "Hide this provider or model in the picker");
        hideRemoveButton = GlyphButton.of("－", "Show the selected entry in the picker again");
        hideAddButton.addActionListener(e -> hideTyped(true));
        hideRemoveButton.addActionListener(e -> unhideSelected());
        JPanel box = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        box.add(hideAddButton);
        box.add(hideRemoveButton);
        controls.add(box, BorderLayout.EAST);
        UiFonts.apply(hiddenList, UiFonts.CAPTION, Font.PLAIN);
        hiddenList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane hiddenScroll = new JScrollPane(hiddenList);
        hiddenScroll.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        hiddenScroll.setPreferredSize(new Dimension(10, 74));
        panel.add(label, BorderLayout.NORTH);
        panel.add(hiddenScroll, BorderLayout.CENTER);
        panel.add(controls, BorderLayout.SOUTH);
        return panel;
    }

    private static final class RegistrationModel extends AbstractTableModel {
        private final String[] columns = {"Id", "Provider", "Wire", "Where", "On"};
        private List<Row> rows = List.of();

        void setRows(List<Row> next) {
            rows = new ArrayList<>(next);
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int c) { return columns[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            NaruModelRegistration reg = row.registration;
            Object value;
            switch (c) {
                case 0 -> value = row.id;
                case 1 -> value = reg.isGeneric() ? "(generic)" : reg.provider().get();
                case 2 -> value = reg.protocol().orNull();
                case 3 -> value = row.where();
                default -> value = Boolean.FALSE.equals(reg.enabled().orNull()) ? "no" : "yes";
            }
            return value == null ? "" : value;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            // one row is not one cell the reader can retype: a field belongs to a file, and an
            // in-place edit would have to guess which of the two it was writing
            return false;
        }
    }
}
