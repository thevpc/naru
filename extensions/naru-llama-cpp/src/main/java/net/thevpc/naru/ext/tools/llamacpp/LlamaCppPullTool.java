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

public class LlamaCppPullTool extends DefaultNaruTool {

    public LlamaCppPullTool() {
        super("llamacpp_pull", new String[]{NaruToolTags.AI, NaruToolTags.EXECUTE, NaruToolTags.NETWORK});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Download a GGUF model into the llama.cpp models directory. Accepts hf.co/<owner>/<repo>[:quant], <owner>/<repo>[:quant], hf.co/<repo>/<file>.gguf, or a direct https URL.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(task),
                NaruToolParameter.string("model", "Model repository, file or URL to download", true).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        NaruTask task = context.task();
        String spec = context.stringArg("model").orNull();
        if (spec == null || spec.isBlank()) {
            return "ERROR: missing model to pull.";
        }
        try {
            LlamaCppModelInfo info = LlamaCppService.of().pullModel(spec, task.session(),
                    msg -> task.log(NaruLogMode.AGENT_RESPONSE, msg));
            return "SUCCESS: model " + info.getId() + " available at " + info.getPath();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
}
