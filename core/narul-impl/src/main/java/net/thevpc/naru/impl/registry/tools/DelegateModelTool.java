package net.thevpc.naru.impl.registry.tools;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.naru.api.tool.NaruToolRegistry;
import net.thevpc.naru.impl.util.ImageUtil;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DelegateModelTool implements NaruTool {

    private final NaruModelProvider provider;
    private final String availableModelsDescription;

    public DelegateModelTool(NaruModelProvider provider, List<String> availableModels) {
        this.provider = provider;
        if (availableModels == null || availableModels.isEmpty()) {
            this.availableModelsDescription = "Unknown (try any model name)";
        } else {
            this.availableModelsDescription = String.join(", ", availableModels);
        }
    }

    @Override
    public String getName() {
        return "delegate_to_model";
    }

    @Override
    public String getDescription() {
        return "Delegate a sub-task to another AI model. Use this to offload vision tasks to vision models, or complex reasoning to larger models. Available models: " + availableModelsDescription;
    }

    @Override
    public NaruToolDefinition getDefinition() {
        return NaruToolRegistry.buildDefinition(
                getName(),
                getDescription(),
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

        try {
            NaruResponse response = provider.chat(modelName, messages, Collections.emptyList());
            if (response.getMessage() != null) {
                return response.getMessage().getContent();
            }
            return "Error: Model returned empty response.";
        } catch (Exception e) {
            return "Error calling model " + modelName + ": " + e.getMessage();
        }
    }
}
