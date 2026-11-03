package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruTaskConfig;
import net.thevpc.naru.api.model.NaruModelRegistration;
import net.thevpc.naru.api.model.NaruRegistrationOrigin;
import net.thevpc.naru.api.model.NaruRegistrationScope;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The settings surface for registered model providers, and for what the picker shows.
 *
 * <p>Two axes, deliberately in two halves of this one pane. The table and the editor below it write
 * {@code registrations.tson} — the endpoint definitions, in the project's files or the user's own,
 * through the same rules the {@code /model} command uses. The hidden list at the bottom is the
 * <b>view</b> axis: it changes what this reader sees in the model picker on this machine, and writes
 * no project file at all. Keeping both here, rather than folding hiding into the policy keys, is the
 * point: a provider you never use is not a provider your teammates may not run.
 *
 * <p>Values are typed the way {@code /settings} and {@link WorkspaceEnvPane} type them — one
 * parameter name, one value, merged into the stored registration — because the parameter set is the
 * store's, and a form that hard-wired its own widget per field would silently drop every parameter
 * it does not know. {@code provider} and {@code protocol} do get candidate lists: naming a provider
 * type wrong is the one typo that registers nothing at all.
 */
public final class ModelProvidersPane extends JPanel {

    private static final String[] BOOLEAN_VALUES = {"true", "false"};

    private final ModelRegistrationGateway gateway;
    /** The project whose view settings this pane reads and writes; null when none is open. */
    private final Supplier<NPath> projectDirectory;

