package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.desktop.diff.DiffModel.FileDiff;
import net.thevpc.naru.desktop.diff.DiffModel.Kind;
import net.thevpc.naru.desktop.diff.DiffModel.Line;
import net.thevpc.naru.desktop.diff.DiffModel.Row;
import net.thevpc.naru.desktop.diff.DiffModel.Side;
import net.thevpc.naru.desktop.diff.UnifiedDiffParser;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Changes tab: a per-file summary with line counts on the left, and a meld-style two-column
 * side-by-side diff for the selected file on the right (a unified view is one keystroke away).
 * <p>
 * The rows come from git's own unified output rather than a re-diff of the files: the counts, line
 * numbers and hunk boundaries are then exactly what git reported, and the only thing computed here
 * is the alignment between the two columns.
 */
public final class DiffPanel extends JPanel {

    /** One row of the summary list: a repository heading, or a file with its counts. */
    private sealed interface SummaryRow {
        record Repo(String path) implements SummaryRow {
        }

        record File(Path repo, String path, int add, int del, boolean staged, boolean unstaged,
                    String note) implements SummaryRow {
        }
    }

    private static final Color BG = textBackground();
    private static final Color ADD_BG = UiPalette.mix(BG, UiPalette.LIVE, 0.16f);
    private static final Color DEL_BG = UiPalette.mix(BG, UiPalette.ATTENTION, 0.16f);
    private static final Color HUNK_BG = UiPalette.mix(BG, UiPalette.IDLE, 0.16f);
    private static final Color SPACER_BG = UiPalette.mix(BG, Color.BLACK, 0.05f);
    private static final Color HUNK_FG = UiPalette.readable(UiPalette.IDLE);

    private final DefaultListModel<SummaryRow> rows = new DefaultListModel<>();
    private final JList<SummaryRow> summary = new JList<>(rows);
    private final JTextPane oldPane = diffPane(false);
    private final JTextPane newPane = diffPane(false);
    private final JTextPane unifiedPane = diffPane(true);
    private final JScrollPane oldScroll = scrolling(oldPane, false);
    private final JScrollPane newScroll = scrolling(newPane, false);
    private final JLabel detailHeader = new JLabel(" ");
    private final JPanel detail = new JPanel(new BorderLayout());
    private final JPanel cards = new JPanel(new BorderLayout());
    private final JPanel sideCard;
    private final JScrollPane unifiedCard = scrolling(unifiedPane, true);
    private final JButton sideButton = GlyphButton.of("⊞", "Side by side", true);
    private final JButton unifiedButton = GlyphButton.of("☰", "Unified", true);
    private final Map<Path, Map<String, FileDiff[]>> byRepo = new LinkedHashMap<>();
    private String query = "";
    private SummaryRow.File selected;
    private boolean sideBySideMode = true;

