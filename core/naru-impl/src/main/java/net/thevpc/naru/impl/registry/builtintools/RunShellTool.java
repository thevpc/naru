package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.DefaultNaruTool;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

/**
 * Runs an arbitrary shell command and returns combined stdout+stderr.
 *
 * <p>Output is capped at 8 KB to avoid flooding the model context.
 */
public class RunShellTool extends DefaultNaruTool {

    private static final int MAX_OUTPUT_CHARS = 8_000;

    public RunShellTool() {
        super("run_shell", new String[]{NaruToolTags.NETWORK});
    }


    @Override
    public String getDescription(NaruTask task) {
        return "Execute a shell command and return its output (stdout + stderr). Use sparingly; prefer specialised tools like maven_compile when available.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("command", "Shell command to execute", true).build(),
                NaruToolParameter.string("working_dir", "Directory to run the command in (defaults to project dir)", false).build(),
                NaruToolParameter.integer("timeout_seconds", "Max seconds to wait (default: 60)", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String command = context.stringArg("command").orNull();
        String workDir = context.stringArg("working_dir").orNull();
        int timeout = context.numberArg("timeout_seconds").map(Number::intValue).orElse(60);

        if (NBlankable.isBlank(command)) return "ERROR: 'command' is required.";

        NPath cwd = !NBlankable.isBlank(workDir) ? context.task().resolve(workDir) : context.task().projectDir();

        try {
            NExec nExec = NExec.ofSystem("/bin/sh", "-c", command)
                    .directory(cwd)
                    .failFast(true);
            String grabbedAllString = nExec
                    .grabbedAll();
            int exitCode = nExec.exitCode();
            return "EXIT_CODE=" + exitCode + "\n" + grabbedAllString;

        } catch (Exception e) {
            return "ERROR running command: " + e.getMessage();
        }
    }


}
