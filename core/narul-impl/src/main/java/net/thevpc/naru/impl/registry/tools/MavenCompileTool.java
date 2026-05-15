package net.thevpc.naru.impl.registry.tools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.naru.api.tool.NaruToolRegistry;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

/** Runs {@code mvn compile} in a Maven project directory. */
public class MavenCompileTool implements NaruTool {

    private static final int MAX_OUTPUT_CHARS = 8_000;
    private final NaruToolDefinition definition;

    public MavenCompileTool() {
        this.definition = NaruToolRegistry.buildDefinition(
                getName(), getDescription(),
                NaruToolParameter.string("project_dir", "Path to the Maven project (defaults to agent project dir)", false)
        );
    }

    @Override public String getName() { return "maven_compile"; }
    @Override public String getDescription() { return "Compile a Maven project using 'mvn compile'. Returns compiler output and exit code."; }
    @Override public NaruToolDefinition getDefinition() { return definition; }

    @Override
    public String execute(NaruToolCallContext context) {
        String projectDirArg = context.stringArg("project_dir").orNull();
        NPath projectDir = NBlankable.isNonBlank(projectDirArg)
                ? context.session().resolve(projectDirArg)
                : context.session().projectDir();

        return runMaven(projectDir, "compile");
    }

    static String runMaven(NPath projectDir, String... goals) {
        StringBuilder output = new StringBuilder();
        NExec exec;
        try {
            exec = NExec.ofSystem(buildCmd(goals))
                    .failFast()
                    .maxLines(300)
                    .directory(projectDir);
            output.append(exec.getGrabbedAllString());
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
