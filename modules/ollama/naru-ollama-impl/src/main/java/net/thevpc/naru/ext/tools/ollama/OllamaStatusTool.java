package net.thevpc.naru.ext.tools.ollama;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.text.NText;

public class OllamaStatusTool extends DefaultNaruTool {

    public OllamaStatusTool() {
        super("ollama_status", new String[]{NaruToolTags.AI, NaruToolTags.EXECUTE});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Check whether Ollama is installed and running, retrieve server info, response latency, loaded VRAM models, and available models.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(name(), getDescription(task));
    }

    @Override
    public String execute(NaruToolCallContext context) {
        OllamaStatus status = OllamaService.of().getStatus(context.task().session());
        return NElementWriter.ofJson()
                .formatPlain(status.toElement());
    }
}
