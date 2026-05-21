package net.thevpc.naru.impl.tools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.naru.impl.util.ImageUtil;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ModelDelegateTool implements NaruTool {

    public ModelDelegateTool() {
    }

    @Override
    public String getName() {
        return "delegate_to_model";
    }

    private String availableModelsDescription(NaruSession session) {
        List<NaruModelInfo> models = session.registry().modelsInfos();
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
        return NaruRegistry.buildDefinition(
                getName(),
                getDescription(session),
                NaruToolParameter.string("model_name", "The exact name of the model to use.", true),
                NaruToolParameter.string("prompt", "The task description or question for the model.", true),
                NaruToolParameter.string("image_path", "Optional absolute path to an image file if this is a vision task.", false)
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
                String base64 = ImageUtil.toBase64(context.session().resolve(imagePath).toString());
                msg.setImages(Collections.singletonList(base64));
            } catch (Exception e) {
                return "Error loading image: " + e.getMessage();
            }
        }

        messages.add(msg);
        NaruModelKey model = context.session().findModel(modelName).orNull();
        if (model == null) {
            return "Error: Model not found : " + modelName;
        }
        try {
            NaruResponse response = context.session().chat(model, messages, Collections.emptyList());
            if (response.getMessage() != null) {
                return response.getMessage().getContent();
            }
            return "Error: Model returned empty response.";
        } catch (Exception e) {
            return "Error calling model " + modelName + ": " + e.getMessage();
        }
    }
}
