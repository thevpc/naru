package net.thevpc.naru.ext.tools.java;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;
import java.util.List;

/**
 * Runs {@code mvn compile} in a Maven project directory.
 */
public class MavenCompileTool extends DefaultNaruTool {

    private static final int MAX_OUTPUT_CHARS = 8_000;

    public MavenCompileTool() {
        // exec: maven_compile spawns mvn, which loads and runs arbitrary build
        // plugins. Without the exec tag a task granted dev saw this in plan mode,
        // whose whole contract is "no code execution".
        super("maven_compile", new String[]{NaruToolTags.DEV, NaruToolTags.JAVA, NaruToolTags.EXECUTE});
    }

    @Override
    public String name() {
        return "maven_compile";
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Compile a Maven project using 'mvn compile'. Returns compiler output and exit code.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("project_dir", "Path to the Maven project (defaults to agent project dir)", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String projectDirArg = context.stringArg("project_dir").orNull();
        NPath projectDir = NBlankable.isNonBlank(projectDirArg)
                ? context.task().resolve(projectDirArg)
                : context.task().projectDir();

        return runMaven(projectDir, "compile");
    }

    static String runMaven(NPath projectDir, String... goals) {
        StringBuilder output = new StringBuilder();
        NExec exec;
        try {
            exec = NExec.ofSystem(buildCmd(goals))
                    .failFast(false)
                    .maxLines(500)
                    .directory(projectDir);
            String raw = exec.grabbedAll();
            int exitCode = exec.exitCode();

            output.append("EXIT_CODE=").append(exitCode).append("\n");

            // Extract structured errors from javac / maven output
            List<String> errors = extractCompilerErrors(raw);
            if (!errors.isEmpty()) {
                output.append("\n=== COMPILER ERRORS (").append(errors.size()).append(" found) ===\n");
                for (int i = 0; i < Math.min(15, errors.size()); i++) {
                    output.append(errors.get(i)).append("\n");
                }
                if (errors.size() > 15) {
                    output.append("... [and ").append(errors.size() - 15).append(" more errors]\n");
                }
                output.append("=========================================\n\n");
            }

            if (raw.length() > MAX_OUTPUT_CHARS) {
                output.append(raw.substring(0, MAX_OUTPUT_CHARS)).append("\n... [output truncated]");
            } else {
                output.append(raw);
            }

            return output.toString();
        } catch (Exception e) {
            return "ERROR running maven: " + e.getMessage();
        }
    }

    private static List<String> extractCompilerErrors(String rawOutput) {
        List<String> errors = new java.util.ArrayList<>();
        if (rawOutput == null || rawOutput.isEmpty()) return errors;

        String[] lines = rawOutput.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[ERROR]") && (trimmed.contains(".java:[") || trimmed.contains("error:") || trimmed.contains("Failure"))) {
                errors.add(trimmed);
            } else if (trimmed.matches(".*\\.java:\\[\\d+,\\d+\\].*")) {
                errors.add(trimmed);
            }
        }
        return errors;
    }

    private static String[] buildCmd(String[] goals) {
        String[] cmd = new String[2 + goals.length];
        cmd[0] = "mvn";
        cmd[1] = "-B"; // batch mode (no ANSI, no interactive prompts)
        System.arraycopy(goals, 0, cmd, 2, goals.length);
        return cmd;
    }

}
