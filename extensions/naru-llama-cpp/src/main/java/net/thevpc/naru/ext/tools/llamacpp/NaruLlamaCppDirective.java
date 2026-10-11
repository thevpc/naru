package net.thevpc.naru.ext.tools.llamacpp;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.model.NaruModelPsResult;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.*;

import java.util.List;

public class NaruLlamaCppDirective extends NaruDirectiveBase {

    private final LlamaCppService service = LlamaCppService.of();

    public NaruLlamaCppDirective() {
        super("llamacpp", "ai", "manage llama.cpp installation and local GGUF models", "lcpp");
        noCommand("status");

        // Subcommand: status / check
        register(new AbstractSubCommand("status", NText.ofPlain("check llama.cpp installation and server running status"),
                new SubCommandHelp(NText.of(""), NText.ofPlain("show detailed llama.cpp status (installation, server, models)"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeStatus(context, cmdLine);
            }
        });

        register(new AbstractSubCommand("check", NText.ofPlain("alias for status")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeStatus(context, cmdLine);
            }
        });

        // Subcommand: run / start
        register(new AbstractSubCommand("run", NText.ofPlain("start the llama.cpp server (llama-server)"),
                new SubCommandHelp(NText.of("[<model>] [--install]"), NText.ofPlain("start llama-server with a model, optionally auto-installing if missing"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeStart(context, cmdLine);
            }
        });

        register(new AbstractSubCommand("start", NText.ofPlain("alias for run")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeStart(context, cmdLine);
            }
        });

        register(new AbstractSubCommand("serve", NText.ofPlain("alias for run")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeStart(context, cmdLine);
            }
        });

        // Subcommand: stop
        register(new AbstractSubCommand("stop", NText.ofPlain("stop the llama.cpp server"),
                new SubCommandHelp(NText.of("[--all|--force]"), NText.ofPlain("stop the llama.cpp server (use --all to stop non-NARU instances)"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeStop(context, cmdLine);
            }
        });

        // Subcommand: restart
        register(new AbstractSubCommand("restart", NText.ofPlain("restart the llama.cpp server"),
                new SubCommandHelp(NText.of("[<model>]"), NText.ofPlain("restart llama-server, optionally with the given model"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeRestart(context, cmdLine);
            }
        });

        // Subcommand: install
        register(new AbstractSubCommand("install", NText.ofPlain("download and install the prebuilt llama.cpp bundle for the current operating system")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeInstall(context, cmdLine);
            }
        });

        // Subcommand: uninstall
        register(new AbstractSubCommand("uninstall", NText.ofPlain("uninstall llama.cpp"),
                new SubCommandHelp(NText.of("[--purge]"), NText.ofPlain("uninstall llama.cpp (use --purge to also delete the models directory)"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeUninstall(context, cmdLine);
            }
        });

        // Subcommand: ps
        register(new AbstractSubCommand("ps", NText.ofPlain("show the model currently served by the running llama.cpp server")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executePs(context, cmdLine);
            }
        });

        // Subcommand: list / models
        register(new AbstractSubCommand("list", NText.ofPlain("list all local GGUF models")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeList(context, cmdLine);
            }
        });

