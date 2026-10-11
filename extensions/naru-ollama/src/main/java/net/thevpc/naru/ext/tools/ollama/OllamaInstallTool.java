package net.thevpc.naru.ext.tools.ollama;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NText;

public class OllamaInstallTool extends DefaultNaruTool {

    public OllamaInstallTool() {
        super("ollama_install", new String[]{NaruToolTags.AI, NaruToolTags.EXECUTE});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Download and install Ollama for the host operating system (Linux, macOS, Windows).");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(name(), getDescription(task));
    }

    @Override
    public String execute(NaruToolCallContext context) {
        NaruTask task = context.task();
        boolean ok = OllamaService.of().install(msg -> task.log(NaruLogMode.AGENT_RESPONSE, msg));
        if (ok) {
            OllamaInstallationInfo info = OllamaService.of().getInstallationInfo();
            return "SUCCESS: Ollama installed at " + info.getExecutablePath() + " (Version: " + info.getVersion() + ")";
        } else {
            return "ERROR: Ollama installation failed. Check logs.";
        }
    }
}
