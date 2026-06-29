package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.DefaultNaruTool;
import net.thevpc.naru.impl.util.ToolHelper;

import java.util.List;

public class ModelDelegateTool extends DefaultNaruTool {

    public ModelDelegateTool() {
        super("delegate_to_model", new String[]{NaruToolTags.AI});
    }


    @Override
    public String name() {
        return "delegate_to_model";
    }

    private String availableModelsDescription(NaruTask task) {
        List<NaruModelInfo> models = task.session().registry().modelsInfos(task.session());
        if (models.isEmpty()) {
            return "Unknown (try any model name)";
        } else {
            return String.join(", ", models.toString());
        }

    }

    @Override
    public String getDescription(NaruTask task) {
        return "Delegate a sub-task to another AI model. Use this to offload vision tasks to vision models, or complex reasoning to larger models. Available models: "
                + availableModelsDescription(task);
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(task),
                NaruToolParameter.string("model_name", "The exact name of the model to use.", true).build(),
                NaruToolParameter.string("prompt", "The task description or question for the model.", true).build(),
                NaruToolParameter.string("image_path", "Optional absolute path to an image file if this is a vision task.", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return ToolHelper.callModel(context.task()
                ,context.stringArg("model_name").orNull()
                ,context.stringArg("prompt").orNull()
                ,context.stringArg("image_path").orNull()
        );
    }
}
