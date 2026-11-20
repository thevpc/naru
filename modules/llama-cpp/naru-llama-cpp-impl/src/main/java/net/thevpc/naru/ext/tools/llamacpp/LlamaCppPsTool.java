package net.thevpc.naru.ext.tools.llamacpp;

import net.thevpc.naru.api.model.NaruModelPsResult;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.text.NText;

import java.util.List;

public class LlamaCppPsTool extends DefaultNaruTool {

    public LlamaCppPsTool() {
        super("llamacpp_ps", new String[] {NaruToolTags.AI, NaruToolTags.EXECUTE});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("List the model currently served by the running llama.cpp server.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(name(), getDescription(task));
    }

    @Override
    public String execute(NaruToolCallContext context) {
        NaruTask task = context.task();
        List<NaruModelPsResult> ps = LlamaCppService.of().listPs(task.session());
        NArrayElementBuilder arr = NArrayElementBuilder.of();
        for (NaruModelPsResult p : ps) {
            NObjectElementBuilder obj = NObjectElementBuilder.of();
            obj.set("model", p.getModel().model());
            obj.set("sizeBytes", p.getSize());
            obj.set("sizeVramBytes", p.getSizeVram());
            if (p.getExpiresAt() != null) {
                obj.set("expiresAt", p.getExpiresAt().toString());
            }
            arr.add(obj.build());
        }
        return NElementWriter.ofJson().formatPlain(arr.build());
    }
}
