package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.desktop.model.ModelCallLog;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.util.BudgetText;

import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.Comparator;

/** Compact inspector: task/session facts stay in one place, with explicit scope labels. */
public final class InspectorPane extends JPanel implements NaruSessionModel.Listener, ModelCallLog.Listener {
    private final NaruSessionModel model;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextArea context = area(), budget = area(), skills = area();
    private final RequestsPane requests;
    private final ToolsPane tools;
    private long taskId = -1;
    private long skillsForTask = Long.MIN_VALUE;
    private String skillsText = "";
    private final Timer detailRefresh = new Timer(160, e -> loadDetails());
    private long detailGeneration;

    public InspectorPane(NaruSessionModel model) {
        this(model, null);
    }

    public InspectorPane(NaruSessionModel model, TaskPane tasks) {
        super(new BorderLayout()); this.model = model;
        // Put task monitoring beside the other technical views instead of below the session list.
        // Keep the live task and tool views first, ahead of the less frequently opened summaries.
        if (tasks != null) tabs.addTab("Tasks", tasks);
        tools = new ToolsPane(model.tools());
        tabs.addTab("Tools · task", tools);
        requests = new RequestsPane(model.modelCallLog());
        tabs.addTab("Requests · session", requests);
        tabs.addTab("Context · task", scroll(context));
        tabs.addTab("Budget · session", scroll(budget));
        tabs.addTab("Skills · task", scroll(skills));
        add(tabs); model.addListener(this);
        model.modelCallLog().addListener(this);
        detailRefresh.setRepeats(false);
        sessionModelChanged();
    }

    /**
     * The child strip, so the window can address one of these panels by title.
     *
     * <p>A tool card in the transcript and a row of this table describe the same call, and a jump
     * between them has to be provable from outside the class: the window's by-title selector reaches
     * in here, and a gate run photographs the child it asked for rather than whichever tab happened
     * to be selected.
     */
    public JTabbedPane tabStrip() {
        return tabs;
    }

    private static JTextArea area() { return TextAreas.readOnly(); }
    private static JScrollPane scroll(JTextArea a) { return new JScrollPane(a); }
    public void setSelectedTaskId(long id) {
        taskId = id;
        if (skillsForTask != id) skillsForTask = Long.MIN_VALUE;
        // the tools table is scoped to the selected task, and its task column is dead width there
        tools.setTaskFilter(id);
        sessionModelChanged();
    }

    /**
     * Let a permission denial in the tools table jump to the rule that caused it. The window owns the
     * tab strip, so it installs the jump; this pane only carries it down to the table.
     */
    public void setJumpToPermissions(java.util.function.Consumer<String> jump) {
        tools.setJumpToPermissions(jump);
    }

    @Override public void sessionModelChanged() {
        showBudget();
        if (model.session() == null) return;
        context.setText("Loading task and project details…");
        if (skillsForTask != taskId) {
            skillsForTask = taskId;
            skills.setText(taskId < 0 ? "Select a task to inspect available skills." : "Loading skills…");
        }
        detailRefresh.restart();
    }

    /** The budget counts calls as they arrive, and the engine records one on its own thread. */
    @Override public void modelCallsChanged() {
        showBudget();
    }

    private void showBudget() {
        ModelCallLog log = model.modelCallLog();
        budget.setText(BudgetText.render(model.tokensLabel(), log.summary(), log.calls()));
    }

    private void loadDetails() {
        NaruSession owner = model.session();
        if (owner == null) return;
        long requestedTask = taskId;
        long generation = ++detailGeneration;
        new SwingWorker<String[], Void>() {
            protected String[] doInBackground() {
                NaruTask t = null;
                if (requestedTask >= 0) try { t = owner.findTask(requestedTask).orNull(); } catch (RuntimeException ignored) { }
                if (t == null) {
                    return new String[]{"Select a live task to inspect its project context.\n\nSession project: "
                            + owner.sessionDir() + "\nSession working directory: " + owner.workingDir(),
                            "Select a task to inspect available skills."};
                }
                String contextText = "Task #" + t.id() + "\nMode: " + t.taskMode() + "\nProject: " + t.projectDir()
                        + "\nWorking directory: " + t.workingDir() + "\n\nContext messages: "
                        + safeContextSize(t) + "\nHistory messages: " + safeHistorySize(t);
                return new String[]{contextText, readSkills(t)};
            }
            protected void done() {
                if (generation != detailGeneration || owner != model.session() || requestedTask != taskId) return;
                try {
                    String[] values = get();
                    context.setText(values[0]); skillsText = values[1]; skills.setText(skillsText);
                } catch (Exception ex) {
                    context.setText("Task details are temporarily unavailable.");
                    skills.setText("Skills are temporarily unavailable.");
                }
            }
        }.execute();
    }
    private static int safeContextSize(NaruTask t) { try { return t.contextView().size(); } catch (RuntimeException e) { return 0; } }
    private static int safeHistorySize(NaruTask t) { try { return t.history().size(); } catch (RuntimeException e) { return 0; } }
    private static String readSkills(NaruTask t) {
        if (t == null) return "Select a task to inspect available skills.";
        Path p; try { p = Paths.get(t.projectDir().toString()).resolve(".naru/skills"); } catch (Exception e) { return "Skills are managed by the skills extension and project .naru/skills roots."; }
        if (!Files.isDirectory(p)) return "No project .naru/skills folder found.\n\nThe skills extension may also provide user and store roots.";
        try (var f = Files.list(p)) { StringBuilder b = new StringBuilder("Project skills · ").append(p).append("\n\n"); f.sorted(Comparator.comparing(x -> x.getFileName().toString())).forEach(x -> b.append(Files.isDirectory(x) ? "▸ " : "• ").append(x.getFileName()).append('\n')); return b.toString(); }
        catch (Exception e) { return "Could not list project skills: " + e.getMessage(); }
    }
    public void dispose() {
        detailRefresh.stop();
        detailGeneration++;
        model.removeListener(this);
        model.modelCallLog().removeListener(this);
        tools.dispose();
        requests.dispose();
    }
}
