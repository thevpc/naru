package net.thevpc.naru.ext.tools.sh;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.core.NSession;
import net.thevpc.nuts.io.NAnsiTermHelper;
import net.thevpc.nuts.platform.NEnv;
import net.thevpc.nuts.platform.NOsFamily;
import net.thevpc.nuts.text.NMsg;

import java.util.logging.Level;

/**
 * Runs a shell command whose text is the raw directive argument (no NCmdLine
 * quoting). By default it captures the output, records everything in the
 * model-visible history and publishes the exit code as the task env var
 * {@code lastExitCode} (so script control-flow like /while can loop until a
 * command succeeds). The option {@code --save <var>} additionally stores the
 * trimmed captured output into the task env var {@code var}, so a script can
 * compare it directly ({@code /if calc42 == "42"}) without a shell pipe.
 * Prefix the command with {@code --no-grab} to run it with
 * the terminal's stdin/stdout attached instead (e.g. an interactive editor like
 * vim): output is then not captured ({@code lastExitCode} is still published).
 */
public class NaruSystemDirective extends NaruDirectiveBase {

    private static final int MAX_OUTPUT_CHARS = 8_000;

    public NaruSystemDirective() {
        super("system", "general", "run system command", "sys");
        register(new AbstractSubCommand() {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                String cmd = context.argument();
                boolean grab = true;
                String saveVar = null;
                // The argument is raw shell text (not NCmdLine-quoted), so the few
                // naru-level options are recognized as a prefix: "--no-grab" (run
                // attached to the terminal instead of capturing) and
                // "--save <var>" (also store the captured output into a task env
                // var, trimmed, so scripts can compare it: /if calc42 == "42").
                while (cmd != null) {
                    if (cmd.equals("--no-grab")) {
                        grab = false;
                        cmd = "";
                    } else if (cmd.startsWith("--no-grab ")) {
                        grab = false;
                        cmd = cmd.substring("--no-grab ".length());
                    } else if (cmd.startsWith("--save ")) {
                        String rest = cmd.substring("--save ".length());
                        int sp = rest.indexOf(' ');
                        saveVar = sp < 0 ? rest.trim() : rest.substring(0, sp);
                        cmd = sp < 0 ? "" : rest.substring(sp + 1);
                    } else {
                        break;
                    }
                }
                final String runCmd = cmd == null ? "" : cmd;
                final boolean grabMode = grab;
                final String saveName = saveVar;
                try (NSession session = NSession.of().copy()) {
                    session.logTermLevel(Level.OFF);
                    session.runWith(() -> {
                        NExec e = NEnv.of().osFamily() == NOsFamily.WINDOWS
                                ? NExec.of("cmd", "/c", runCmd)
                                : NExec.ofSystem("/bin/sh", "-c", runCmd);
                        e.directory(task.projectDir() == null ? task.workingDir() : task.projectDir())
                                .failFast(false)
                                // Pass the full environment (PATH, HOME, JAVA_HOME, ...):
                                // nuts' NExec otherwise spawns the child with a near-empty
                                // env so /bin/sh cannot find mvn/java on the PATH.
                                .env(System.getenv());
                        if (grabMode) {
                            String grabbed = e.grabbedAll();
                            int exitCode = e.exitCode();
                            String result = NAnsiTermHelper.of().stripAnsi(grabbed);
                            if (result != null && result.length() > MAX_OUTPUT_CHARS) {
                                result = result.substring(result.length() - MAX_OUTPUT_CHARS);
                            }
                            task.addHistory(NaruMessage.user(NMsg.ofC(
                                    "call   : system %s\nexit code %s\nresult : \n%s",
                                    runCmd, exitCode, result).toString()));
                            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
                            // publish so script control-flow can loop until exit 0
                            task.setTaskEnv("lastExitCode", exitCode);
                            // optional capture: /system --save <var> <cmd> stores the
                            // trimmed output so a script can compare it without a pipe
                            if (saveName != null && !saveName.isEmpty()) {
                                task.setTaskEnv(saveName, result == null ? "" : result.trim());
                            }
                        } else {
                            // no output capture: run attached to the terminal (vim, ...)
                            e.run();
                            int exitCode = e.exitCode();
                            task.addHistory(NaruMessage.user(NMsg.ofC(
                                    "call   : system --no-grab %s\nexit code %s", runCmd, exitCode).toString()));
                            task.setTaskEnv("lastExitCode", exitCode);
                        }
                    });
                }
            }
        });
    }

}
