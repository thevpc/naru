package net.thevpc.naru.impl.cmdline;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.impl.agent.NaruAgentImpl;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.io.NOut;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NCancelException;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.util.ArrayList;
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
    private final List<String> precommands = new ArrayList<>();
    private Boolean forceInteractive;

    public NaruCmdLineProcessor(NCmdLine args) {
        parse(args);
    }

    // ── Argument parser ────────────────────────────────────────────────────────

    private void parse(NCmdLine args) {
        args
                .matcher()
                .with("--tasks", "-t").matchEntry(a -> precommands.add(a.value()))
                .with("--file", "-f").matchEntry(a -> precommands.add("/source "+a.value()))
                .with("--interactive", "-i").matchFlag(a -> forceInteractive = a.booleanValue())
                .with("--project","-d").matchEntry(a -> projectDir = a.value())
                .with("--help", "-h").matchTrueFlag(a -> help = a.booleanValue())
                .withDefaults()
                .requireAll();
    }


    // ── Run ───────────────────────────────────────────────────────────────────

    public void run() {
        if (help) {
            printHelp();
            return;
        }
        NaruAgent runner = new NaruAgentImpl();
        runner.setProjectDirectory(NPath.of(projectDir));
        boolean interactive = (forceInteractive == null ? precommands.isEmpty() : forceInteractive);
        if (interactive) {
            runner.startInteractiveSession(precommands.toArray(new String[0]))
                    ;
        } else {
            if (precommands.isEmpty()) {
                throw new NIllegalArgumentException(NMsg.ofC("no task specified"));
            }
            try {
                runner.startSession(precommands.toArray(new String[0]))
                        .waitFor();
            }catch (NCancelException e){
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
                        "  naru -t \"/model set qwen2.5-coder:7b\"  -t \"Fix the bug in MyApp.java\" --project ./my-app" +
                        "  naru -t \"/model set qwen2.5-coder:7b\"  -f \"myscript.naru\" --project ./my-app" +
                        "  naru --task \"Verify the output.png matches input.tson\" --project ./my-app\n"
        );
    }
}
