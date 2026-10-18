package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.nuts.io.NPath;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.tree.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Project tools live together in one tool window so they do not compete with the task rail. */
public final class WorkbenchPane extends JPanel {
    private final NaruSessionModel model;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTree tree = new JTree(new DefaultMutableTreeNode("Project"));
    private final JTextPane fileView = new JTextPane();
    private final JTextArea diffView = TextAreas.readOnly();
    private final DefaultListModel<String> routineRows = new DefaultListModel<>();
    private final JList<String> routines = new JList<>(routineRows);
    private final JTextArea routineEditor = TextAreas.monospace(0, 0);
    private long taskId = -1;

    public WorkbenchPane(NaruSessionModel model) {
        super(new BorderLayout()); this.model = model;
        tabs.addTab("Explorer", explorer()); tabs.addTab("Changes", changes()); tabs.addTab("Routines", routineEditor());
        add(tabs, BorderLayout.CENTER); refresh();
    }
    public void setSelectedTaskId(long id) { taskId = id; refresh(); }
    private Path projectRoot() { NaruTask t = selectedTask(); return path(t == null ? model.projectDir() : t.projectDir()); }

    private JSplitPane explorer() {
        tree.setRootVisible(true); tree.setShowsRootHandles(true);
        tree.addTreeWillExpandListener(new javax.swing.event.TreeWillExpandListener() {
            public void treeWillExpand(javax.swing.event.TreeExpansionEvent e) { populate((DefaultMutableTreeNode) e.getPath().getLastPathComponent()); }
            public void treeWillCollapse(javax.swing.event.TreeExpansionEvent e) { }
        });
        tree.addTreeSelectionListener(e -> { Object node = tree.getLastSelectedPathComponent(); Object value = node instanceof DefaultMutableTreeNode ? ((DefaultMutableTreeNode) node).getUserObject() : null; if (value instanceof Path p && Files.isRegularFile(p)) openFile(p); });
        fileView.setEditable(false); fileView.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane preview = new JScrollPane(fileView); preview.setBorder(BorderFactory.createTitledBorder("File preview"));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(tree), preview); split.setResizeWeight(.30); return split;
    }
    private JPanel changes() {
        JPanel p = new JPanel(new BorderLayout());
        JButton refresh = new JButton("Refresh diff"); refresh.addActionListener(e -> loadDiff()); p.add(refresh, BorderLayout.NORTH); p.add(new JScrollPane(diffView), BorderLayout.CENTER); return p;
    }
    private JPanel routineEditor() {
        JPanel p = new JPanel(new BorderLayout()); routines.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        routines.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) loadRoutine(); });
        JButton save = new JButton("Save routine"); save.addActionListener(e -> saveRoutine());
        JPanel editor = new JPanel(new BorderLayout()); editor.add(new JScrollPane(routineEditor), BorderLayout.CENTER); editor.add(save, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(routines), editor); split.setResizeWeight(.30); p.add(split); return p;
    }
    private void refresh() {
        NaruSession s = model.session(); if (s == null) return;
        Path root = projectRoot(); if (root != null) { DefaultMutableTreeNode n = new DefaultMutableTreeNode(root); if (Files.isDirectory(root)) n.add(new DefaultMutableTreeNode("…")); tree.setModel(new DefaultTreeModel(n)); }
        routineRows.clear(); try { for (NaruResourceInfo r : s.routines()) routineRows.addElement(r.getName()); } catch (RuntimeException ignored) { }
        loadDiff();
    }
    private void populate(DefaultMutableTreeNode node) {
        Object value = node.getUserObject(); if (!(value instanceof Path dir) || !Files.isDirectory(dir)) return;
        if (node.getChildCount() == 1 && "…".equals(String.valueOf(((DefaultMutableTreeNode) node.getChildAt(0)).getUserObject()))) node.removeAllChildren(); else if (node.getChildCount() > 0) return;
        try (var paths = Files.list(dir)) { paths.sorted(Comparator.comparing((Path x) -> !Files.isDirectory(x)).thenComparing(x -> x.getFileName().toString(), String.CASE_INSENSITIVE_ORDER)).limit(400).forEach(x -> { DefaultMutableTreeNode child = new DefaultMutableTreeNode(x); if (Files.isDirectory(x)) child.add(new DefaultMutableTreeNode("…")); node.add(child); }); } catch (Exception ignored) { }
        ((DefaultTreeModel) tree.getModel()).nodeStructureChanged(node);
    }
    private void openFile(Path p) { try { String text = Files.readString(p, StandardCharsets.UTF_8); fileView.setText(text); colorize(text); } catch (Exception ex) { fileView.setText("Unable to read file: " + ex.getMessage()); } }
    private void colorize(String text) {
        StyledDocument d = fileView.getStyledDocument(); StyleContext sc = StyleContext.getDefaultStyleContext(); d.setCharacterAttributes(0, d.getLength(), sc.getStyle(StyleContext.DEFAULT_STYLE), true);
        Style kw = fileView.addStyle("kw", null), str = fileView.addStyle("str", null), comment = fileView.addStyle("comment", null);
        StyleConstants.setForeground(kw, new Color(130, 70, 180)); StyleConstants.setBold(kw, true); StyleConstants.setForeground(str, new Color(40, 130, 80)); StyleConstants.setForeground(comment, Color.GRAY);
        apply(d, text, "\\b(class|interface|enum|public|private|protected|static|final|void|int|long|boolean|if|else|for|while|return|new|import|package|try|catch|throw|extends|implements|var)\\b", kw);
        apply(d, text, "\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'", str); apply(d, text, "//[^\\n]*|/\\*[\\s\\S]*?\\*/", comment);
    }
    private void apply(StyledDocument d, String text, String regex, Style style) { Matcher m = Pattern.compile(regex).matcher(text); while (m.find()) d.setCharacterAttributes(m.start(), m.end() - m.start(), style, false); }
    private void loadDiff() {
        Path root = projectRoot(); if (root == null) { diffView.setText("Select a task or project directory to inspect its Git diff."); return; }
        diffView.setText("Loading project diff…");
        new SwingWorker<String, Void>() {
            protected String doInBackground() throws Exception { Process p = new ProcessBuilder("git", "-C", root.toString(), "diff", "--no-ext-diff", "HEAD", "--").redirectErrorStream(true).start(); String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8); int code = p.waitFor(); return code == 0 ? (out.isBlank() ? "No changes in the working tree" : out) : "Git diff unavailable (is this a Git repository?)\n" + out; }
            protected void done() { try { diffView.setText(get()); diffView.setCaretPosition(0); } catch (Exception e) { diffView.setText("Git diff unavailable: " + e.getMessage()); } }
        }.execute();
    }
    private void loadRoutine() {
        String name = routines.getSelectedValue(); NaruTask task = selectedTask(); NaruSession s = model.session();
        if (name == null || s == null) { routineEditor.setText(""); return; } if (task == null) { routineEditor.setText("Select a task to edit this session routine."); return; }
        try { NaruRoutine r = s.routine(name, task, false).orNull(); routineEditor.setText(r == null ? "" : r.getFormattedText()); } catch (RuntimeException ex) { routineEditor.setText("Routine unavailable: " + ex.getMessage()); }
    }
    private void saveRoutine() {
        String name = routines.getSelectedValue(); NaruTask task = selectedTask(); NaruSession s = model.session(); if (name == null || task == null || s == null) return;
        try { NaruRoutine r = s.routine(name, task, true).orNull(); if (r == null) return; r.clear(); for (String line : routineEditor.getText().split("\\R", -1)) r.appendLine(line); JOptionPane.showMessageDialog(this, "Routine saved."); }
        catch (RuntimeException ex) { JOptionPane.showMessageDialog(this, "Could not save routine: " + ex.getMessage(), "Routine", JOptionPane.ERROR_MESSAGE); }
    }
    private NaruTask selectedTask() { NaruSession s = model.session(); if (s == null || taskId < 0) return null; try { return s.findTask(taskId).orNull(); } catch (RuntimeException e) { return null; } }
    private static Path path(NPath p) { try { return p == null ? null : Paths.get(p.toString()).toAbsolutePath().normalize(); } catch (Exception e) { return null; } }
}
