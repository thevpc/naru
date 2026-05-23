package net.thevpc.naru.impl.cmdline;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.impl.agent.NaruAgentImpl;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.io.NOut;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;

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

    private String task;
    private String projectDir = ".";
    private boolean help = false;

    public NaruCmdLineProcessor(NCmdLine args) {
        parse(args);
    }

    // ── Argument parser ────────────────────────────────────────────────────────

    private void parse(NCmdLine args) {
        NCmdLine.Matcher matcher = args
                .matcher()
                .with("--task").matchEntry(a -> task = a.value())
                .with("--project-dir").matchEntry(a -> projectDir = a.value())
                .with("--help", "-h").matchTrueFlag(a -> help = a.booleanValue())
                .withNonOption().matchAny(a -> task = (task == null ? "" : task + " ") + a.image());
        while (args.hasNext()) {
            matcher.requireDefaults();
        }
    }


    // ── Run ───────────────────────────────────────────────────────────────────

    public void run() {
        if (help) {
            printHelp();
            return;
        }
        NaruAgent runner = new NaruAgentImpl();
        runner.setProjectDirectory(NPath.of(projectDir));
        if (NBlankable.isBlank(task)) {
            runner.runInteractive();
        } else {
            runner.runTask(task);
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
                        "  --task <text>          (required) The task description\n" +
                        "  --model <name>         Reasoning model  (default: qwen2.5-coder:7b)\n" +
                        "  --vision-model <name>  Vision model     (default: qwen2.5vl:7b)\n" +
                        "  --provider <name>      Provider         (default: ollama)\n" +
                        "  --provider-url <url>   Provider URL     (default: http://localhost:11434)\n" +
                        "  --project-dir <path>   Working dir      (default: .)\n" +
                        "  --max-steps <n>        Max iterations   (default: 20)\n" +
                        "  --no-vision            Disable inspect_image tool\n" +
                        "  --quiet                Suppress step output\n" +
                        "  --help, -h             Show this help\n\n" +
                        "Examples:\n" +
                        "  naru --task \"What files are in the project?\"\n" +
                        "  naru --task \"Fix the bug in MyApp.java\" --project-dir ./my-app --model qwen2.5-coder:7b\n" +
                        "  naru --task \"Verify the output.png matches input.tson\" --project-dir ./my-app\n"
        );
    }
}
