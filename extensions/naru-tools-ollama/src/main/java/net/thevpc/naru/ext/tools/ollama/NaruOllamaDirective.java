package net.thevpc.naru.ext.tools.ollama;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.model.NaruModelPsResult;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.*;

import java.text.DecimalFormat;
import java.util.List;

public class NaruOllamaDirective extends NaruDirectiveBase {

    private final OllamaService service = OllamaService.of();

    public NaruOllamaDirective() {
        super("ollama", "ai", "manage Ollama server, installation, and models", "ol");
        noCommand("status");

        // Subcommand: status / check
        register(new AbstractSubCommand("status", NText.ofPlain("check Ollama installation and server running status"),
                new SubCommandHelp(NText.of(""), NText.ofPlain("show detailed Ollama status (installation, service, memory, models)"))
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
        register(new AbstractSubCommand("run", NText.ofPlain("start Ollama server (ollama serve)"),
                new SubCommandHelp(NText.of("[--install]"), NText.ofPlain("start Ollama, optionally auto-installing if missing"))
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
        register(new AbstractSubCommand("stop", NText.ofPlain("stop Ollama server"),
                new SubCommandHelp(NText.of("[--all|--force]"), NText.ofPlain("stop Ollama server (use --all to stop non-NARU instances)"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeStop(context, cmdLine);
            }
        });

        // Subcommand: restart
        register(new AbstractSubCommand("restart", NText.ofPlain("restart Ollama server")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                service.restart(task.session(), msg -> task.log(NaruLogMode.AGENT_RESPONSE, msg));
                return NaruStmtResult.ofSuccess(null);
            }
        });

        // Subcommand: install
        register(new AbstractSubCommand("install", NText.ofPlain("download and install Ollama for the current operating system")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeInstall(context, cmdLine);
            }
        });

        // Subcommand: uninstall
        register(new AbstractSubCommand("uninstall", NText.ofPlain("uninstall Ollama"),
                new SubCommandHelp(NText.of("[--purge]"), NText.ofPlain("uninstall Ollama (use --purge to also delete models directory)"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeUninstall(context, cmdLine);
            }
        });

        // Subcommand: ps
        register(new AbstractSubCommand("ps", NText.ofPlain("list models currently loaded in memory/VRAM")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executePs(context, cmdLine);
            }
        });

        // Subcommand: list / models
        register(new AbstractSubCommand("list", NText.ofPlain("list all local Ollama models")) {
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
        register(new AbstractSubCommand("pull", NText.ofPlain("pull a model from the Ollama library"),
                new SubCommandHelp(NText.of("<model>"), NText.ofPlain("name of model to download (e.g. qwen2.5-coder:7b)"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executePull(context, cmdLine);
            }
        });

        // Subcommand: rm / delete
        register(new AbstractSubCommand("rm", NText.ofPlain("delete a local Ollama model"),
                new SubCommandHelp(NText.of("<model>"), NText.ofPlain("name of model to delete"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeDelete(context, cmdLine);
            }
        });
    }

    private NaruStmtResult executeStatus(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        OllamaStatus status = service.getStatus(task.session());
        OllamaInstallationInfo install = status.getInstallation();
        NStringBuilder sb = NStringBuilder.of();

        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("=== %s Status ===", NMsg.ofStyledPrimary1("Ollama")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  Platform     : %s / %s (Dist: %s)",
                NMsg.ofStyledPrimary1(install.getOsFamily()),
                NMsg.ofStyledPrimary2(install.getArchFamily()),
                NMsg.ofStyledPrimary3(install.getOsDist())
        ));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  Installed    : %s",
                install.isInstalled()
                        ? NMsg.ofC("%s (Version: %s, Path: %s)",
                        NMsg.ofStyledSuccess("YES"),
                        NMsg.ofStyledPrimary1(install.getVersion() != null ? install.getVersion() : "unknown"),
                        NMsg.ofStyledPale(install.getExecutablePath())
                )
                        : NMsg.ofStyledWarn("NO (use '/ollama install' to install)")
        ));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  Service      : %s",
                status.isRunning()
                        ? NMsg.ofC("%s at %s (Latency: %s ms, Server version: %s)",
                        NMsg.ofStyledSuccess("RUNNING"),
                        NMsg.ofStyledPrimary1(status.getUrl()),
                        NMsg.ofStyledNumber(String.valueOf(status.getResponseTimeMs())),
                        NMsg.ofStyledPrimary2(status.getServerVersion() != null ? status.getServerVersion() : "unknown")
                )
                        : NMsg.ofStyledWarn("STOPPED (use '/ollama run' to start)")
        ));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  Managed by NARU: %s",
                status.isStartedByNaru()
                        ? NMsg.ofC("%s (PID: %s - will stop automatically on NARU exit)",
                        NMsg.ofStyledSuccess("YES"),
                        NMsg.ofStyledPrimary2(String.valueOf(status.getPid()))
                )
                        : NMsg.ofC("NO (External or background process)")
        ));
        sb.println("=== Ollama Status ===");
        sb.println("  Platform     : " + install.getOsFamily() + " / " + install.getArchFamily() + " (Dist: " + install.getOsDist() + ")");
        sb.println("  Installed    : " + (install.isInstalled() ? "YES (Version: " + (install.getVersion() != null ? install.getVersion() : "unknown") + ", Path: " + install.getExecutablePath() + ")" : "NO (use '/ollama install' to install)"));
        sb.println("  Service      : " + (status.isRunning() ? "RUNNING at " + status.getUrl() + " (Latency: " + status.getResponseTimeMs() + " ms, Server version: " + (status.getServerVersion() != null ? status.getServerVersion() : "unknown") + ")" : "STOPPED (use '/ollama run' to start)"));
        sb.println("  Managed by NARU: " + (status.isStartedByNaru() ? "YES (PID: " + status.getPid() + ")" : "NO (External or background process)"));

        if (status.isRunning()) {
            List<String> models = status.getAvailableModels();
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  Local Models : %s available (use '/ollama list' to view)",
                    NMsg.ofStyledNumber(String.valueOf(models.size()))
            ));
            sb.println("  Local Models : " + models.size() + " available (use '/ollama list' to view)");

