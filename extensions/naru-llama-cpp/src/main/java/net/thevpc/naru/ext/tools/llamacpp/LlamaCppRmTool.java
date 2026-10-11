package net.thevpc.naru.ext.tools.llamacpp;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NText;

public class LlamaCppRmTool extends DefaultNaruTool {

    public LlamaCppRmTool() {
        super("llamacpp_rm", new String[]{NaruToolTags.AI, NaruToolTags.EXECUTE, NaruToolTags.WRITE});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Delete a local GGUF model (and all of its shards) from the llama.cpp models directory.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(task),
                NaruToolParameter.string("model", "Model id or file name to delete", true).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        NaruTask task = context.task();
        String model = context.stringArg("model").orNull();
        if (model == null || model.isBlank()) {
            return "ERROR: missing model to delete.";
        }
        try {
            LlamaCppService.of().rmModel(model, task.session(), msg -> task.log(NaruLogMode.AGENT_RESPONSE, msg));
            return "SUCCESS: model " + model + " deleted.";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
}
