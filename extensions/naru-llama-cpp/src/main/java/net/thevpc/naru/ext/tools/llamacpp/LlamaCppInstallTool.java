package net.thevpc.naru.ext.tools.llamacpp;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;

public class LlamaCppInstallTool extends DefaultNaruTool {

    public LlamaCppInstallTool() {
        super("llamacpp_install", new String[]{NaruToolTags.AI, NaruToolTags.EXECUTE});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Download and install the prebuilt llama.cpp bundle for the host operating system (Linux, macOS, Windows) and the configured backend (cpu, cuda, vulkan, rocm, sycl, openvino).");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(name(), getDescription(task));
    }

    @Override
    public String execute(NaruToolCallContext context) {
        NaruTask task = context.task();
        boolean ok = LlamaCppService.of().install(task.session(), msg -> task.addHistory(NaruMessage.engine(msg)));
        if (ok) {
            LlamaCppInstallationInfo info = LlamaCppService.of().getInstallationInfo(task.session());
            return "SUCCESS: llama.cpp installed at " + info.getExecutablePath() + " (Version: " + info.getVersion() + ")";
        }
        return "ERROR: llama.cpp installation failed. Check logs.";
    }
}
