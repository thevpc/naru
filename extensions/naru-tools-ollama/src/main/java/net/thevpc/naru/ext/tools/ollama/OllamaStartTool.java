package net.thevpc.naru.ext.tools.ollama;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NMsg;

public class OllamaStartTool extends DefaultNaruTool {

    public OllamaStartTool() {
        super("ollama_start", new String[]{NaruToolTags.AI, NaruToolTags.EXECUTE});
    }

    @Override
    public String getDescription(NaruTask task) {
        return "Start the Ollama server (ollama serve) as a background process managed by NARU. It will be automatically stopped when NARU exits.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(task),
                NaruToolParameter.bool("auto_install", "Auto-install Ollama if not present before starting (default: true)", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        boolean autoInstall = context.booleanArg("auto_install").orElse(true);
        NaruTask task = context.task();
        OllamaService service = OllamaService.of();

        if (service.isRunning(task.session())) {
            return "Ollama is already running at " + service.getOllamaUrl(task.session());
        }

        if (!service.isInstalled() && autoInstall) {
            task.log(NaruLogMode.TRACE, NMsg.ofC("Auto-installing Ollama before starting..."));
            service.install(msg -> task.log(NaruLogMode.TRACE, msg));
        }

        OllamaService.StartResult result = service.start(task.session(), msg -> task.log(NaruLogMode.TRACE, msg));
        if (result.started() || result.alreadyStarted()) {
            return "SUCCESS: Ollama started at " + service.getOllamaUrl(task.session()) + " (PID: " + OllamaProcessManager.getManagedPid() + ")";
        } else {
            return "ERROR: Failed to start Ollama server. Check logs.";
        }
    }
}
