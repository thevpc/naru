package net.thevpc.naru.ext.assess;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;

/**
 * {@code /assess} — measures how well a model does real work. A run performs the tasks of an
 * asset (a reference project plus prompts) with the chosen model in isolated naru sessions,
 * scores each outcome with deterministic checks, and reports score, time, tokens and tool use.
 */
public class NaruAssessDirective extends NaruDirectiveBase {

    public NaruAssessDirective() {
        super("assess", "ai", "assess a model against reference coding tasks");
        noCommand("list");
        register(new AbstractSubCommand("list", NText.ofPlain("list assets and their tasks"),
                new SubCommandHelp("[--asset=<id>]", "show one asset; default all")) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                return executeList(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("run", NText.ofPlain("run a model against an asset"),
                new SubCommandHelp("<provider/model> [--asset=<id>]", "model as registered, e.g. ollama/qwen3-coder:30b; default asset java-ref"),
                new SubCommandHelp("[--tier=simple|normal|complex|expert] [--task=<id>[,<id>...]]", "narrow the tasks; default all"),
                new SubCommandHelp("[--timeout-scale=<x>]", "multiply every wall-clock cap (tiers: 5/10/20/30 min); slow local models need more"),
                new SubCommandHelp("[--thinking=on|off] [--max-steps=<n>] [--keep]", "force reasoning on/off, bound tool rounds per turn, keep work dirs")) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                return executeRun(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("report", NText.ofPlain("show a finished run"),
                new SubCommandHelp("[<runId>|last] [--detail] [--log]", "scores per task; --detail adds failed checks, tools and the answer; --log the session transcript")) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                return executeReport(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("runs", NText.ofPlain("list past runs, newest first")) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                AssessReports.runs(new AssessStore().list()).forEach(l -> print(context, l));
                return NaruStmtResult.ofSuccess();
            }
        });
    }

    // ── /assess list ───────────────────────────────────────────────────────────

    private NaruStmtResult executeList(SubCommandContext context, NCmdLine cmdLine) {
        String only = null;
        while (cmdLine.hasNext()) {
            String a = cmdLine.next().get().image();
            if (a.startsWith("--asset=")) {
                only = a.substring("--asset=".length());
            } else {
                return fail(context, "unexpected argument '" + a + "'");
            }
        }
        for (AssessAsset asset : AssessAssets.all()) {
            if (only != null && !only.equals(asset.id())) {
                continue;
            }
            print(context, asset.id() + " — " + asset.description());
            for (AssessTask t : asset.tasks()) {
                print(context, String.format(Locale.ROOT, "  %-24s %-8s %3d pts  cap %2dm  %s", t.id(), t.tier().label(),
                        t.totalPoints(), t.tier().defaultMinutes(), t.title()));
            }
        }
        return NaruStmtResult.ofSuccess();
    }

    // ── /assess run ────────────────────────────────────────────────────────────

    private NaruStmtResult executeRun(SubCommandContext context, NCmdLine cmdLine) {
        String model = null;
        String assetId = "java-ref";
        AssessTier tier = null;
        List<String> taskIds = new ArrayList<>();
        AssessOptions opts = new AssessOptions();
        try {
            while (cmdLine.hasNext()) {
                NArg arg = cmdLine.next().get();
                String a = arg.image();
                if (!a.startsWith("--")) {
                    if (model != null) {
                        return fail(context, "unexpected argument '" + a + "' (model is already " + model + ")");
                    }
                    model = a;
                } else if (a.startsWith("--asset=")) {
                    assetId = value(a);
                } else if (a.startsWith("--tier=")) {
                    tier = AssessTier.parse(value(a));
                } else if (a.startsWith("--task=")) {
                    for (String id : value(a).split(",")) {
                        taskIds.add(id.trim());
                    }
                } else if (a.startsWith("--timeout-scale=")) {
                    opts.timeoutScale = Double.parseDouble(value(a));
                } else if (a.startsWith("--thinking=")) {
                    String v = value(a);
                    if (!v.equals("on") && !v.equals("off")) {
                        return fail(context, "--thinking expects on|off");
                    }
                    opts.thinking = v.equals("on");
                } else if (a.startsWith("--max-steps=")) {
                    opts.maxSteps = Integer.parseInt(value(a));
                } else if (a.equals("--keep")) {
                    opts.keepWorkDir = true;
                } else {
                    return fail(context, "unknown option '" + a + "'");
                }
            }
        } catch (IllegalArgumentException e) {
            return fail(context, e.getMessage());
        }
        if (model == null) {
            return fail(context, "usage: /assess run <provider/model> [options] (see /assess help)");
        }
        Optional<AssessAsset> asset = AssessAssets.find(assetId);
        if (asset.isEmpty()) {
            return fail(context, "unknown asset '" + assetId + "' (see /assess list)");
        }
        List<AssessTask> tasks = new ArrayList<>();
        for (AssessTask t : asset.get().tasks()) {
            if ((tier == null || t.tier() == tier) && (taskIds.isEmpty() || taskIds.contains(t.id()))) {
                tasks.add(t);
            }
        }
        for (String id : taskIds) {
            if (tasks.stream().noneMatch(t -> t.id().equals(id))) {
                return fail(context, "no task '" + id + "' selected (see /assess list)");
            }
        }
        if (tasks.isEmpty()) {
            return fail(context, "no task matches the selection");
        }
        print(context, "assessing " + model + " on " + asset.get().id() + ": " + tasks.size() + " task(s)");
        AssessStore store = new AssessStore();
        AssessRun run = AssessRunner.run(asset.get(), tasks, model, opts, store, line -> print(context, line));
        print(context, "");
        AssessReports.run(run, true).forEach(l -> print(context, l));
        print(context, "saved " + store.runsDir().resolve(run.id + ".json"));
        return NaruStmtResult.ofSuccess();
    }

    // ── /assess report ─────────────────────────────────────────────────────────

    private NaruStmtResult executeReport(SubCommandContext context, NCmdLine cmdLine) {
        String id = "last";
        boolean detail = false;
        boolean log = false;
        while (cmdLine.hasNext()) {
            String a = cmdLine.next().get().image();
            if (a.equals("--detail")) {
                detail = true;
            } else if (a.equals("--log")) {
                log = true;
            } else if (a.startsWith("--")) {
                return fail(context, "unknown option '" + a + "'");
            } else {
                id = a;
            }
        }
        Optional<AssessRun> run = new AssessStore().find(id);
        if (run.isEmpty()) {
            return fail(context, "no such run '" + id + "' (see /assess runs)");
        }
        AssessReports.run(run.get(), detail).forEach(l -> print(context, l));
        if (log) {
            for (AssessTaskResult r : run.get().results) {
                print(context, "---- transcript " + r.taskId);
                r.transcript.lines().forEach(l -> print(context, l));
            }
        }
        return NaruStmtResult.ofSuccess();
    }

    private static String value(String optionImage) {
        return optionImage.substring(optionImage.indexOf('=') + 1);
    }

    private static void print(SubCommandContext context, String line) {
        context.outln(NMsg.ofC("%s", NText.ofPlain(line)));
    }

    private static NaruStmtResult fail(SubCommandContext context, String message) {
        context.errln(NMsg.ofC("%s", NText.ofPlain("Error: " + message)));
        return NaruStmtResult.ofError();
    }
}