        register(new AbstractSubCommand("models", NText.ofPlain("alias for list")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeList(context, cmdLine);
            }
        });

        // Subcommand: pull / download
        register(new AbstractSubCommand("pull", NText.ofPlain("download a GGUF model from HuggingFace or a direct URL"),
                new SubCommandHelp(NText.of("<model>"), NText.ofPlain("hf.co/<owner>/<repo>[:quant], <owner>/<repo>[:quant], hf.co/<repo>/<file>.gguf, or an https .gguf URL"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executePull(context, cmdLine);
            }
        });

        // Subcommand: rm / delete
        register(new AbstractSubCommand("rm", NText.ofPlain("delete a local GGUF model"),
                new SubCommandHelp(NText.of("<model>"), NText.ofPlain("id or file name of the model to delete (all its shards)"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeDelete(context, cmdLine);
            }
        });
    }

    private NaruStmtResult executeStatus(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        LlamaCppStatus status = service.getStatus(task.session());
        LlamaCppInstallationInfo install = status.getInstallation();
        NStringBuilder sb = NStringBuilder.of();

        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("=== %s Status ===", NMsg.ofStyledPrimary1("llama.cpp")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  Platform     : %s / %s (Dist: %s)",
                NMsg.ofStyledPrimary1(install.getOsFamily()),
                NMsg.ofStyledPrimary2(install.getArchFamily()),
                NMsg.ofStyledPrimary3(install.getOsDist())
        ));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  Installed    : %s",
                install.isInstalled()
                        ? NMsg.ofC("%s (Version: %s, Backend: %s, Path: %s)",
                        NMsg.ofStyledSuccess("YES"),
                        NMsg.ofStyledPrimary1(install.getVersion() != null ? install.getVersion() : "unknown"),
                        NMsg.ofStyledPrimary2(install.getBackend()),
                        NMsg.ofStyledPale(install.getExecutablePath())
                )
                        : NMsg.ofStyledWarn("NO (use '/llamacpp install' to install)")
        ));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  Home         : %s",
                NMsg.ofStyledPale(install.getHome())
        ));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  Service      : %s",
                status.isRunning()
                        ? NMsg.ofC("%s at %s (Latency: %s ms)",
                        NMsg.ofStyledSuccess("RUNNING"),
                        NMsg.ofStyledPrimary1(status.getUrl()),
                        NMsg.ofStyledNumber(String.valueOf(status.getResponseTimeMs()))
                )
                        : NMsg.ofStyledWarn("STOPPED (use '/llamacpp run' to start)")
        ));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  Managed by NARU: %s",
                status.isStartedByNaru()
                        ? NMsg.ofC("%s (PID: %s - will stop automatically on NARU exit)",
                        NMsg.ofStyledSuccess("YES"),
                        NMsg.ofStyledPrimary2(String.valueOf(status.getPid()))
                )
                        : NMsg.ofC("NO (External or already stopped)")
        ));
        sb.println("=== llama.cpp Status ===");
        sb.println("  Platform     : " + install.getOsFamily() + " / " + install.getArchFamily() + " (Dist: " + install.getOsDist() + ")");
        sb.println("  Installed    : " + (install.isInstalled() ? "YES (Version: " + (install.getVersion() != null ? install.getVersion() : "unknown")
                + ", Backend: " + install.getBackend() + ", Path: " + install.getExecutablePath() + ")" : "NO (use '/llamacpp install' to install)"));
        sb.println("  Home         : " + install.getHome());
        sb.println("  Service      : " + (status.isRunning() ? "RUNNING at " + status.getUrl() + " (Latency: " + status.getResponseTimeMs() + " ms)" : "STOPPED (use '/llamacpp run' to start)"));
        sb.println("  Managed by NARU: " + (status.isStartedByNaru() ? "YES (PID: " + status.getPid() + ")" : "NO (External or already stopped)"));

        List<String> models = status.getAvailableModels();
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  Local Models : %s available (use '/llamacpp list' to view)",
                NMsg.ofStyledNumber(String.valueOf(models.size()))
        ));
        sb.println("  Local Models : " + models.size() + " available (use '/llamacpp list' to view)");

        if (status.isRunning()) {
            List<NaruModelPsResult> ps = status.getLoadedModels();
            if (ps.isEmpty()) {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  Loaded       : none"));
                sb.println("  Loaded       : none");
            } else {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  Loaded       : %s model(s) active",
                        NMsg.ofStyledNumber(String.valueOf(ps.size()))
                ));
                sb.println("  Loaded       : " + ps.size() + " model(s) active");
                for (NaruModelPsResult p : ps) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("    - %s", p.getModel().toMsg()));
                    sb.println("    - " + p.getModel().toMsg());
                }
            }
        }
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    private NaruStmtResult executeStart(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        boolean autoInstall = false;
        String model = null;
        while (cmdLine.hasNext()) {
            NArg a = cmdLine.next().get();
            if (a.isOption() && (a.key().equals("--install") || a.key().equals("-i"))) {
                autoInstall = true;
            } else if (!a.isOption() && model == null) {
                model = a.image();
            }
        }
        if (autoInstall && !service.isInstalled(task.session())) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Auto-installing llama.cpp before launch..."));
            service.install(task.session(), msg -> task.log(NaruLogMode.AGENT_RESPONSE, msg));
        }
        LlamaCppService.StartResult r = service.start(task.session(), model, msg -> task.log(NaruLogMode.AGENT_RESPONSE, msg));
        return NaruStmtResult.ofSuccess(r.started());
    }

    private NaruStmtResult executeStop(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        boolean forceAll = false;
        while (cmdLine.hasNext()) {
            NArg a = cmdLine.next().get();
            if (a.isOption() && (a.key().equals("--all") || a.key().equals("--force") || a.key().equals("-f") || a.key().equals("-a"))) {
                forceAll = true;
            }
        }
        boolean r = service.stop(task.session(), forceAll, msg -> task.log(NaruLogMode.AGENT_RESPONSE, msg));
        return NaruStmtResult.ofSuccess(r);
    }

    private NaruStmtResult executeRestart(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        String model = null;
        while (cmdLine.hasNext()) {
            NArg a = cmdLine.next().get();
            if (!a.isOption() && model == null) {
                model = a.image();
            }
        }
        LlamaCppService.StartResult r = service.restart(task.session(), model, msg -> task.log(NaruLogMode.AGENT_RESPONSE, msg));
        return NaruStmtResult.ofSuccess(r.started());
    }

    private NaruStmtResult executeInstall(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Starting llama.cpp download and installation..."));
        boolean ok = service.install(task.session(), msg -> task.log(NaruLogMode.AGENT_RESPONSE, msg));
        if (ok) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofStyledSuccess("llama.cpp installation completed successfully."));
            LlamaCppInstallationInfo info = service.getInstallationInfo(task.session());
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Installed binary: %s (Version: %s)",
                    NMsg.ofStyledPrimary1(info.getExecutablePath()),
                    NMsg.ofStyledPrimary2(info.getVersion() != null ? info.getVersion() : "unknown")
            ));
            return NaruStmtResult.ofSuccess(info.isInstalled());
        } else {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("llama.cpp installation failed. Check logs above.").asError());
            return NaruStmtResult.ofError("llama.cpp installation failed. Check logs above.");
        }
    }

    private NaruStmtResult executeUninstall(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        boolean purge = false;
        while (cmdLine.hasNext()) {
            NArg a = cmdLine.next().get();
            if (a.isOption() && (a.key().equals("--purge") || a.key().equals("-p"))) {
                purge = true;
            }
        }
        boolean ok = service.uninstall(task.session(), purge, msg -> task.log(NaruLogMode.AGENT_RESPONSE, msg));
        if (ok) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofStyledSuccess("llama.cpp uninstalled."));
            return NaruStmtResult.ofSuccess(null);
        } else {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("llama.cpp uninstallation encountered issues.").asWarning());
            return NaruStmtResult.ofError("llama.cpp uninstallation encountered issues.");
        }
    }

    private NaruStmtResult executePs(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        if (!service.isRunning(task.session())) {
            NMsg msg = NMsg.ofC("llama.cpp server is not running. Use '/llamacpp run' to start it.").asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        List<NaruModelPsResult> ps = service.listPs(task.session());
        if (ps.isEmpty()) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("No model currently served by llama.cpp."));
            return NaruStmtResult.ofSuccess(null);
        }
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s served model(s):", ps.size()));
        NStringBuilder sb = NStringBuilder.of();
        sb.println(ps.size() + " served model(s):");
        for (NaruModelPsResult element : ps) {
            NMsg msg = NMsg.ofC("  %s  size: %s  vram: %s",
                    element.getModel().toMsg(),
                    NMsg.ofStyledNumber(NMemoryFormat.DEFAULT.format(NMemorySize.ofBytes(element.getSize()).normalize().canonicalize())),
                    NMsg.ofStyledNumber(NMemoryFormat.DEFAULT.format(NMemorySize.ofBytes(element.getSizeVram()).normalize().canonicalize()))
            );
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            sb.println(msg.toString());
        }
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    private NaruStmtResult executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        List<LlamaCppModelInfo> models = service.listModels(task.session());
        if (models.isEmpty()) {
            NPath dir = LlamaCppPaths.modelsDir(task.session());
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("No local GGUF model found in %s. Pull one with '/llamacpp pull <repo>'.",
                    NMsg.ofStyledPale(dir.toString())));
            return NaruStmtResult.ofSuccess(null);
        }
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s local model(s) available in llama.cpp:", models.size()));
        NStringBuilder sb = NStringBuilder.of();
        sb.println(models.size() + " local model(s) available in llama.cpp:");
        for (int i = 0; i < models.size(); i++) {
            LlamaCppModelInfo m = models.get(i);
            NMsg msg = NMsg.ofC("  [%s] %s  %s%s",
                    NMsg.ofStyledNumber(String.valueOf(i + 1)),
                    NMsg.ofStyledPrimary1(m.getId()),
                    NMsg.ofStyledPale(NMemoryFormat.DEFAULT.format(NMemorySize.ofBytes(m.getSizeBytes()).normalize().canonicalize())),
                    m.isSharded() ? NMsg.ofStyledPale(" (sharded)") : NMsg.ofStyledPale("")
            );
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            sb.println(msg.toString());
        }
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    private NaruStmtResult executePull(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NOptional<NArg> n = cmdLine.next();
        if (!n.isPresent() || NBlankable.isBlank(n.get().image())) {
            NMsg msg = NMsg.ofC("Error: missing model to pull (e.g. /llamacpp pull hf.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF:Q4_K_M)").asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        String model = n.get().image();
        try {
            LlamaCppModelInfo info = service.pullModel(model, task.session(), msg -> task.log(NaruLogMode.AGENT_RESPONSE, msg));
            return NaruStmtResult.ofSuccess(info.getId());
        } catch (Exception e) {
            NMsg msg = NMsg.ofC("Error pulling model: %s", e.getMessage()).asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
    }

    private NaruStmtResult executeDelete(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NOptional<NArg> n = cmdLine.next();
        if (!n.isPresent() || NBlankable.isBlank(n.get().image())) {
            NMsg msg = NMsg.ofC("Error: missing model to delete (e.g. /llamacpp rm qwen2.5-coder-7b-instruct-q4_k_m)").asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        String model = n.get().image();
        try {
            service.rmModel(model, task.session(), msg -> task.log(NaruLogMode.AGENT_RESPONSE, msg));
            return NaruStmtResult.ofSuccess(null);
        } catch (Exception e) {
            NMsg msg = NMsg.ofC("Error deleting model: %s", e.getMessage()).asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
    }
}
