package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.impl.util.ImageUtil;
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
        String modelName = context.stringArg("model_name").orNull();
        String prompt = context.stringArg("prompt").orNull();
        String imagePath = context.stringArg("image_path").orNull();

        if (NBlankable.isBlank(modelName)) return "Error: model_name is required.";
        if (NBlankable.isBlank(prompt)) return "Error: prompt is required.";

        List<NaruMessage> messages = new ArrayList<>();
        NaruMessage msg = NaruMessage.user(prompt);

        if (!NBlankable.isBlank(imagePath)) {
            try {
                String base64 = ImageUtil.toBase64(context.task().resolve(imagePath).toString());
                msg.setImages(Collections.singletonList(base64));
            } catch (Exception e) {
                return "Error loading image: " + e.getMessage();
            }
        }


        messages.add(msg);
        NaruModelConfig model = context.task().session().findModel(modelName).orNull();
        if (model == null) {
            return "Error: Model not found : " + modelName;
        }
        NaruModelConfig oldModel = context.task().model();
        context.task().setModel(model);
        Map<String, NElement> env = context.task().context(NaruSource.values()).env();
        try {
            NaruResponse response = context.task().chat(model,
                    new NaruModelRequest(messages,
                            env
                    )
            );
            if (response.getMessage() != null) {
                return response.getMessage().getContent();
            }
            return "Error: Model returned empty response.";
        } catch (Exception e) {
            return "Error calling model " + modelName + ": " + e.getMessage();
        } finally {
            context.task().setModel(oldModel);
        }
    }
}
