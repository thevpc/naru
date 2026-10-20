package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.desktop.model.NaruSessionModel;

import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.Comparator;

/** Compact inspector: task/session facts stay in one place, with explicit scope labels. */
public final class InspectorPane extends JPanel implements NaruSessionModel.Listener {
    private final NaruSessionModel model;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextArea context = area(), budget = area(), skills = area(), tags = area(), plans = area();
    private long taskId = -1;
    private long skillsForTask = Long.MIN_VALUE;
    private String skillsText = "";
    private final Timer detailRefresh = new Timer(160, e -> loadDetails());
    private long detailGeneration;

    public InspectorPane(NaruSessionModel model) {
        super(new BorderLayout()); this.model = model;
        tabs.addTab("Context · task", scroll(context));
        tabs.addTab("Budget · session", scroll(budget));
        tabs.addTab("Skills · task", scroll(skills));
        tabs.addTab("Tags · task", scroll(tags));
        tabs.addTab("Plans · session", scroll(plans));
        add(tabs); model.addListener(this);
        detailRefresh.setRepeats(false);
        sessionModelChanged();
    }
    private static JTextArea area() { return TextAreas.readOnly(); }
    private static JScrollPane scroll(JTextArea a) { return new JScrollPane(a); }
    public void setSelectedTaskId(long id) { taskId = id; if (skillsForTask != id) skillsForTask = Long.MIN_VALUE; sessionModelChanged(); }

    @Override public void sessionModelChanged() {
        budget.setText(model.tokensLabel() + "\n\nSession totals reflect calls observed by this desktop session.\nMessage-type percentages will appear when the budget service exposes those aggregates.");
        if (model.session() == null) return;
        context.setText("Loading task and project details…");
        if (skillsForTask != taskId) {
            skillsForTask = taskId;
            skills.setText(taskId < 0 ? "Select a task to inspect available skills." : "Loading skills…");
        }
        detailRefresh.restart();
        plans.setText("Plans are session scoped.\n\nThe desktop project does not statically depend on the optional plan extension. This panel is reserved for its active plan, items, dependencies, and progress when a runtime adapter is available.");
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
                            + owner.projectDir() + "\nSession working directory: " + owner.workingDir(),
                            "Select a task to inspect its granted tool tags.", "Select a task to inspect available skills."};
                }
                String contextText = "Task #" + t.id() + "\nMode: " + t.taskMode() + "\nProject: " + t.projectDir()
                        + "\nWorking directory: " + t.workingDir() + "\n\nContext messages: "
                        + safeContextSize(t) + "\nHistory messages: " + safeHistorySize(t);
                String tagsText;
                try { tagsText = String.join("\n", t.findToolTagNames()); }
                catch (RuntimeException ex) { tagsText = "Task tags are temporarily unavailable."; }
                return new String[]{contextText, tagsText, readSkills(t)};
            }
            protected void done() {
                if (generation != detailGeneration || owner != model.session() || requestedTask != taskId) return;
                try {
                    String[] values = get();
                    context.setText(values[0]); tags.setText(values[1]); skillsText = values[2]; skills.setText(skillsText);
                } catch (Exception ex) {
                    context.setText("Task details are temporarily unavailable.");
                    tags.setText("Task tags are temporarily unavailable.");
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
    public void dispose() { detailRefresh.stop(); detailGeneration++; model.removeListener(this); }
}
