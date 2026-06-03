package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.impl.util.ImageUtil;
import net.thevpc.naru.impl.util.ToolHelper;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ModelDelegateTool implements NaruTool {

    public ModelDelegateTool() {
    }

    @Override
    public String name() {
        return "delegate_to_model";
    }

    private String availableModelsDescription(NaruSession session) {
        List<NaruModelInfo> models = session.registry().modelsInfos(session);
        if (models.isEmpty()) {
            return "Unknown (try any model name)";
        } else {
            return String.join(", ", models.toString());
        }

    }

    @Override
    public String getDescription(NaruSession session) {
        return "Delegate a sub-task to another AI model. Use this to offload vision tasks to vision models, or complex reasoning to larger models. Available models: "
                + availableModelsDescription(session);
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(session),
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
