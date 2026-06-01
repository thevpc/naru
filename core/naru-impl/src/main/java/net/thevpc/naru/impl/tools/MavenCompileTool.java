package net.thevpc.naru.impl.tools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

/**
 * Runs {@code mvn compile} in a Maven project directory.
 */
public class MavenCompileTool implements NaruTool {

    private static final int MAX_OUTPUT_CHARS = 8_000;

    public MavenCompileTool() {
    }

    @Override
    public String name() {
        return "maven_compile";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "Compile a Maven project using 'mvn compile'. Returns compiler output and exit code.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(session),
                NaruToolParameter.string("project_dir", "Path to the Maven project (defaults to agent project dir)", false)
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
                    .failFast(true)
                    .maxLines(300)
                    .directory(projectDir);
            output.append(exec.grabbedAll());
            return "EXIT_CODE=" + exec.exitCode() + "\n" + output;
        } catch (Exception e) {
            return "ERROR running maven: " + e.getMessage();
        }
    }

    private static String[] buildCmd(String[] goals) {
        String[] cmd = new String[2 + goals.length];
        cmd[0] = "mvn";
        cmd[1] = "-B"; // batch mode (no ANSI, no interactive prompts)
        System.arraycopy(goals, 0, cmd, 2, goals.length);
        return cmd;
    }

}
