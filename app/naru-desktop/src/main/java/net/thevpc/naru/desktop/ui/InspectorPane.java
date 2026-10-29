package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruModelCallListener;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.nuts.time.NDuration;

import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Compact inspector: task/session facts stay in one place, with explicit scope labels. */
public final class InspectorPane extends JPanel implements NaruSessionModel.Listener, NaruModelCallListener {
    private final NaruSessionModel model;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextArea context = area(), budget = area(), skills = area(), tags = area(), plans = area(), requests = area();
    private long taskId = -1;
    private long skillsForTask = Long.MIN_VALUE;
    private String skillsText = "";
    private final Timer detailRefresh = new Timer(160, e -> loadDetails());
    private long detailGeneration;
    private final List<ModelCallRecord> callRecords = new ArrayList<>();
    private boolean listenerRegistered = false;
    
    private static class ModelCallRecord {
        final long taskId;
        final NaruModelKey model;
        final NaruModelRequest request;
        final NaruResponse response;
        final NDuration duration;
        final long timestamp;
        
        ModelCallRecord(long taskId, NaruModelKey model, NaruModelRequest request, NaruResponse response, NDuration duration) {
            this.taskId = taskId;
            this.model = model;
            this.request = request;
            this.response = response;
            this.duration = duration;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public InspectorPane(NaruSessionModel model) {
        super(new BorderLayout()); this.model = model;
        tabs.addTab("Context · task", scroll(context));
        tabs.addTab("Budget · session", scroll(budget));
        tabs.addTab("Skills · task", scroll(skills));
        tabs.addTab("Tags · task", scroll(tags));
        tabs.addTab("Plans · session", scroll(plans));
        tabs.addTab("Requests · session", scroll(requests));
        add(tabs); model.addListener(this);
        detailRefresh.setRepeats(false);
        sessionModelChanged();
    }
    
    @Override
    public void onModelCall(NaruTask task, NaruModelKey model, NaruModelRequest request, NaruResponse response, NDuration duration) {
        callRecords.add(new ModelCallRecord(task.id(), model, request, response, duration));
        // Update UI on EDT
        SwingUtilities.invokeLater(this::refreshRequestsView);
    }

    private void refreshRequestsView() {
        if (callRecords.isEmpty()) {
            requests.setText("No model calls recorded yet.\n\nModel calls will appear here as they happen.");
            return;
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("Model Calls (").append(callRecords.size()).append(" total)\n");
        sb.append("==========================================\n\n");
        
        for (int i = 0; i < callRecords.size(); i++) {
            ModelCallRecord rec = callRecords.get(i);
            sb.append("Call #").append(i + 1).append("\n");
            sb.append("Task: ").append(rec.taskId).append("\n");
            sb.append("Model: ").append(rec.model == null ? "unknown" : rec.model.provider() + "/" + rec.model.model()).append("\n");
            sb.append("Duration: ").append(rec.duration == null ? "unknown" : rec.duration.toString()).append("\n");
            sb.append("Timestamp: ").append(java.time.Instant.ofEpochMilli(rec.timestamp)).append("\n");
            
            // Request summary
            sb.append("\n--- REQUEST ---\n");
            sb.append("Messages: ").append(rec.request.messages().size()).append("\n");
            for (int j = 0; j < rec.request.messages().size(); j++) {
                var msg = rec.request.messages().get(j);
                String role = msg.getRole() != null ? msg.getRole().name() : "unknown";
                String content = msg.getFilteredContent() != null ? msg.getFilteredContent() : "";
                if (content.length() > 200) content = content.substring(0, 200) + "...";
                content = content.replace("\n", " \\n ");
                sb.append("  [").append(j).append("] ").append(role).append(": ").append(content).append("\n");
            }
            sb.append("Tools: ").append(rec.request.tools().size()).append("\n");
            for (var tool : rec.request.tools()) {
                sb.append("  - ").append(tool.getName()).append(": ").append(tool.getDescription() != null ? tool.getDescription() : "").append("\n");
            }
            
            // Response summary
            sb.append("\n--- RESPONSE ---\n");
            if (rec.response.getMessage() != null) {
                var msg = rec.response.getMessage();
                String role = msg.getRole() != null ? msg.getRole().name() : "unknown";
                String content = msg.getFilteredContent() != null ? msg.getFilteredContent() : "";
                if (content.length() > 500) content = content.substring(0, 500) + "...";
                content = content.replace("\n", " \\n ");
                sb.append("Role: ").append(role).append("\n");
                sb.append("Content: ").append(content).append("\n");
                if (msg.hasToolCalls()) {
                    sb.append("Tool Calls: ").append(msg.getToolCalls().size()).append("\n");
                    for (var tc : msg.getToolCalls()) {
                        sb.append("  - ").append(tc.getName()).append(" (").append(tc.getId()).append(")\n");
                    }
                }
            }
            sb.append("Done: ").append(rec.response.isDone()).append("\n");
            sb.append("Stop Reason: ").append(rec.response.getStopReason() != null ? rec.response.getStopReason() : "none").append("\n");
            sb.append("Tokens - Prompt: ").append(rec.response.getPromptTokens()).append(", Completion: ").append(rec.response.getEvalTokens()).append(", Total: ").append(rec.response.getTotalTokens()).append("\n");
            if (rec.response.hasCacheAccounting()) {
                sb.append("Cache - Write: ").append(rec.response.getCacheWriteTokens()).append(", Read: ").append(rec.response.getCacheReadTokens()).append("\n");
            }
            
            sb.append("\n==========================================\n\n");
        }
        
        requests.setText(sb.toString());
        requests.setCaretPosition(0);
    }

    private static JTextArea area() { return TextAreas.readOnly(); }
    private static JScrollPane scroll(JTextArea a) { return new JScrollPane(a); }
    public void setSelectedTaskId(long id) { taskId = id; if (skillsForTask != id) skillsForTask = Long.MIN_VALUE; sessionModelChanged(); }

    @Override public void sessionModelChanged() {
        budget.setText(model.tokensLabel() + "\n\nSession totals reflect calls observed by this desktop session.\nMessage-type percentages will appear when the budget service exposes those aggregates.");
        NaruSession session = model.session();
        if (session == null) {
            // Unregister listener if session is gone
            if (listenerRegistered) {
                try {
                    model.session().removeModelCallListener(this);
                } catch (Exception ignored) {}
                listenerRegistered = false;
            }
            return;
        }
        
        // Register listener if not already registered
        if (!listenerRegistered) {
            session.addModelCallListener(this);
            listenerRegistered = true;
        }
        
        context.setText("Loading task and project details…");
        if (skillsForTask != taskId) {
            skillsForTask = taskId;
            skills.setText(taskId < 0 ? "Select a task to inspect available skills." : "Loading skills…");
        }
        detailRefresh.restart();
        plans.setText("Plans are session scoped.\n\nThe desktop project does not statically depend on the optional plan extension. This panel is reserved for its active plan, items, dependencies, and progress when a runtime adapter is available.");
        refreshRequestsView();
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
    public void dispose() { 
        detailRefresh.stop(); 
        detailGeneration++; 
        model.removeListener(this);
        // Unregister model call listener
        NaruSession session = model.session();
        if (session != null && listenerRegistered) {
            try {
                session.removeModelCallListener(this);
            } catch (Exception ignored) {}
            listenerRegistered = false;
        }
    }
}
