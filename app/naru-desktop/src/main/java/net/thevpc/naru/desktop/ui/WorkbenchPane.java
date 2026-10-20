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
    private final JTextField fileSearch = new JTextField();
    private Path displayedRoot;
    private final JTextPane fileView = new JTextPane();
    private final JTextArea diffView = TextAreas.readOnly();
    private final JTextField diffSearch = new JTextField(18);
    private final DefaultListModel<String> routineRows = new DefaultListModel<>();
    private final JList<String> routines = new JList<>(routineRows);
    private final JTextArea routineEditor = TextAreas.monospace(0, 0);
    private long taskId = -1;
    private long routineLoadGeneration;

    public WorkbenchPane(NaruSessionModel model) {
        super(new BorderLayout()); this.model = model;
        tabs.addTab("Explorer", explorer()); tabs.addTab("Changes", changes()); tabs.addTab("Routines", routineEditor());
        add(tabs, BorderLayout.CENTER); refresh();
    }
    public void setSelectedTaskId(long id) { taskId = id; refresh(); }
    private Path projectRoot() { NaruTask t = selectedTask(); return path(t == null ? model.projectDir() : t.projectDir()); }

    private JSplitPane explorer() {
        tree.setRootVisible(true); tree.setShowsRootHandles(true);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected,
                    boolean expanded, boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, focus);
                Object u = value instanceof DefaultMutableTreeNode n ? n.getUserObject() : value;
                if (u instanceof Path p) {
                    boolean root = p.equals(displayedRoot);
                    setText(root ? p.toAbsolutePath().normalize().toString() : p.getFileName().toString());
                    setIcon(UIManager.getIcon(Files.isDirectory(p) ? "FileView.directoryIcon" : "FileView.fileIcon"));
                }
                return this;
            }
        });
        tree.addTreeWillExpandListener(new javax.swing.event.TreeWillExpandListener() {
            public void treeWillExpand(javax.swing.event.TreeExpansionEvent e) { populate((DefaultMutableTreeNode) e.getPath().getLastPathComponent()); }
            public void treeWillCollapse(javax.swing.event.TreeExpansionEvent e) { }
        });
        tree.addTreeSelectionListener(e -> { Object node = tree.getLastSelectedPathComponent(); Object value = node instanceof DefaultMutableTreeNode ? ((DefaultMutableTreeNode) node).getUserObject() : null; if (value instanceof Path p && Files.isRegularFile(p)) openFile(p); });
        fileView.setEditable(false); fileView.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane preview = new JScrollPane(fileView); preview.setBorder(BorderFactory.createTitledBorder("File preview"));
        JPanel navigation = new JPanel(new BorderLayout(0, 4));
        fileSearch.putClientProperty("JTextField.placeholderText", "Filter workspace files…");
        fileSearch.addActionListener(e -> searchFiles());
        fileSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { searchFiles(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { searchFiles(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { searchFiles(); }
        });
        navigation.add(fileSearch, BorderLayout.NORTH); navigation.add(new JScrollPane(tree), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navigation, preview); split.setResizeWeight(.30); return split;
    }
    private JPanel changes() {
        JPanel p = new JPanel(new BorderLayout());
        JButton refresh = new JButton("↻"); refresh.setToolTipText("Refresh Git changes"); refresh.addActionListener(e -> loadDiff());
        diffSearch.putClientProperty("JTextField.placeholderText", "Find in diff…");
        diffSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){ public void insertUpdate(javax.swing.event.DocumentEvent e){highlightDiff();} public void removeUpdate(javax.swing.event.DocumentEvent e){highlightDiff();} public void changedUpdate(javax.swing.event.DocumentEvent e){highlightDiff();} });
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4)); bar.add(refresh); bar.add(diffSearch); p.add(bar, BorderLayout.NORTH); p.add(new JScrollPane(diffView), BorderLayout.CENTER); return p;
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
        Path root = projectRoot(); if (root != null) { displayedRoot = root; DefaultMutableTreeNode n = new DefaultMutableTreeNode(root); if (Files.isDirectory(root)) n.add(new DefaultMutableTreeNode("…")); tree.setModel(new DefaultTreeModel(n)); }
        loadRoutines(s);
        loadDiff();
    }

    private void loadRoutines(NaruSession owner) {
        long generation = ++routineLoadGeneration;
        routineRows.clear();
        routineRows.addElement("Loading routines…");
        new SwingWorker<List<String>, Void>() {
            protected List<String> doInBackground() {
                List<String> names = new ArrayList<>();
                for (NaruResourceInfo routine : owner.routines()) names.add(routine.getName());
                return names;
            }
            protected void done() {
                if (generation != routineLoadGeneration || owner != model.session()) return;
                routineRows.clear();
                try {
                    List<String> names = get();
                    if (names.isEmpty()) routineRows.addElement("No routines available");
                    else names.forEach(routineRows::addElement);
                } catch (Exception ex) {
                    routineRows.addElement("Could not load routines");
                }
            }
        }.execute();
    }
    private void searchFiles() {
        Path root = projectRoot();
        if (root == null || !Files.isDirectory(root)) return;
        String query = fileSearch.getText().trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) { DefaultMutableTreeNode top = new DefaultMutableTreeNode(root); if (Files.isDirectory(root)) top.add(new DefaultMutableTreeNode("…")); tree.setModel(new DefaultTreeModel(top)); return; }
        new SwingWorker<DefaultMutableTreeNode, Void>() {
            protected DefaultMutableTreeNode doInBackground() {
                DefaultMutableTreeNode top = new DefaultMutableTreeNode(root);
                try (var paths = Files.walk(root, 12)) {
                    paths.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).contains(query))
                            .limit(500).forEach(p -> addPath(top, root, p));
                } catch (Exception ignored) { }
                return top;
            }
            protected void done() { if (root.equals(projectRoot()) && query.equals(fileSearch.getText().trim().toLowerCase(Locale.ROOT))) try { tree.setModel(new DefaultTreeModel(get())); for (int i=0;i<tree.getRowCount();i++) tree.expandRow(i); } catch (Exception ignored) { } }
        }.execute();
    }
    private static void addPath(DefaultMutableTreeNode rootNode, Path root, Path file) {
        DefaultMutableTreeNode at = rootNode; Path relative = root.relativize(file); Path walk = root;
        for (Path part : relative) {
            walk = walk.resolve(part); DefaultMutableTreeNode next = null;
            for (int i=0;i<at.getChildCount();i++) { Object u=((DefaultMutableTreeNode)at.getChildAt(i)).getUserObject(); if (walk.equals(u)) { next=(DefaultMutableTreeNode)at.getChildAt(i); break; } }
            if (next == null) { next=new DefaultMutableTreeNode(walk); at.add(next); } at=next;
        }
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
            protected String doInBackground() throws Exception {
                Set<Path> candidates = new TreeSet<>(Comparator.comparing(Path::toString));
                candidates.add(root);
                try (var paths = Files.walk(root, 10)) {
                    paths.filter(p -> p.getFileName()!=null && ".git".equals(p.getFileName().toString())).forEach(p -> candidates.add(p.getParent()));
                } catch (Exception ignored) { }
                StringBuilder all = new StringBuilder();
                Set<Path> repos = new TreeSet<>(Comparator.comparing(Path::toString));
                for (Path repo : candidates) {
                    Process probe = new ProcessBuilder("git", "-C", repo.toString(), "rev-parse", "--show-toplevel").redirectErrorStream(true).start();
                    String top = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim(); if (probe.waitFor()!=0 || top.isBlank()) continue;
                    Path canonical = Paths.get(top).toAbsolutePath().normalize(); if (!repos.add(canonical)) continue;
                    String stats = git(canonical,"diff","--stat","HEAD","--");
                    String diff = git(canonical,"diff","--no-ext-diff","--no-color","--unified=3","HEAD","--");
                    String stagedStats=git(canonical,"diff","--cached","--stat"); String staged=git(canonical,"diff","--cached","--no-ext-diff","--no-color","--unified=3");
                    all.append("\n▶ ").append(canonical).append("\n").append(stats.isBlank()?"Working tree clean\n":stats).append(stagedStats.isBlank()?"":"Staged changes:\n"+stagedStats+"\n");
                    if (!diff.isBlank()) all.append(diff).append('\n'); if (!staged.isBlank()) all.append(staged).append('\n');
                }
                return all.isEmpty()?"No Git repositories with changes found under "+root:all.toString();
            }
            private String git(Path repo,String... args) throws Exception { java.util.ArrayList<String> command=new java.util.ArrayList<>(List.of("git","-C",repo.toString())); command.addAll(List.of(args)); Process p=new ProcessBuilder(command).redirectErrorStream(true).start(); String out=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8); p.waitFor(); return out; }
            protected void done() { try { diffView.setText(get()); diffView.setCaretPosition(0); highlightDiff(); } catch (Exception e) { diffView.setText("Git diff unavailable: " + e.getMessage()); } }
        }.execute();
    }
    private void highlightDiff() {
        diffView.getHighlighter().removeAllHighlights();
        String q=diffSearch.getText().trim(); if(q.isEmpty()) return;
        String text=diffView.getText(); int start=0; int i;
        while((i=text.toLowerCase(Locale.ROOT).indexOf(q.toLowerCase(Locale.ROOT),start))>=0){ try { diffView.getHighlighter().addHighlight(i,Math.min(text.length(),i+q.length()),new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(new Color(255,220,100))); } catch (javax.swing.text.BadLocationException ignored) { return; } start=i+q.length(); }
    }
    private void loadRoutine() {
        String name = routines.getSelectedValue(); NaruTask task = selectedTask(); NaruSession s = model.session();
        if (name == null || s == null) { routineEditor.setText(""); return; } if (task == null) { routineEditor.setText("Select a task to edit this session routine."); return; }
        try { NaruRoutine r = s.routine(name, task, false).orNull(); routineEditor.setText(r == null ? "" : r.getFormattedText()); } catch (RuntimeException ex) { routineEditor.setText("Routine unavailable: " + ex.getMessage()); }
    }
    private void saveRoutine() {
        String name = routines.getSelectedValue(); NaruTask task = selectedTask(); NaruSession s = model.session(); if (name == null || task == null || s == null) return;
        try { NaruRoutine r = s.routine(name, task, true).orNull(); if (r == null) return; r.clear(); for (String line : routineEditor.getText().split("\\R", -1)) r.appendLine(line); DesktopDialog.showMessage(SwingUtilities.getWindowAncestor(this),"Routine","Routine saved."); }
        catch (RuntimeException ex) { DesktopDialog.showMessage(SwingUtilities.getWindowAncestor(this),"Routine","Could not save routine: " + ex.getMessage()); }
    }
    private NaruTask selectedTask() { NaruSession s = model.session(); if (s == null || taskId < 0) return null; try { return s.findTask(taskId).orNull(); } catch (RuntimeException e) { return null; } }
    private static Path path(NPath p) { try { return p == null ? null : Paths.get(p.toString()).toAbsolutePath().normalize(); } catch (Exception e) { return null; } }
}
