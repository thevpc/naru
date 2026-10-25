package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruTaskConfig;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.registry.NaruConfigKey;
import net.thevpc.naru.api.registry.NaruLineCompletion;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Editor for the workspace project config: the two {@code env.tson} files behind
 * {@link NaruWorkspace#env()}.
 *
 * <p>Mirrors {@code /settings}: the same value typing (boolean / number / text), the same
 * private-wins-public resolution, and a write that lands in exactly one of the two files.
 * Saving reads the key back from the store and shows what came off the disk, so a
 * round-trip failure is visible instead of silent.
 *
 * <p>The key field is a plain text field with the composer's {@link CompletionPopup}, not an
 * editable {@code JComboBox}: filtering a combo means swapping its model, and every swap
 * re-selects the first row and overwrites the editor with it — the typed prefix (and the
 * picked candidate) kept turning into whatever key sorted first. A text field owns its text.
 */
public final class WorkspaceEnvPane extends JPanel {

    private static final class Row {
        final String key;
        final NElement publicValue;
        final NElement privateValue;

        Row(String key, NElement publicValue, NElement privateValue) {
            this.key = key;
            this.publicValue = publicValue;
            this.privateValue = privateValue;
        }
    }

    private final Supplier<NaruWorkspace> workspaceSupplier;
    private final Supplier<NaruSession> liveSession;
    private final JLabel heading = new JLabel("Workspace environment");
    private final JLabel pathsCopy = new JLabel(" ");
    private final EnvModel model = new EnvModel();
    private final JTable table = new JTable(model);
    private final JTextField keyField = new JTextField();
    private final CompletionPopup keyPopup = new CompletionPopup();
    private final JComboBox<String> valueBox = new JComboBox<>();
    private final JRadioButton privateChoice = new JRadioButton("Private", true);
    private final JRadioButton publicChoice = new JRadioButton("Public");
    private final JLabel detail = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private JButton applyButton;
    private JButton removeButton;
    private JButton refreshButton;

    /** Sorted union of declared keys and keys already on disk; recomputed on reload. */
    private List<String> keyItems = List.of();
    /** Declared keys by name, for descriptions and fixed value vocabularies. */
    private Map<String, NaruConfigKey> knownKeys = Map.of();
    /** Set while the pane writes the key field itself, so it does not pop its own list. */
    private boolean programmaticKeyText;

    public WorkspaceEnvPane(Supplier<NaruWorkspace> workspaceSupplier, Supplier<NaruSession> liveSession) {
        super(new BorderLayout(0, 14));
        this.workspaceSupplier = workspaceSupplier;
        this.liveSession = liveSession;
        UiFonts.apply(heading, UiFonts.SUBTITLE, Font.BOLD);
        UiFonts.apply(pathsCopy, UiFonts.CAPTION, Font.PLAIN);
        UiFonts.apply(detail, UiFonts.CAPTION, Font.PLAIN);
        UiFonts.apply(status, UiFonts.CAPTION, Font.PLAIN);

        JPanel texts = new JPanel(new BorderLayout(0, 6));
        texts.add(heading, BorderLayout.NORTH);
        texts.add(pathsCopy, BorderLayout.SOUTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowSelectionAllowed(true);
        table.getTableHeader().setReorderingAllowed(false);
        UiFonts.apply(table, UiFonts.BODY, Font.PLAIN);
        table.getSelectionModel().addListSelectionListener(this::rowSelected);
        JScrollPane scroll = new JScrollPane(table);
        UiFonts.apply(scroll.getViewport(), UiFonts.BODY, Font.PLAIN);
        scroll.setBorder(BorderFactory.createLineBorder(UiPalette.border()));

        add(texts, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(buildEditor(), BorderLayout.SOUTH);
        reload();
    }

    private JPanel buildEditor() {
        UiFonts.apply(keyField, UiFonts.BODY, Font.PLAIN);
        UiFonts.apply(valueBox, UiFonts.BODY, Font.PLAIN);
        keyField.setToolTipText("Setting key; typed prefixes are completed from the keys this build declares");
        valueBox.setToolTipText("Value, typed the way /settings types it: boolean, number, or text");
        keyField.setColumns(28);
        valueBox.setEditable(true);
        ((JTextField) valueBox.getEditor().getEditorComponent()).setColumns(28);
        bindKeyCompletion();

        privateChoice.setToolTipText("Write .naru/local/config/env.tson (untracked, personal)");
        publicChoice.setToolTipText("Write .naru/config/env.tson (checked in, shared)");
        privateChoice.getAccessibleContext().setAccessibleName("Private file");
        publicChoice.getAccessibleContext().setAccessibleName("Public file");
        javax.swing.ButtonGroup visibilityGroup = new javax.swing.ButtonGroup();
        visibilityGroup.add(privateChoice);
        visibilityGroup.add(publicChoice);

        JButton apply = applyButton = GlyphButton.of("✓", "Save this entry to the chosen file");
        JButton remove = removeButton = GlyphButton.of("×", "Remove this key from the chosen file");
        JButton refresh = refreshButton = GlyphButton.of("⟳", "Reload both config files from disk");
        apply.addActionListener(e -> applyValue());
        remove.addActionListener(e -> removeKey());
        refresh.addActionListener(e -> reload());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(apply);
        buttons.add(remove);
        buttons.add(refresh);

        JPanel editor = new JPanel(new BorderLayout(0, 8));
        // two rows: the fields stretch across the card's width, and the visibility and
        // actions keep their own row instead of being pushed off-screen at large sizes
        JPanel fields = new JPanel(new java.awt.GridLayout(1, 2, 8, 0));
        fields.add(keyField);
        fields.add(valueBox);
        JPanel actions = new JPanel(new BorderLayout());
        JPanel radios = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        radios.add(privateChoice);
        radios.add(publicChoice);
        actions.add(radios, BorderLayout.WEST);
        actions.add(buttons, BorderLayout.EAST);
        JPanel head = new JPanel(new BorderLayout(0, 8));
        head.add(fields, BorderLayout.NORTH);
        head.add(actions, BorderLayout.SOUTH);
        JPanel foot = new JPanel(new BorderLayout(0, 2));
        foot.add(detail, BorderLayout.NORTH);
        foot.add(status, BorderLayout.SOUTH);
        editor.add(head, BorderLayout.NORTH);
        editor.add(foot, BorderLayout.SOUTH);
        return editor;
    }

    /**
     * The key field's completion: every keystroke re-filters the catalog into the popup,
     * Enter or a double click commits the highlighted row, and the field's text is never
     * rewritten by anything but the user or a commit.
     */
    private void bindKeyCompletion() {
        keyPopup.setOnPick(candidate -> {
            setKeyText(candidate.insert());
            keyPopup.hide();
            commitKey();
        });
        keyField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { typed(); }
            @Override public void removeUpdate(DocumentEvent e) { typed(); }
            @Override public void changedUpdate(DocumentEvent e) { typed(); }
        });
        javax.swing.InputMap im = keyField.getInputMap(JTextField.WHEN_FOCUSED);
        javax.swing.ActionMap am = keyField.getActionMap();
        im.put(javax.swing.KeyStroke.getKeyStroke("DOWN"), "completion-down");
        im.put(javax.swing.KeyStroke.getKeyStroke("UP"), "completion-up");
        im.put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "completion-enter");
        im.put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "completion-escape");
        am.put("completion-down", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (keyPopup.isVisible()) {
                    keyPopup.moveSelection(true);
                } else {
                    showKeyPopup(true);
                }
            }
        });
        am.put("completion-up", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (keyPopup.isVisible()) {
                    keyPopup.moveSelection(false);
                }
            }
        });
        am.put("completion-enter", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (keyPopup.isVisible()) {
                    keyPopup.commitSelected();
                } else {
                    commitKey();
                }
            }
        });
        am.put("completion-escape", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                keyPopup.hide();
            }
        });
        keyField.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                showKeyPopup(false);
            }

            @Override public void focusLost(FocusEvent e) {
                keyPopup.hide();
                commitKey();
            }
        });
    }

    private void typed() {
        updateDetail();
        if (!programmaticKeyText) {
            showKeyPopup(false);
        }
    }

    /** The candidates for the field's current prefix; an empty field offers the whole catalog. */
    private void showKeyPopup(boolean forceEmpty) {
        String prefix = keyField.getText().trim();
        if (prefix.isEmpty() && !forceEmpty) {
            keyPopup.hide();
            return;
        }
        List<NaruLineCompletion.Candidate> candidates = new ArrayList<>();
        String p = prefix.toLowerCase();
        for (String k : keyItems) {
            if (p.isEmpty() || k.toLowerCase().startsWith(p)) {
                NaruConfigKey declared = knownKeys.get(k);
                candidates.add(new NaruLineCompletion.Candidate(k,
                        declared == null ? "" : declared.description(),
                        NaruLineCompletion.Kind.KEYWORD));
            }
        }
        boolean shown = keyPopup.show(keyField,
                new Rectangle(0, 0, keyField.getWidth(), keyField.getHeight()),
                new NaruLineCompletion.Result(candidates, prefix, 0, prefix.length(), false));
        if (!shown) {
            keyPopup.hide();
        }
    }

    private void setKeyText(String text) {
        programmaticKeyText = true;
        try {
            keyField.setText(text);
        } finally {
            programmaticKeyText = false;
        }
    }

    /** A key is settled (committed, picked, or selected from the table): retype the value field. */
    private void commitKey() {
        updateVocabulary();
        updateDetail();
    }

    /** When the committed key declares a fixed vocabulary, offer exactly those values. */
    private void updateVocabulary() {
        String had = valueBox.getEditor().getItem() == null ? "" : valueBox.getEditor().getItem().toString();
        NaruConfigKey k = knownKeys.get(currentKey());
        List<String> values = k == null ? null : k.values();
        if (values != null && !values.isEmpty()) {
            valueBox.setModel(new DefaultComboBoxModel<>(values.toArray(new String[0])));
            valueBox.setToolTipText(k.key() + " accepts: " + String.join(", ", values));
        } else {
            valueBox.setModel(new DefaultComboBoxModel<>(new String[0]));
            valueBox.setToolTipText("Value, typed the way /settings types it: boolean, number, or text");
        }
        if (!had.isEmpty()) {
            @SuppressWarnings("unchecked")
            DefaultComboBoxModel<String> m = (DefaultComboBoxModel<String>) valueBox.getModel();
            if (!contains(m, had)) {
                m.insertElementAt(had, 0);
            }
            valueBox.getEditor().setItem(had);
        }
    }

    private static boolean contains(DefaultComboBoxModel<String> m, String value) {
        for (int i = 0; i < m.getSize(); i++) {
            if (value.equals(m.getElementAt(i))) {
                return true;
            }
        }
        return false;
    }

    private String currentKey() {
        return keyField.getText().trim();
    }

    private NaruVisibility currentVisibility() {
        return publicChoice.isSelected() ? NaruVisibility.PUBLIC : NaruVisibility.PRIVATE;
    }

    private void rowSelected(ListSelectionEvent e) {
        if (e.getValueIsAdjusting()) {
            return;
        }
        int i = table.getSelectedRow();
        if (i < 0) {
            return;
        }
        Row row = model.row(i);
        // choosing a row is a commit: any completion list still open from typing is stale now
        keyPopup.hide();
        setKeyText(row.key);
        commitKey();
        NElement winner = row.privateValue != null ? row.privateValue : row.publicValue;
        String text = winner == null ? "" : NaruTaskConfig.toText(winner);
        if (!text.isEmpty()) {
            @SuppressWarnings("unchecked")
            DefaultComboBoxModel<String> m = (DefaultComboBoxModel<String>) valueBox.getModel();
            if (!contains(m, text)) {
                m.insertElementAt(text, 0);
            }
            valueBox.getEditor().setItem(text);
        }
        // select the winner directly: a ButtonGroup ignores setSelected(false) on the
        // selected button, so "deselect the other one" silently does nothing
        if (row.privateValue == null && row.publicValue != null) {
            publicChoice.setSelected(true);
        } else {
            privateChoice.setSelected(true);
        }
    }

    private void updateDetail() {
        NaruConfigKey k = knownKeys.get(currentKey());
        detail.setText(k == null || k.description().isEmpty()
                ? (currentKey().isEmpty() ? " " : "Custom key: read by whatever feature asks for it by name.")
                : k.description());
    }

    private void applyValue() {
        String key = currentKey();
        if (key.isEmpty()) {
            status.setText("no key typed");
            return;
        }
        Object editorItem = valueBox.getEditor().getItem();
        String raw = editorItem == null ? "" : editorItem.toString();
        if (raw.isBlank()) {
            status.setText("empty value: use the remove button instead");
            return;
        }
        NaruEnv env = env();
        if (env == null) {
            status.setText("no project open");
            return;
        }
        try {
            env.put(key, NaruTaskConfig.parseValue(raw), currentVisibility());
            // the round-trip proof: read the merged store back and show what the file now holds
            reload();
            NOptional<NElement> back = env.get(key);
            String landed = back != null && back.isPresent() ? NaruTaskConfig.toText(back.get()) : null;
            if (raw.trim().equals(landed)) {
                status.setText(landed + " saved to " + fileLabel(currentVisibility()) + " and read back");
            } else {
                status.setText("saved, but it reads back as '" + landed + "' — check the file");
            }
        } catch (RuntimeException ex) {
            status.setText("not saved: " + ex.getMessage());
        }
    }

    private void removeKey() {
        String key = currentKey();
        NaruEnv env = env();
        if (key.isEmpty() || env == null) {
            status.setText(env == null ? "no project open" : "no key typed");
            return;
        }
        try {
            env.put(key, null, currentVisibility());
            reload();
            NOptional<NElement> back = env.get(key);
            status.setText(back != null && back.isPresent()
                    ? key + " removed from " + fileLabel(currentVisibility())
                            + ", still in effect as " + NaruTaskConfig.toText(back.get())
                    : key + " removed from " + fileLabel(currentVisibility()));
        } catch (RuntimeException ex) {
            status.setText("not removed: " + ex.getMessage());
        }
    }

    private NaruEnv env() {
        try {
            NaruWorkspace workspace = workspaceSupplier.get();
            return workspace == null || workspace.projectDirectory() == null ? null : workspace.env();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String fileLabel(NaruVisibility visibility) {
        return visibility == NaruVisibility.PUBLIC
                ? ".naru/config/env.tson"
                : ".naru/local/config/env.tson";
    }

    @Override
    public void removeNotify() {
        // the completion card lives in the window's layered pane; a pane being thrown away
        // (the settings page is rebuilt on every open) must not leave its card behind
        keyPopup.detach();
        super.removeNotify();
    }

    /**
     * Refresh the table from disk and re-collect the completion catalog off the EDT.
     * The entries are small file reads; the declared keys may walk extension classloaders,
     * so that part is deferred.
     */
    private void reload() {
        NaruWorkspace workspace = workspaceSupplier.get();
        boolean editable = workspace != null && workspace.projectDirectory() != null;
        String paths = !editable ? "" : projectPaths(workspace.projectDirectory());
        pathsCopy.setText(!editable
                ? "No project open yet — workspace settings become editable with the first session."
                : paths);
        pathsCopy.setToolTipText(paths.isEmpty() ? null : paths);
        model.setRows(rows());
        if (!editable) {
            knownKeys = Map.of();
            keyItems = List.of();
            return;
        }
        new SwingWorker<Map<String, NaruConfigKey>, Void>() {
            @Override protected Map<String, NaruConfigKey> doInBackground() {
                return collectKeys(workspace, liveSession.get());
            }

            @Override protected void done() {
                Map<String, NaruConfigKey> keys;
                try {
                    keys = get();
                } catch (Exception ex) {
                    keys = Map.of();
                }
                knownKeys = keys;
                List<String> items = new ArrayList<>(keys.keySet());
                java.util.Collections.sort(items);
                keyItems = List.copyOf(items);
                commitKey();
            }
        }.execute();
    }

    private String projectPaths(NPath projectDirectory) {
        return "Editing " + projectDirectory + " — private overrides public: "
                + fileLabel(NaruVisibility.PRIVATE) + " · " + fileLabel(NaruVisibility.PUBLIC);
    }

    private List<Row> rows() {
        NaruEnv env = env();
        if (env == null) {
            return List.of();
        }
        Map<String, NElement> pub = env.entries(NaruVisibility.PUBLIC);
        Map<String, NElement> priv = env.entries(NaruVisibility.PRIVATE);
        TreeMap<String, Row> out = new TreeMap<>();
        for (String key : pub.keySet()) {
            out.put(key, new Row(key, pub.get(key), priv.get(key)));
        }
        for (String key : priv.keySet()) {
            out.putIfAbsent(key, new Row(key, pub.get(key), priv.get(key)));
        }
        return new ArrayList<>(out.values());
    }

    /**
     * The completion catalog: what the live session's registry declares (richest: dynamic
     * model keys), plus what the workspace declares without a session, plus the keys already
     * on disk so a custom key is completed once it exists.
     */
    private static Map<String, NaruConfigKey> collectKeys(NaruWorkspace workspace, NaruSession session) {
        LinkedHashMap<String, NaruConfigKey> byKey = new LinkedHashMap<>();
        if (session != null && session.registry() != null) {
            try {
                for (NaruConfigKey k : session.registry().configKeys()) {
                    if (k != null) {
                        byKey.putIfAbsent(k.key(), k);
                    }
                }
            } catch (RuntimeException ignored) {
                // best effort: a failing provider must not empty the catalog
            }
        }
        try {
            for (NaruConfigKey k : workspace.configKeys()) {
                if (k != null) {
                    byKey.putIfAbsent(k.key(), k);
                }
            }
        } catch (RuntimeException ignored) {
        }
        try {
            NaruEnv env = workspace.env();
            if (env != null) {
                for (Map.Entry<String, NElement> e : env.entries().entrySet()) {
                    byKey.putIfAbsent(e.getKey(),
                            NaruConfigKey.of(e.getKey(), "key present in the project config"));
                }
            }
        } catch (RuntimeException ignored) {
        }
        return byKey;
    }

    private static String text(NElement value) {
        if (value == null) {
            return "";
        }
        boolean retypable = value.isString() || value.isNumber() || value.isBoolean();
        if (!retypable) {
            return NaruTaskConfig.toText(value) + " (edit in file)";
        }
        return NaruTaskConfig.toText(value);
    }

    private static final class EnvModel extends AbstractTableModel {
        private final String[] columns = {"Key", "Public", "Private"};
        private List<Row> rows = List.of();

        void setRows(List<Row> next) {
            rows = next;
            fireTableDataChanged();
        }

        Row row(int i) {
            return rows.get(i);
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int c) { return columns[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            return switch (c) {
                case 0 -> row.key;
                case 1 -> text(row.publicValue);
                default -> text(row.privateValue);
            };
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    }
}
