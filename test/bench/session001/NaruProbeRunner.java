package bench;

import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolCallContextImpl;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class NaruProbeRunner {

    private static final NaruSessionListener NOOP = new NaruSessionListener() {
        @Override public void onEventAppended(NaruEvent e) {}
        @Override public void sessionStarted(NaruSession s) {}
        @Override public void sessionStopped(NaruSession s) {}
        @Override public void onSessionReloaded(NaruSession s) {}
    };

    public static void main(String[] args) throws Exception {
        System.out.println("=== NARU PROBE RUNNER START ===");
        Nuts.require();

        Path tempDir = Files.createTempDirectory("naru-probe");
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(NPath.of(tempDir.toString()));

        NaruSession session = agent.newSession()
                .directory(agent.projectDirectory())
                .sessionListener(NOOP)
                .build();

        // 1. INVENTORY: All tools registered
        System.out.println("\n--- 1. ALL REGISTERED TOOLS ---");
        Map<String, NaruTool> allTools = session.registry().tools();
        for (Map.Entry<String, NaruTool> e : allTools.entrySet()) {
            NaruTool tool = e.getValue();
            System.out.printf("TOOL: %-25s TAGS: %-30s ESSENTIAL: %b%n",
                    tool.name(), tool.tags(), tool.isEssential());
        }

        // 2. DEFAULT TOOLS (task with no tags)
        NaruTask defaultTask = session.newTask(NaruTaskSpec.of());
        List<NaruToolDefinition> defTools = defaultTask.findTools();
        System.out.println("\n--- 2. DEFAULT TOOLS OFFERED TO MODEL (no tags granted) ---");
        System.out.println("Count: " + defTools.size());
        for (NaruToolDefinition td : defTools) {
            System.out.println(" - " + td.getName());
        }

        // 3. TAGS AVAILABLE
        System.out.println("\n--- 3. AVAILABLE TAGS ---");
        for (String tag : session.registry().availableTags().keySet()) {
            System.out.println(" - " + tag);
        }

        // 4. TOOLS REVEALED PER TAG
        System.out.println("\n--- 4. TOOLS REVEALED PER TAG ---");
        String[] tagsToTest = {"fs", "write", "exec", "network", "git", "dev", "java", "index", "tags", "plan", "skills", "routine", "ai"};
        for (String t : tagsToTest) {
            NaruTask tTask = session.newTask(NaruTaskSpec.of());
            try {
                tTask.addToolTag(t);
                List<String> names = tTask.findTools().stream().map(NaruToolDefinition::getName).toList();
                System.out.printf("TAG [%-10s] reveals (%2d tools): %s%n", t, names.size(), names);
            } catch (Exception ex) {
                System.out.printf("TAG [%-10s] ERROR: %s%n", t, ex.getMessage());
            }
        }

        // 5. BEHAVIOR PROBES
        System.out.println("\n--- 5. BEHAVIORAL PROBES ---");
        NaruTask probeTask = session.newTask(NaruTaskSpec.of());
        probeTask.addToolTag("fs");
        probeTask.addToolTag("write");
        probeTask.addToolTag("exec");
        probeTask.addToolTag("network");
        probeTask.addToolTag("plan");
        probeTask.addToolTag("skills");

        // PROBE READ
        System.out.println("\n>> PROBE: file_read");
        Path sampleFile = tempDir.resolve("sample.txt");
        Files.writeString(sampleFile, "line 1\nline 2\nline 3\n");
        System.out.println("Normal read (sample.txt):");
        System.out.println(executeTool(session, probeTask, "file_read", Map.of("path", sampleFile.toString())));

        System.out.println("Nonexistent read:");
        System.out.println(executeTool(session, probeTask, "file_read", Map.of("path", tempDir.resolve("nonexistent.txt").toString())));

        System.out.println("Directory read (trying to read directory path):");
        System.out.println(executeTool(session, probeTask, "file_read", Map.of("path", tempDir.toString())));

        // PROBE WRITE
        System.out.println("\n>> PROBE: file_write");
        Path newFile = tempDir.resolve("sub/dir/written.txt");
        System.out.println("Write with missing parent dirs:");
        System.out.println(executeTool(session, probeTask, "file_write", Map.of("path", newFile.toString(), "content", "hello world")));
        System.out.println("File exists now? " + Files.exists(newFile));

        // PROBE EDIT SEARCH REPLACE
        System.out.println("\n>> PROBE: file_edit_search_replace");
        Path editFile = tempDir.resolve("edit.txt");
        Files.writeString(editFile, "foo bar foo baz foo\n");
        System.out.println("Edit missing string:");
        System.out.println(executeTool(session, probeTask, "file_edit_search_replace", Map.of("path", editFile.toString(), "search", "missing", "replace", "new")));

        System.out.println("Edit multiple matches WITHOUT occurrence:");
        System.out.println(executeTool(session, probeTask, "file_edit_search_replace", Map.of("path", editFile.toString(), "search", "foo", "replace", "QUX")));
        System.out.println("Content after replace: " + Files.readString(editFile));

        System.out.println("Edit with identical search and replace:");
        System.out.println(executeTool(session, probeTask, "file_edit_search_replace", Map.of("path", editFile.toString(), "search", "bar", "replace", "bar")));

        // PROBE RUN_SHELL
        System.out.println("\n>> PROBE: run_shell");
        System.out.println("Exit code surfacing (exit 42):");
        System.out.println(executeTool(session, probeTask, "run_shell", Map.of("command", "exit 42")));

        System.out.println("Working dir probe (pwd with working_dir):");
        System.out.println(executeTool(session, probeTask, "run_shell", Map.of("command", "pwd", "working_dir", tempDir.toString())));

        System.out.println("Large output truncation probe (python printing 20,000 chars):");
        String largeOut = executeTool(session, probeTask, "run_shell", Map.of("command", "python3 -c 'print(\"A\" * 20000)'"));
        System.out.println("Output length: " + largeOut.length() + " (starts with: " + largeOut.substring(0, Math.min(30, largeOut.length())) + "...)");

        // PROBE FOLDER_FIND & SCHEMA PARAMETERS
        System.out.println("\n>> PROBE: folder_find");
        NaruTool folderFindTool = allTools.get("folder_find");
        if (folderFindTool != null) {
            NaruToolDefinition def = folderFindTool.getDefinition(probeTask);
            System.out.println("Declared parameters for folder_find:");
            if (def instanceof NaruToolDefinitionFunction fn) {
                for (NaruToolParameter param : fn.getParams()) {
                    System.out.println(" - " + param.getName() + " (" + param.getType() + ", required=" + param.isRequired() + ")");
                }
            }
            System.out.println("Calling folder_find with schema parameter 'content_pattern':");
            System.out.println(executeTool(session, probeTask, "folder_find", Map.of("path", tempDir.toString(), "content_pattern", "hello")));

            System.out.println("Calling folder_find with undocumented parameter 'pattern':");
            System.out.println(executeTool(session, probeTask, "folder_find", Map.of("path", tempDir.toString(), "pattern", "hello")));
        }

        // PROBE FILE_GREP & SCHEMA PARAMETERS
        System.out.println("\n>> PROBE: file_grep");
        NaruTool fileGrepTool = allTools.get("file_grep");
        if (fileGrepTool != null) {
            NaruToolDefinition def = fileGrepTool.getDefinition(probeTask);
            System.out.println("Declared parameters for file_grep:");
            if (def instanceof NaruToolDefinitionFunction fn) {
                for (NaruToolParameter param : fn.getParams()) {
                    System.out.println(" - " + param.getName() + " (" + param.getType() + ", required=" + param.isRequired() + ")");
                }
            }
            System.out.println("Calling file_grep with schema parameter 'content_pattern':");
            System.out.println(executeTool(session, probeTask, "file_grep", Map.of("path", sampleFile.toString(), "content_pattern", "line")));

            System.out.println("Calling file_grep with undocumented parameter 'pattern':");
            System.out.println(executeTool(session, probeTask, "file_grep", Map.of("path", sampleFile.toString(), "pattern", "line")));
        }

        // PROBE SKILL
        System.out.println("\n>> PROBE: skill");
        NaruTool skillTool = allTools.get("skill");
        if (skillTool != null) {
            System.out.println("isRelevant? " + skillTool.isRelevant(probeTask));
            System.out.println("Execute nonexistent skill:");
            System.out.println(executeTool(session, probeTask, "skill", Map.of("name", "nonexistent")));
        }

        System.out.println("\n=== NARU PROBE RUNNER COMPLETE ===");
    }

    private static String executeTool(NaruSession session, NaruTask task, String toolName, Map<String, Object> args) {
        NaruTool tool = session.registry().tools().get(toolName);
        if (tool == null) return "ERROR: Tool not found: " + toolName;
        NaruToolCallContext ctx = new NaruToolCallContextImpl(args, task);
        try {
            return tool.execute(ctx);
        } catch (Exception ex) {
            return "EXCEPTION: " + ex.getMessage();
        }
    }
}
