package net.thevpc.naru.impl.tools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

/**
 * Runs an arbitrary shell command and returns combined stdout+stderr.
 *
 * <p>Output is capped at 8 KB to avoid flooding the model context.
 */
public class RunShellTool implements NaruTool {

    private static final int MAX_OUTPUT_CHARS = 8_000;

    public RunShellTool() {
    }

    @Override
    public String getName() {
        return "run_shell";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "Execute a shell command and return its output (stdout + stderr). Use sparingly; prefer specialised tools like maven_compile when available.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return NaruRegistry.buildDefinition(
                getName(), getDescription(session),
                NaruToolParameter.string("command", "Shell command to execute", true),
                NaruToolParameter.string("working_dir", "Directory to run the command in (defaults to project dir)", false),
                NaruToolParameter.integer("timeout_seconds", "Max seconds to wait (default: 60)", false)
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String command = context.stringArg("command").orNull();
        String workDir = context.stringArg("working_dir").orNull();
        int timeout = context.numberArg( "timeout_seconds").map(Number::intValue).orElse(60);

        if (NBlankable.isBlank(command)) return "ERROR: 'command' is required.";

        NPath cwd = !NBlankable.isBlank(workDir) ? context.session().resolve(workDir) : context.session().projectDir();

        try {
            NExec nExec = NExec.ofSystem("/bin/sh", "-c", command)
                    .directory(cwd)
                    .failFast();
            String grabbedAllString = nExec
                    .getGrabbedAllString();
            int exitCode = nExec.exitCode();
            return "EXIT_CODE=" + exitCode + "\n" + grabbedAllString;

        } catch (Exception e) {
            return "ERROR running command: " + e.getMessage();
        }
    }

}
