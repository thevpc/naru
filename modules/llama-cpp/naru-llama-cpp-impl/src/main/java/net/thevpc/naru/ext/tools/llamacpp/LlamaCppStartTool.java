package net.thevpc.naru.ext.tools.llamacpp;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;

public class LlamaCppStartTool extends DefaultNaruTool {

    public LlamaCppStartTool() {
        super("llamacpp_start", new String[]{NaruToolTags.AI, NaruToolTags.EXECUTE});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Start the llama.cpp server (llama-server) with a local GGUF model as a background process managed by NARU. If no model is given, llamacpp.model or the first local model is used. It is stopped automatically when NARU exits.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(task),
                NaruToolParameter.string("model", "Local model id or a repository/URL to provision first (optional)", false).build(),
                NaruToolParameter.bool("auto_install", "Auto-install llama.cpp if not present before starting (default: true)", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        NaruTask task = context.task();
        LlamaCppService service = LlamaCppService.of();
        String model = context.stringArg("model").orNull();
        boolean autoInstall = context.booleanArg("auto_install").orElse(true);

        if (service.isRunning(task.session())) {
            return "llama.cpp server is already running at " + service.getUrl(task.session());
        }
        if (!service.isInstalled(task.session()) && autoInstall) {
            task.addHistory(NaruMessage.engine(NMsg.ofC("Auto-installing llama.cpp before starting...")));
            service.install(task.session(), msg -> task.addHistory(NaruMessage.engine(msg)));
        }
        LlamaCppService.StartResult result = service.start(task.session(), model, msg -> task.addHistory(NaruMessage.engine(msg)));
        if (result.started() || result.alreadyStarted()) {
            return "SUCCESS: llama.cpp started at " + service.getUrl(task.session())
                    + " with model " + (result.model() != null ? result.model() : "?")
                    + " (PID: " + LlamaCppProcessManager.getManagedPid() + ")";
        }
        return "ERROR: Failed to start llama.cpp server. Check logs.";
    }
}
