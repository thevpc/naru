package net.thevpc.naru.impl.cmdline;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.command.NExecutionException;
import net.thevpc.nuts.io.NErr;
import net.thevpc.nuts.io.NOut;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NCancelException;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses CLI arguments and wires everything together for a single agent run.
 *
 * <p>Kept deliberately simple — no Nuts NCmdLine dependency here so this
 * class can be unit-tested without the Nuts runtime.
 *
 * <pre>
 * Usage:
 *   naru [options]
 *
 * Options:
 *   --task &lt;text&gt;           (required) The task description for the agent
 *   --model &lt;name&gt;          Reasoning model (default: qwen2.5-coder:7b)
 *   --vision-model &lt;name&gt;   Vision model for inspect_image (default: qwen2.5vl:7b)
 *   --provider &lt;name&gt;       Provider: ollama (default: ollama)
 *   --provider-url &lt;url&gt;    Provider base URL (default: http://localhost:11434)
 *   --project-dir &lt;path&gt;    Project directory tools operate in (default: .)
 *   --max-steps &lt;n&gt;         Max agent loop iterations (default: 20)
 *   --no-vision             Disable the inspect_image tool
 *   --quiet                 Suppress step-by-step output
 *   --help                  Show this help
 * </pre>
 */
public class NaruCmdLineProcessor {

    private String projectDir = ".";
    private boolean help = false;
    private boolean helpCommands = false;
    private String[] helpCommandsArgs = new String[0];
    private final List<String> precommands = new ArrayList<>();
    private Boolean forceInteractive;

    public NaruCmdLineProcessor(NCmdLine args) {
        parse(args);
    }

    // ── Argument parser ────────────────────────────────────────────────────────

    private void parse(NCmdLine args) {
        args
                .matcher()
                .when("--tasks", "-t").asEntry(a -> precommands.add(a.value()))
                .when("--file", "-f").asEntry(a -> precommands.add("/source " + a.value()))
                .when("--interactive", "-i").asFlag(a -> forceInteractive = a.booleanValue())
                .when("--project", "-d").asEntry(a -> projectDir = a.value())
                .when("--help", "-h").asFlag(a -> {
                    help = a.booleanValue();
                    while (args.hasNext()) {
                        NArg n = args.next().get();
                        if (n.image().equals("commands")) {
                            helpCommands = true;
                            helpCommandsArgs = args.toStringArray();
                        }
                        args.skipAll();
                    }
                })
                .withDefaults()
                .requireAll();
    }


    // ── Run ───────────────────────────────────────────────────────────────────

    public void run() {
        if (help) {
            if (helpCommands) {
                printHelpCommands(helpCommandsArgs);
            } else {
                printHelp();
            }
            return;
        }
        NaruAgent runner = new NaruAgentImpl();
        runner.projectDirectory(NPath.of(projectDir));
        boolean interactive = (forceInteractive == null ? precommands.isEmpty() : forceInteractive);
        if (interactive) {
            runner.newSession()
                    .statements(precommands.toArray(new String[0]))
                    .interactive()
                    .banner(true)
                    .richTerm(true)
                    .build()
                    .start()
                    .waitFor();
        } else {
            if (precommands.isEmpty()) {
                throw new NIllegalArgumentException(NMsg.ofC("no task specified"));
            }
            try {
                runner.newSession()
                        .statements(precommands.toArray(new String[0]))
                        .build()
                        .start()
                        .waitFor();
            } catch (NCancelException e) {
                // just exit
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void printHelp() {
        NOut.println(
                NMsg.ofC("%s — Nuts AI Reasoning Unit",
                        NMsg.ofStyledPrimary1("naru"))
        );
        NOut.println(
                "Multi-model, tool-based AI agent (Ollama-local-first)\n\n" +
                        "Usage:\n" +
                        "  naru --task \"<description>\" [options]\n\n" +
                        "Options:\n" +
                        "  -t|--task <text>          task to run\n" +
                        "  -f|--file <text>          task file to run\n" +
                        "  -i|--interactive          force interactive mode\n" +
                        "  -d|--project <directory>  select project folder\n" +
                        "  --help, -h             Show this help\n\n" +
                        "Examples:\n" +
                        "  naru --task \"What files are in the project?\"\n" +
                        "  naru -t \"/model use qwen2.5-coder:7b\"  -t \"Fix the bug in MyApp.java\" --project ./my-app" +
                        "  naru -t \"/model use qwen2.5-coder:7b\"  -f \"myscript.naru\" --project ./my-app" +
                        "  naru --task \"Verify the output.png matches input.tson\" --project ./my-app\n"
        );
    }

    private static final NaruSessionListener NOOP_LISTENER = new NaruSessionListener() {
        @Override
        public void onEventAppended(NaruEvent newEvent) {
        }

        @Override
        public void sessionStarted(NaruSession session) {
        }

        @Override
        public void sessionStopped(NaruSession session) {
        }

        @Override
        public void onSessionReloaded(NaruSession naruSession) {
        }
    };

    private static void openStandaloneWorkspace() {
        Nuts.require();
    }

    public void printHelpCommands(String[] helpCommandsArgs) {
        openStandaloneWorkspace();
        NPath projectDir = NPath.ofTempFolder("naru-helpgen-" + System.nanoTime());
        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(projectDir);
        NaruSessionImpl session = new NaruSessionImpl(
                agent, projectDir,
                new NaruStreamInteraction(o -> {
                    // help renders through the directive result; session output is discarded
                }),
                true, NOOP_LISTENER, null, null, null);
        try {
            NaruTask task = session.newTask(NaruTaskSpec.of());
            NaruDirective help = session.registry().findDirective("help")
                    .orElseThrow(() -> new IllegalStateException("no /help directive registered"));
            String argument = helpCommandsArgs.length == 0 ? "--full" : String.join(" ", helpCommandsArgs);
            NaruDirectiveCallContext ctx = new NaruDirectiveCallContextImpl("help", argument, task);
            NaruStmtResult result = help.execute(ctx);
            Object value = result == null ? null : result.successValue();
            String out = value == null ? "" : String.valueOf(value);
            NOut.println(out);
            if (result != null && result.errorValue() != null) {
                throw new NExecutionException(NMsg.ofC("/help reported an error: %s", result.errorValue()), 1);
            }
            if (out.isBlank()) {
                throw new NExecutionException(NMsg.ofC("/help produced no output (args=%s)"
                        ,Arrays.toString(helpCommandsArgs)), 2);
            }
        } catch (Exception e) {
            throw new NExecutionException(NMsg.ofC("failed: %s" , e), 3);
        } finally {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
    }
}
