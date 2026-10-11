package net.thevpc.naru.ext.tools.llamacpp;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.text.NText;

public class LlamaCppStatusTool extends DefaultNaruTool {

    public LlamaCppStatusTool() {
        super("llamacpp_status", new String[]{NaruToolTags.AI, NaruToolTags.EXECUTE});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Check whether llama.cpp is installed and its server is running, with platform, version, latency, available GGUF models and currently loaded model.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(name(), getDescription(task));
    }

    @Override
    public String execute(NaruToolCallContext context) {
        LlamaCppStatus status = LlamaCppService.of().getStatus(context.task().session());
        return NElementWriter.ofJson().formatPlain(status.toElement());
    }
}