    public DiffPanel() {
        super(new BorderLayout());
        UiFonts.apply(summary, UiFonts.SECONDARY, Font.PLAIN);
        UiFonts.apply(detailHeader, UiFonts.SECONDARY, Font.PLAIN);
        summary.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        summary.setCellRenderer(new SummaryCell());
        summary.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && summary.getSelectedValue() instanceof SummaryRow.File f) {
                selected = f;
                showDetails();
            }
        });
        summary.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                // A repo heading is not selectable; a click on one jumps to its first file.
                int at = summary.locationToIndex(e.getPoint());
                if (at >= 0 && rows.getElementAt(at) instanceof SummaryRow.Repo) {
                    for (int i = at + 1; i < rows.size(); i++) {
                        if (rows.getElementAt(i) instanceof SummaryRow.File) {
                            summary.setSelectedIndex(i);
                            break;
                        }
                    }
                }
            }
        });
        java.awt.event.AdjustmentListener sync = a -> {
            if (a.getSource() == oldScroll.getVerticalScrollBar()) {
                newScroll.getVerticalScrollBar().setValue(a.getValue());
            } else if (a.getSource() == newScroll.getVerticalScrollBar()) {
                oldScroll.getVerticalScrollBar().setValue(a.getValue());
            } else if (a.getSource() == oldScroll.getHorizontalScrollBar()) {
                newScroll.getHorizontalScrollBar().setValue(a.getValue());
            } else if (a.getSource() == newScroll.getHorizontalScrollBar()) {
                oldScroll.getHorizontalScrollBar().setValue(a.getValue());
            }
        };
        oldScroll.getVerticalScrollBar().addAdjustmentListener(sync);
        newScroll.getVerticalScrollBar().addAdjustmentListener(sync);
        oldScroll.getHorizontalScrollBar().addAdjustmentListener(sync);
        newScroll.getHorizontalScrollBar().addAdjustmentListener(sync);
        JSplitPane pair = SplitPanes.horizontal(oldScroll, newScroll);
        pair.setResizeWeight(.5);
        // A ratio location is remembered and re-applied at the first real layout; a pixel
        // location set now would be clamped to the still-zero width.
        pair.setDividerLocation(0.5);
        JPanel columnTags = new JPanel(new BorderLayout());
        columnTags.add(columnTag("Before", SwingConstants.LEFT), BorderLayout.WEST);
        columnTags.add(columnTag("After", SwingConstants.RIGHT), BorderLayout.EAST);
        sideCard = new JPanel(new BorderLayout());
        sideCard.add(columnTags, BorderLayout.NORTH);
        sideCard.add(pair, BorderLayout.CENTER);
        sideButton.setSelected(true);
        sideButton.addActionListener(e -> setMode(true));
        unifiedButton.addActionListener(e -> setMode(false));
        JPanel toggles = new JPanel();
        toggles.add(sideButton);
        toggles.add(unifiedButton);
        JPanel head = new JPanel(new BorderLayout(6, 0));
        head.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        head.add(detailHeader, BorderLayout.CENTER);
        head.add(toggles, BorderLayout.EAST);
        detail.add(head, BorderLayout.NORTH);
        detail.add(cards, BorderLayout.CENTER);
        cards.add(sideCard, BorderLayout.CENTER);
        // The list is stacked above the diff instead of beside it. Beside it, this panel had to fit
        // a file column, a before column and an after column across one workbench tab — three
        // columns in a pane that is a third of the window — and the diff was the part that lost.
        JSplitPane split = SplitPanes.vertical(scrolling(summary, true), detail);
        split.setResizeWeight(0.35);
        // A ratio: the pixel equivalent would be clamped against the panel's still-zero height.
        split.setDividerLocation(0.35);
        add(split, BorderLayout.CENTER);
        watchFonts();
    }

    private void setMode(boolean side) {
        if (sideBySideMode == side) return;
        sideBySideMode = side;
        sideButton.setSelected(side);
        unifiedButton.setSelected(!side);
        cards.removeAll();
        cards.add(side ? sideCard : unifiedCard, BorderLayout.CENTER);
        cards.revalidate();
        showDetails();
    }

    /**
     * A diff text pane. The two columns are unwrapped on purpose: a row that wrapped to a different
     * number of visual lines in each pane would break the alignment the whole view is built on, so
     * long lines scroll sideways instead.
     */
    private static JTextPane diffPane(boolean wrap) {
        JTextPane pane = new JTextPane() {
            @Override public boolean getScrollableTracksViewportWidth() {
                if (wrap) return super.getScrollableTracksViewportWidth();
                Component parent = getParent();
                return parent == null || getUI().getPreferredSize(this).width <= parent.getSize().width;
            }
        };
        pane.setEditable(false);
        UiFonts.applyMono(pane, UiFonts.BODY, Font.PLAIN);
        pane.setBackground(BG);
        return pane;
    }

    private void watchFonts() {
        // A scale or theme pass moves the component font; a styled document keeps its own copy,
        // so the diff is re-rendered from the model once the new size has landed.
        for (JTextPane pane : List.of(oldPane, newPane, unifiedPane)) {
            pane.addPropertyChangeListener("font", e -> SwingUtilities.invokeLater(() -> {
                if (selected != null) showDetails();
            }));
        }
    }

    private static JScrollPane scrolling(Component view, boolean wrapWidth) {
        JScrollPane s = new JScrollPane(view);
        s.setHorizontalScrollBarPolicy(wrapWidth ? ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                : ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        s.setBorder(BorderFactory.createLineBorder(UiPalette.border()));
        return s;
    }

    private static Color textBackground() {
        Color c = UIManager.getColor("TextArea.background");
        return c != null ? c : Color.WHITE;
    }

    private JLabel columnTag(String text, int align) {
        JLabel l = new JLabel(text, align);
        l.setFont(UiFonts.font(UiFonts.CAPTION, Font.PLAIN));
        l.setForeground(UiPalette.muted());
        l.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        return l;
    }

    /** Merge one repository's unstaged and staged unified diffs into the summary. */
    public void load(Path repo, String unstagedText, String stagedText) {
        Map<String, FileDiff[]> map = new LinkedHashMap<>();
        for (FileDiff d : UnifiedDiffParser.parse(unstagedText)) {
            map.computeIfAbsent(displayPath(d), k -> new FileDiff[2])[0] = d;
        }
        for (FileDiff d : UnifiedDiffParser.parse(stagedText)) {
            map.computeIfAbsent(displayPath(d), k -> new FileDiff[2])[1] = d;
        }
        byRepo.put(repo, map);
        rebuildSummary();
    }

    public void clearAll() {
        byRepo.clear();
        rebuildSummary();
    }

    /** True when the summary currently shows at least one file. */
    public boolean hasFiles() {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i) instanceof SummaryRow.File) return true;
        return false;
    }

    private void rebuildSummary() {
        SummaryRow.File keep = selected;
        rows.clear();
        selected = null;
        for (Map.Entry<Path, Map<String, FileDiff[]>> repo : byRepo.entrySet()) {
            rows.addElement(new SummaryRow.Repo(repo.getKey().toString()));
            for (Map.Entry<String, FileDiff[]> e : repo.getValue().entrySet()) {
                FileDiff u = e.getValue()[0], s = e.getValue()[1];
                rows.addElement(new SummaryRow.File(repo.getKey(), e.getKey(),
                        (u != null ? u.added() : 0) + (s != null ? s.added() : 0),
                        (u != null ? u.deleted() : 0) + (s != null ? s.deleted() : 0),
                        s != null, u != null, note(u, s)));
            }
        }
        if (rows.isEmpty()) {
            rows.addElement(new SummaryRow.Repo("No Git repository with changes under the project"));
            showEmptyDetail();
            return;
        }
        int at = keep == null ? -1 : indexOfFile(keep.repo(), keep.path());
        if (at >= 0) {
            summary.setSelectedIndex(at);
        } else if (rows.size() > 1) {
            summary.setSelectedIndex(1);
        } else {
            showEmptyDetail();
        }
    }

    private int indexOfFile(Path repo, String path) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof SummaryRow.File f && f.repo().equals(repo) && f.path().equals(path)) return i;
        }
        return -1;
    }

    private void showEmptyDetail() {
        selected = null;
        detailHeader.setText(" ");
        setText(oldPane, " ");
        setText(newPane, " ");
        setText(unifiedPane, " ");
    }

    private static void setText(JTextPane pane, String text) {
        try {
            StyledDocument doc = pane.getStyledDocument();
            doc.remove(0, doc.getLength());
            doc.insertString(0, text, rootStyle());
        } catch (BadLocationException ignored) {
        }
    }

    private static String displayPath(FileDiff d) {
        return "?".equals(d.newPath) ? d.oldPath : d.newPath;
    }

    /** What a change is when it carries no lines at all: a rename, a mode flip, a binary blob. */
    private static String note(FileDiff... diffs) {
        for (FileDiff d : diffs) {
            if (d == null) continue;
            if (d.binary) return "binary";
            if (d.renamed()) return "renamed";
            for (String h : d.headers)
                if (h.startsWith("old mode") || h.startsWith("new mode")) return "mode change";
        }
        return null;
    }

    private void showDetails() {
        if (selected == null) return;
        FileDiff u = file(selected, false), s = file(selected, true);
        detailHeader.setText(headerHtml(selected, title(u, s)));
        if (sideBySideMode) renderSideBySide(s, u);
        else renderUnified(s, u);
        applyQuery();
        oldPane.setCaretPosition(0);
        newPane.setCaretPosition(0);
        unifiedPane.setCaretPosition(0);
    }

    /** A rename is named by both of its paths; anything else by the one it lives at. */
    private static String title(FileDiff... diffs) {
        for (FileDiff d : diffs) if (d != null && d.renamed()) return d.oldPath + " → " + d.newPath;
        for (FileDiff d : diffs) if (d != null) return "?".equals(d.newPath) ? d.oldPath : d.newPath;
        return "?";
    }

    private FileDiff file(SummaryRow.File f, boolean staged) {
        FileDiff[] pair = byRepo.getOrDefault(f.repo(), Map.of()).get(f.path());
        return pair == null ? null : pair[staged ? 1 : 0];
    }

    private static String headerHtml(SummaryRow.File f, String title) {
        String state = (f.staged() && f.unstaged()) ? "staged + working tree"
                : f.staged() ? "staged" : "working tree";
        String muted = mutedHtmlColor();
        String note = f.note() == null ? "" : "<font color='" + muted + "'>(" + f.note() + ")</font> ";
        String counts = f.note() != null && f.add() + f.del() == 0
                ? note
                : "<font color='#" + hex(UiPalette.LIVE) + ">+" + f.add() + "</font> "
                        + "<font color='#" + hex(UiPalette.ATTENTION) + "'>&#8722;" + f.del() + "</font> " + note;
        return "<html><b>" + escape(title) + "</b>  " + counts
                + "<font color='" + muted + "'>" + state + "</font></html>";
    }

    private static String mutedHtmlColor() {
        return "#" + hex(UiPalette.muted());
    }

    private static String hex(Color c) {
        return String.format(Locale.ROOT, "%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private void renderSideBySide(FileDiff staged, FileDiff unstaged) {
        List<Row> all = new ArrayList<>();
        if (staged != null && !staged.binary) all.addAll(staged.sideBySide());
        if (unstaged != null && !unstaged.binary) all.addAll(unstaged.sideBySide());
        if (all.isEmpty()) {
            String note = note(staged, unstaged);
            setText(oldPane, (note == null ? "No textual diff" : capitalise(note) + " — no textual diff") + ".");
            setText(newPane, " ");
            return;
        }
        fill(oldPane, doc -> {
            for (Row row : all) appendSide(doc, leftSide(row));
        });
        fill(newPane, doc -> {
            for (Row row : all) appendSide(doc, rightSide(row));
        });
    }

    private static String capitalise(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static Side leftSide(Row row) {
        return row instanceof Row.Paired p ? p.shared() : ((Row.Changed) row).left();
    }

    private static Side rightSide(Row row) {
        return row instanceof Row.Paired p ? p.shared() : ((Row.Changed) row).right();
    }

    private void renderUnified(FileDiff staged, FileDiff unstaged) {
        List<Line> lines = new ArrayList<>();
        if (staged != null && !staged.binary) lines.addAll(staged.allLines());
        if (unstaged != null && !unstaged.binary) lines.addAll(unstaged.allLines());
        if (lines.isEmpty()) {
            String note = note(staged, unstaged);
            setText(unifiedPane, (note == null ? "No textual diff" : capitalise(note) + " — no textual diff") + ".");
            return;
        }
        fill(unifiedPane, doc -> {
            for (Line l : lines) appendUnifiedLine(doc, l);
        });
    }

    private interface DocTask {
        void run(StyledDocument doc) throws BadLocationException;
    }

    private static void fill(JTextPane pane, DocTask task) {
        StyledDocument doc = pane.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
            task.run(doc);
        } catch (BadLocationException ignored) {
        }
    }

    private static void appendSide(StyledDocument doc, Side side) throws BadLocationException {
        String line = " \n";
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setBackground(a, BG);
        if (side == null) {
            line = "\n";
        } else if (side == Side.BLANK) {
            line = " \n";
            StyleConstants.setBackground(a, SPACER_BG);
        } else {
            line = pad(Kind.HUNK == side.kind() ? "" : side.number(), 5) + " " + side.text() + "\n";
            switch (side.kind()) {
                case HUNK -> {
                    StyleConstants.setBackground(a, HUNK_BG);
                    StyleConstants.setForeground(a, HUNK_FG);
                }
                case ADDED -> StyleConstants.setBackground(a, ADD_BG);
                case DELETED -> StyleConstants.setBackground(a, DEL_BG);
                case CONTEXT -> { }
            }
        }
        doc.insertString(doc.getLength(), line, a);
    }

    private static void appendUnifiedLine(StyledDocument doc, Line l) throws BadLocationException {
        char marker = switch (l.kind()) {
            case ADDED -> '+';
            case DELETED -> '-';
            case HUNK -> '@';
            case CONTEXT -> ' ';
        };
        String numbers = l.kind() == Kind.HUNK ? "          "
                : pad(Integer.toString(l.oldNumber()), 5) + " " + pad(Integer.toString(l.newNumber()), 5) + " ";
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setBackground(a, switch (l.kind()) {
            case ADDED -> ADD_BG;
            case DELETED -> DEL_BG;
            case HUNK -> HUNK_BG;
            case CONTEXT -> BG;
        });
        if (l.kind() == Kind.HUNK) StyleConstants.setForeground(a, HUNK_FG);
        doc.insertString(doc.getLength(),
                numbers + (l.kind() == Kind.HUNK ? l.text() : marker + " " + l.text()) + "\n", a);
    }

    private static SimpleAttributeSet rootStyle() {
        SimpleAttributeSet a = new SimpleAttributeSet();
        Font font = UiFonts.monoFont(UiFonts.BODY, Font.PLAIN);
        StyleConstants.setFontFamily(a, font.getFamily());
        StyleConstants.setFontSize(a, font.getSize());
        StyleConstants.setBackground(a, BG);
        Color fg = UIManager.getColor("TextArea.foreground");
        StyleConstants.setForeground(a, fg != null ? fg : Color.BLACK);
        return a;
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : " ".repeat(width - s.length()) + s;
    }

    /** Case-insensitive search across the visible diff panes. */
    public void setQuery(String q) {
        query = q == null ? "" : q.trim();
        applyQuery();
    }

    private void applyQuery() {
        Color mark = new Color(255, 220, 100);
        for (JTextPane pane : List.of(oldPane, newPane, unifiedPane)) {
            pane.getHighlighter().removeAllHighlights();
            if (query.isEmpty()) continue;
            String text = pane.getText().toLowerCase(Locale.ROOT);
            String needle = query.toLowerCase(Locale.ROOT);
            int from = 0, i;
            while ((i = text.indexOf(needle, from)) >= 0) {
                try {
                    pane.getHighlighter().addHighlight(i, i + needle.length(),
                            new DefaultHighlighter.DefaultHighlightPainter(mark));
                } catch (BadLocationException stop) {
                    break;
                }
                from = i + needle.length();
            }
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Name on the left, counts and state on the right; HTML so the counts carry the state hues. */
    private final class SummaryCell implements ListCellRenderer<SummaryRow> {
        private final JPanel shell = new JPanel(new BorderLayout(8, 0));
        private final JLabel name = new JLabel();
        private final JLabel counts = new JLabel();

        SummaryCell() {
            counts.setHorizontalAlignment(SwingConstants.RIGHT);
            shell.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            shell.add(name, BorderLayout.CENTER);
            shell.add(counts, BorderLayout.EAST);
            // The HTML labels report their full text as preferred width, which would blow the list
            // open; a fixed em-width keeps the panel's own preferred width modest. Cells still
            // stretch to whatever width the row is given.
            FontMetrics fm = shell.getFontMetrics(name.getFont());
            shell.setPreferredSize(new Dimension(fm.charWidth('0') * 40, fm.getHeight() + 6));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends SummaryRow> list, SummaryRow value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            name.setFont(list.getFont());
            counts.setFont(list.getFont());
            shell.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            name.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            if (value instanceof SummaryRow.Repo r) {
                name.setText("<html><i>" + escape(r.path()) + "</i></html>");
                name.setForeground(isSelected ? list.getSelectionForeground() : UiPalette.muted());
                counts.setText(" ");
                return shell;
            }
            SummaryRow.File f = (SummaryRow.File) value;
            int slash = f.path().lastIndexOf('/');
            String dir = slash < 0 ? "" : f.path().substring(0, slash + 1);
            String base = slash < 0 ? f.path() : f.path().substring(slash + 1);
            String mutedColor = isSelected ? "#" + hex(list.getSelectionForeground()) : mutedHtmlColor();
            String addColor = isSelected ? "#" + hex(list.getSelectionForeground()) : "#" + hex(UiPalette.LIVE);
            String delColor = isSelected ? "#" + hex(list.getSelectionForeground()) : "#" + hex(UiPalette.ATTENTION);
            name.setText("<html><font color='" + mutedColor + "'>" + escape(dir) + "</font>" + escape(base) + "</html>");
            String states = (f.staged() ? "S" : "") + (f.unstaged() ? "W" : "");
            boolean quiet = f.note() != null && f.add() + f.del() == 0;
            counts.setText("<html>" + (quiet
                    ? "<font color='" + mutedColor + "'>" + f.note() + "</font>"
                    : "<font color='" + addColor + "'>+" + f.add() + "</font> <font color='"
                            + delColor + "'>&#8722;" + f.del() + "</font>") + " <font color='"
                            + mutedColor + "'>" + states + "</font></html>");
            shell.getAccessibleContext().setAccessibleName(
                    f.path() + ": " + (quiet ? f.note() : "+" + f.add() + " −" + f.del()) + " " + states);
            return shell;
        }
    }
}