    private final JLabel heading = new JLabel("Models & providers");
    private final JLabel copy = new JLabel("Registered endpoints this session can use, and the providers "
            + "and models this window keeps out of the picker.");
    private final RegistrationModel model = new RegistrationModel();
    private final JTable table = new JTable(model);
    private final JTextField idField = new JTextField();
    private final JTextField paramField = new JTextField();
    private final JComboBox<String> valueBox = new JComboBox<>();
    private final JRadioButton projectChoice = new JRadioButton("Project", true);
    private final JRadioButton userChoice = new JRadioButton("User");
    private final JTextField hideField = new JTextField();
    private final DefaultListModel<String> hiddenRows = new DefaultListModel<>();
    private final JList<String> hiddenList = new JList<>(hiddenRows);
    private final JLabel detail = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private final List<Row> rows = new ArrayList<>();
    private JButton saveButton;
    private JButton clearFieldButton;
    private JButton toggleButton;
    private JButton deleteButton;
    private JButton reloadButton;
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
        UiFonts.apply(table, UiFonts.BODY, Font.PLAIN);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                rowSelected();
            }
        });
        JScrollPane scroll = new JScrollPane(table);
        UiFonts.apply(scroll.getViewport(), UiFonts.BODY, Font.PLAIN);
        scroll.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.add(texts, BorderLayout.NORTH);
        center.add(scroll, BorderLayout.CENTER);
        center.add(buildEditor(), BorderLayout.SOUTH);

        add(center, BorderLayout.CENTER);
        add(buildHiddenPanel(), BorderLayout.SOUTH);
        reload();
    }

    private JPanel buildEditor() {
        UiFonts.apply(idField, UiFonts.BODY, Font.PLAIN);
        UiFonts.apply(paramField, UiFonts.BODY, Font.PLAIN);
        UiFonts.apply(valueBox, UiFonts.BODY, Font.PLAIN);
        idField.setToolTipText("Registration id — the instance name a model key is built from, e.g. fast/llama3");
        paramField.setToolTipText("Parameter to write: provider, protocol, url, apiKey, models, temperature, ...");
        valueBox.setToolTipText("Value, typed the way /settings types it: boolean, number, or text");
        // three boxes in a row name nothing until they are filled in; the words have to be there from
        // the start, and a tooltip that requires hovering a blank field asks the reader to guess first
        idField.putClientProperty("JTextField.placeholderText", "registration id");
        paramField.putClientProperty("JTextField.placeholderText", "parameter, e.g. apiKey");
        hideField.putClientProperty("JTextField.placeholderText", "provider, or provider/model");
        idField.setColumns(16);
        paramField.setColumns(18);
        valueBox.setEditable(true);
        JTextField valueEditor = (JTextField) valueBox.getEditor().getEditorComponent();
        valueEditor.setColumns(22);
        valueEditor.putClientProperty("JTextField.placeholderText", "value");
        paramField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { offerValues(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { offerValues(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { offerValues(); }
        });

        projectChoice.setToolTipText("Write this project's .naru/config/registrations.tson (checked in)");
        userChoice.setToolTipText("Write ~/.naru/config/registrations.tson (this machine, every project)");
        projectChoice.getAccessibleContext().setAccessibleName("Project scope");
        userChoice.getAccessibleContext().setAccessibleName("User scope");
        javax.swing.ButtonGroup scopeGroup = new javax.swing.ButtonGroup();
        scopeGroup.add(projectChoice);
        scopeGroup.add(userChoice);

        saveButton = GlyphButton.of("✓", "Write this parameter into the chosen scope");
        clearFieldButton = GlyphButton.of("⌫", "Clear this parameter from the chosen scope");
        toggleButton = GlyphButton.of("⏻", "Enable or disable the selected registration");
        deleteButton = GlyphButton.of("×", "Delete the selected registration from the chosen scope");
        reloadButton = GlyphButton.of("⟳", "Reload the registrations from disk");
        saveButton.addActionListener(e -> saveField());
        clearFieldButton.addActionListener(e -> clearField());
        toggleButton.addActionListener(e -> toggleEnabled());
        deleteButton.addActionListener(e -> deleteRegistration());
        reloadButton.addActionListener(e -> reload());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.add(saveButton);
        buttons.add(clearFieldButton);
        buttons.add(toggleButton);
        buttons.add(deleteButton);
        buttons.add(reloadButton);

        JPanel scopes = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        scopes.add(projectChoice);
        scopes.add(userChoice);

        JPanel fields = new JPanel(new java.awt.GridLayout(1, 3, 8, 0));
        fields.add(idField);
        fields.add(paramField);
        fields.add(valueBox);
        JPanel actions = new JPanel(new BorderLayout());
        actions.add(scopes, BorderLayout.WEST);
        actions.add(buttons, BorderLayout.EAST);
        JPanel head = new JPanel(new BorderLayout(0, 8));
        head.add(fields, BorderLayout.NORTH);
        head.add(actions, BorderLayout.SOUTH);
        JPanel foot = new JPanel(new BorderLayout(0, 2));
        foot.add(detail, BorderLayout.NORTH);
        foot.add(status, BorderLayout.SOUTH);
        JPanel editor = new JPanel(new BorderLayout(0, 8));
        editor.add(head, BorderLayout.NORTH);
        editor.add(foot, BorderLayout.SOUTH);
        return editor;
    }

    private JPanel buildHiddenPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        JLabel label = new JLabel("Hidden from the model picker (this project, this machine)");
        UiFonts.apply(label, UiFonts.CAPTION, Font.BOLD);
        UiFonts.apply(hideField, UiFonts.CAPTION, Font.PLAIN);
        hideField.setToolTipText("A provider name, or a full provider/model key to hide one model");
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
        hiddenScroll.setPreferredSize(new java.awt.Dimension(10, 74));
        panel.add(label, BorderLayout.NORTH);
        panel.add(hiddenScroll, BorderLayout.CENTER);
        panel.add(controls, BorderLayout.SOUTH);
        return panel;
    }

    // ── actions ───────────────────────────────────────────────────────────────

    private NaruRegistrationScope currentScope() {
        return userChoice.isSelected() ? NaruRegistrationScope.USER : NaruRegistrationScope.PROJECT;
    }

    private String currentId() {
        return idField.getText() == null ? "" : idField.getText().trim();
    }

    private String currentParam() {
        return paramField.getText() == null ? "" : paramField.getText().trim();
    }

    private Row selected() {
        int i = table.getSelectedRow();
        return i < 0 || i >= rows.size() ? null : rows.get(i);
    }

    private void rowSelected() {
        Row row = selected();
        if (row == null) {
            return;
        }
        idField.setText(row.id);
        updateDetail(row);
        // the hide field starts one provider away from what the reader is looking at: hiding the
        // endpoint you just selected is the only reason the field is next to the table
        String provider = row.registration.provider();
        hideField.setText(provider == null ? row.id : provider);
    }

    private void saveField() {
        String id = currentId();
        String param = currentParam();
        if (id.isEmpty() || param.isEmpty()) {
            status.setText("needs an id and a parameter name");
            return;
        }
        Object item = valueBox.getEditor().getItem();
        String raw = item == null ? "" : item.toString();
        if (raw.isBlank()) {
            status.setText("empty value: use the clear-field button instead");
            return;
        }
        Map<String, NElement> edits = new LinkedHashMap<>();
        edits.put(param, NaruTaskConfig.parseValue(raw));
        write(id, edits, rows.stream().anyMatch(r -> r.id.equalsIgnoreCase(id)),
                w -> "'" + id + "' saved in the " + w + " registrations");
    }

    private void clearField() {
        String id = currentId();
        String param = currentParam();
        if (id.isEmpty() || param.isEmpty()) {
            status.setText("needs an id and the parameter to clear");
            return;
        }
        Map<String, NElement> edits = new LinkedHashMap<>();
        edits.put(param, null);
        write(id, edits, true,
                w -> "'" + param + "' cleared from '" + id + "' in the " + w + " files");
    }

    private void toggleEnabled() {
        Row row = selected();
        if (row == null) {
            status.setText("select a registration first");
            return;
        }
        boolean enabled = !Boolean.FALSE.equals(row.registration.booleanValue("enabled").orNull());
        Map<String, NElement> edits = new LinkedHashMap<>();
        edits.put("enabled", NElement.of(!enabled));
        write(row.id, edits, true,
                w -> "'" + row.id + "' " + (enabled ? "disabled" : "enabled")
                        + " in the " + w + " registrations");
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
        status.setText(success.apply(scope.word()));
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
        // a write reloads the table, and a table that forgets which row was selected turns the next
        // click — ⏻ again, or × — into an operation on nothing
        String keepId = selected() == null ? null : selected().id;
        rows.clear();
        for (Map.Entry<String, NaruModelRegistration> e : new TreeMap<>(gateway.registrations()).entrySet()) {
            rows.add(new Row(e.getKey(), e.getValue(), gateway.origin(e.getKey())));
        }
        model.setRows(rows);
        if (keepId != null) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id.equalsIgnoreCase(keepId)) {
                    table.setRowSelectionInterval(i, i);
                    break;
                }
            }
        }
        reloadHidden();
        // an editor that writes through a session has nothing to say without one: leaving the buttons
        // live would promise a save that can only ever report an error
        boolean available = gateway.isAvailable();
        for (JButton b : new JButton[]{saveButton, clearFieldButton, toggleButton, deleteButton}) {
            b.setEnabled(available);
        }
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
        offerValues();
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

    private void updateDetail(Row row) {
        NaruModelRegistration masked = row.registration.masked();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, NElement> e : masked.params().entrySet()) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            NaruRegistrationScope from = row.origin == null ? null : row.origin.scopeOf(e.getKey());
            sb.append(e.getKey()).append('=').append(NaruTaskConfig.toText(e.getValue()));
            if (from != null) {
                sb.append('[').append(from.word()).append(']');
            }
        }
        detail.setText(sb.length() == 0 ? " " : sb.toString());
        detail.setToolTipText(sb.length() == 0 ? null : sb.toString());
    }

    /**
     * The candidate values for the parameter typed so far. {@code provider} and {@code protocol}
     * are the two worth listing — a provider type is an id, and guessing it registers nothing — and
     * the flags get their two words. Everything else is free text, including a literal {@code
     * apiKey}, which belongs to whichever of the two files is currently selected.
     */
    private void offerValues() {
        String param = currentParam();
        List<String> candidates = new ArrayList<>();
        if ("provider".equalsIgnoreCase(param)) {
            candidates.addAll(gateway.providerTypes());
        } else if ("protocol".equalsIgnoreCase(param)) {
            Row row = selected();
            String type = row == null ? null : row.registration.provider();
            candidates.addAll(gateway.protocols(type));
        } else if ("enabled".equalsIgnoreCase(param) || "tools".equalsIgnoreCase(param)
                || "probe".equalsIgnoreCase(param) || "free".equalsIgnoreCase(param)) {
            candidates.addAll(List.of(BOOLEAN_VALUES));
        }
        // the registry hands back whatever order its own map had; a list that reorders itself while
        // the reader is choosing from it reads as a bug in the field
        candidates.sort(String.CASE_INSENSITIVE_ORDER);
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < valueBox.getItemCount(); i++) {
            shown.add(valueBox.getItemAt(i));
        }
        if (shown.equals(candidates)) {
            // swapping the model of an editable combo re-selects its first row, which is how a
            // keystroke in the value field would start overwriting itself; the list only moves when
            // the parameter actually changed hands
            return;
        }
        String keep = valueBox.getEditor() == null || valueBox.getEditor().getItem() == null
                ? "" : valueBox.getEditor().getItem().toString();
        javax.swing.DefaultComboBoxModel<String> next =
                new javax.swing.DefaultComboBoxModel<>(candidates.toArray(new String[0]));
        next.setSelectedItem(keep);
        valueBox.setModel(next);
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
                case 1 -> value = reg.provider() == null ? "(generic)" : reg.provider();
                case 2 -> value = reg.protocol().orNull();
                case 3 -> value = row.where();
                default -> value = Boolean.FALSE.equals(reg.booleanValue("enabled").orNull()) ? "no" : "yes";
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