            List<NaruModelPsResult> ps = status.getLoadedModels();
            if (!ps.isEmpty()) {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  Loaded (VRAM): %s model(s) active",
                        NMsg.ofStyledNumber(String.valueOf(ps.size()))
                ));
                sb.println("  Loaded (VRAM): " + ps.size() + " model(s) active");
                for (NaruModelPsResult p : ps) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("    - %s (Size: %s, VRAM: %s)",
                            p.getModel().toMsg(),
                            NMsg.ofStyledNumber(NMemoryFormat.DEFAULT.format(NMemorySize.ofBytes(p.getSize()).normalize().canonicalize())),
                            NMsg.ofStyledNumber(NMemoryFormat.DEFAULT.format(NMemorySize.ofBytes(p.getSizeVram()).normalize().canonicalize()))
                    ));
                    sb.println("    - " + p.getModel().toMsg() + " (Size: "
                            + NMemoryFormat.DEFAULT.format(NMemorySize.ofBytes(p.getSize()).normalize().canonicalize()) + ", VRAM: "
                            + NMemoryFormat.DEFAULT.format(NMemorySize.ofBytes(p.getSizeVram()).normalize().canonicalize()) + ")");
                }
            }
        }
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    private NaruStmtResult executeStart(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        boolean autoInstall = false;
        while (cmdLine.hasNext()) {
            NArg a = cmdLine.next().get();
            if (a.isOption() && (a.key().equals("--install") || a.key().equals("-i"))) {
                autoInstall = true;
            }
        }
        boolean started=false;
        boolean installed=false;

        if (autoInstall && !service.isInstalled()) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Auto-installing Ollama before launch..."));
            installed=service.install(msg -> task.log(NaruLogMode.AGENT_RESPONSE, msg));
        }

        OllamaService.StartResult r = service.start(task.session(), msg -> task.log(NaruLogMode.AGENT_RESPONSE, msg));
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
        boolean r=service.stop(task.session(), forceAll, msg -> task.log(NaruLogMode.AGENT_RESPONSE, msg));
        return NaruStmtResult.ofSuccess(r);
    }

    private NaruStmtResult executeInstall(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Starting Ollama download and installation..."));
        boolean ok = service.install(msg -> task.log(NaruLogMode.AGENT_RESPONSE, msg));
        if (ok) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofStyledSuccess("Ollama installation completed successfully."));
            OllamaInstallationInfo info = service.getInstallationInfo();
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Installed binary: %s (Version: %s)",
                    NMsg.ofStyledPrimary1(info.getExecutablePath()),
                    NMsg.ofStyledPrimary2(info.getVersion() != null ? info.getVersion() : "unknown")
            ));
            return NaruStmtResult.ofSuccess(info.isInstalled());
        } else {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Ollama installation failed. Check logs above.").asError());
            return NaruStmtResult.ofError("Ollama installation failed. Check logs above.");
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
        boolean ok = service.uninstall(purge, msg -> task.log(NaruLogMode.AGENT_RESPONSE, msg));
        if (ok) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofStyledSuccess("Ollama uninstalled."));
            return NaruStmtResult.ofSuccess(null);
        } else {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Ollama uninstallation encountered issues.").asWarning());
            return NaruStmtResult.ofError("Ollama uninstallation encountered issues.");
        }
    }

    private NaruStmtResult executePs(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        if (!service.isRunning(task.session())) {
            NMsg msg = NMsg.ofC("Ollama is not running. Use '/ollama run' to start it.").asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        List<NaruModelPsResult> ps = service.listPs(task.session());
        if (ps.isEmpty()) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("No models currently loaded in memory/VRAM."));
            return NaruStmtResult.ofSuccess(null);
        }
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s loaded model(s):", ps.size()));
        NStringBuilder sb = NStringBuilder.of();
        sb.println(ps.size() + " loaded model(s):");
        for (NaruModelPsResult element : ps) {
            NMsg msg = NMsg.ofC("  %s  size: %s  vram: %s (%s)  expires: %s",
                    element.getModel().toMsg(),
                    NMsg.ofStyledNumber(NMemoryFormat.DEFAULT.format(NMemorySize.ofBytes(element.getSize()).normalize().canonicalize())),
                    NMsg.ofStyledNumber(NMemoryFormat.DEFAULT.format(NMemorySize.ofBytes(element.getSizeVram()).normalize().canonicalize())),
                    NMsg.ofStyledNumber(
                            (element.getSize() == 0 ? "0.00" :
                                    new DecimalFormat("0.00").format((100.0 * element.getSizeVram() / element.getSize()))
                            ) + "%"
                    ),
                    element.getExpiresAt() != null ? element.getExpiresAt().toString() : "never"
            );
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            sb.println(msg.toString());
        }
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    private NaruStmtResult executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        if (!service.isRunning(task.session())) {
            NMsg msg = NMsg.ofC("Ollama is not running. Use '/ollama run' to start it.").asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        List<String> models = service.listModels(task.session());
        if (models.isEmpty()) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("No local models found in Ollama. Pull one with '/ollama pull <model>'."));
            return NaruStmtResult.ofSuccess(null);
        }
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s local model(s) available in Ollama:", models.size()));
        NStringBuilder sb = NStringBuilder.of();
        sb.println(models.size() + " local model(s) available in Ollama:");
        for (int i = 0; i < models.size(); i++) {
            NMsg msg = NMsg.ofC("  [%s] %s",
                    NMsg.ofStyledNumber(String.valueOf(i + 1)),
                    NMsg.ofStyledPrimary1(models.get(i))
            );
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            sb.println(msg.toString());
        }
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    private NaruStmtResult executePull(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NOptional<NArg> n = cmdLine.next();
        if (!n.isPresent()) {
            NMsg msg = NMsg.ofC("Error: missing model name to pull (e.g. /ollama pull qwen2.5-coder:7b)").asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        String model = n.get().image();
        try {
            service.pullModel(model, task.session(), msg -> task.log(NaruLogMode.AGENT_RESPONSE, msg));
            return NaruStmtResult.ofSuccess(null);
        } catch (Exception e) {
            NMsg msg = NMsg.ofC("Error pulling model: %s", e.getMessage()).asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
    }

    private NaruStmtResult executeDelete(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NOptional<NArg> n = cmdLine.next();
        if (!n.isPresent()) {
            NMsg msg = NMsg.ofC("Error: missing model name to delete (e.g. /ollama rm qwen2.5-coder:7b)").asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        String model = n.get().image();
        try {
            service.deleteModel(model, task.session(), msg -> task.log(NaruLogMode.AGENT_RESPONSE, msg));
            return NaruStmtResult.ofSuccess(null);
        } catch (Exception e) {
            NMsg msg = NMsg.ofC("Error deleting model: %s", e.getMessage()).asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
    }
}