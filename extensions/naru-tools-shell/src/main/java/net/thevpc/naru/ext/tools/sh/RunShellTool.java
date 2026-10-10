package net.thevpc.naru.ext.tools.sh;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;

/**
 * Runs an arbitrary shell command and returns combined stdout+stderr.
 *
 * <p>Output is capped at 8 KB to avoid flooding the model context.
 */
public class RunShellTool extends DefaultNaruTool {

    private static final int MAX_OUTPUT_CHARS = 8_000;

    public RunShellTool() {
        // exec, not just network: spawning a process is what the exec tag exists for,
        // and the plan mode veto keys on it -- a read-only mode must not be able to see
        // a shell through a task that was granted network for search_web alone.
        // write, because a shell can mutate the filesystem; network stays, since
        // granting it has always been how a task opts into run_shell.
        super("run_shell", new String[]{NaruToolTags.NETWORK, NaruToolTags.EXECUTE, NaruToolTags.WRITE});
    }


    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Execute a shell command and return its output (stdout + stderr). Use sparingly; prefer specialised tools like maven_compile when available.");
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
            // Pass the full current environment (PATH, HOME, JAVA_HOME, ...) to the
            // child: nuts' NExec otherwise spawns the process with a near-empty env
            // (+ NUTS_DEPLOY_* vars), so /bin/sh cannot find mvn/java on the PATH.
            NExec nExec = NExec.ofSystem("/bin/sh", "-c", command)
                    .directory(cwd)
                    .env(System.getenv())
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
