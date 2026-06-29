package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.DefaultNaruTool;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

/**
 * Runs {@code mvn compile} in a Maven project directory.
 */
public class MavenCompileTool extends DefaultNaruTool {

    private static final int MAX_OUTPUT_CHARS = 8_000;

    public MavenCompileTool() {
        super("maven_compile", new String[]{NaruToolTags.DEV});
    }

    @Override
    public String name() {
        return "maven_compile";
    }

    @Override
    public String getDescription(NaruTask task) {
        return "Compile a Maven project using 'mvn compile'. Returns compiler output and exit code.";
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
