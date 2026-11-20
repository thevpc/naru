package net.thevpc.naru.desktop.ui;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A comma-separated free-text field with a picker for the values an endpoint reports.
 *
 * <p>The {@code models} parameter is deliberately both: a registration may pin a model the
 * provider cannot enumerate (a hand-named alias, a model served by a generic endpoint), and it
 * may equally pick from what an Ollama or OpenAI-compatible endpoint lists right now. A plain
 * combo can express the first or the second, never both; a list alone cannot express a name the
 * endpoint does not know. So the text stays the value and the picker only helps fill it.
 *
 * <p>Picking merges rather than replaces: a typed name that the endpoint does not report has no
 * row in the chooser, and checking one reported model must not silently drop it.
 */
final class MultiValueField extends JPanel {

    private final JTextField text = new JTextField();
    private final JButton pick = new JButton("…");
    private List<String> candidates = List.of();

    MultiValueField(String placeholderText, String tooltip, Runnable onEdit) {
        super(new BorderLayout(4, 0));
        text.setColumns(24);
        text.putClientProperty("JTextField.placeholderText", placeholderText);
        text.setToolTipText(tooltip);
        pick.setMargin(new Insets(2, 6, 2, 6));
        pick.setFocusable(false);
        pick.setToolTipText("Pick from the models this endpoint reports");
        pick.setEnabled(false);
        pick.addActionListener(e -> choose());
        add(text, BorderLayout.CENTER);
        add(pick, BorderLayout.EAST);
        text.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onEdit.run(); }
            @Override public void removeUpdate(DocumentEvent e) { onEdit.run(); }
            @Override public void changedUpdate(DocumentEvent e) { onEdit.run(); }
        });
    }

    /** The comma-separated value as typed, trimmed. */
    String getText() {
        return text.getText() == null ? "" : text.getText().trim();
    }

    void setText(String value) {
        text.setText(value == null ? "" : value);
    }

    /** What the endpoint reports right now — the chooser's rows. */
    void setCandidates(Collection<String> values) {
        List<String> sorted = new ArrayList<>(values == null ? List.of() : values);
        sorted.removeIf(v -> v == null || v.isBlank());
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        candidates = sorted;
        pick.setEnabled(!candidates.isEmpty());
    }

    /** The chooser's rows, in the order it shows them. */
    List<String> candidates() {
        return candidates;
    }

    /**
     * Apply the chooser's answer: the checked reported models, plus any typed value the
     * endpoint does not report (which the chooser has no row for and must not erase).
     */
    void applySelection(Collection<String> selected) {
        Set<String> current = parse(getText());
        Set<String> next = new LinkedHashSet<>();
        for (String value : current) {
            if (!candidates.contains(value)) {
                next.add(value);
            }
        }
        if (selected != null) {
            next.addAll(selected);
        }
        setText(String.join(", ", next));
    }

    private void choose() {
        if (candidates.isEmpty()) {
            return;
        }
        DefaultListModel<String> model = new DefaultListModel<>();
        candidates.forEach(model::addElement);
        JList<String> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setVisibleRowCount(Math.min(10, Math.max(3, candidates.size())));
        Set<String> current = parse(getText());
        List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (current.contains(candidates.get(i))) {
                selected.add(i);
            }
        }
        int[] indexes = new int[selected.size()];
        for (int i = 0; i < indexes.length; i++) {
            indexes[i] = selected.get(i);
        }
        list.setSelectedIndices(indexes);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        scroll.setPreferredSize(new Dimension(320, Math.min(360, 24 + candidates.size() * 18)));
        int answer = JOptionPane.showConfirmDialog(this, scroll, "Models this endpoint reports",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        List<String> chosen = new ArrayList<>(list.getSelectedValuesList());
        applySelection(chosen);
    }

    private static Set<String> parse(String value) {
        Set<String> out = new LinkedHashSet<>();
        if (value == null) {
            return out;
        }
        for (String part : value.split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) {
                out.add(item);
            }
        }
        return out;
    }
}
