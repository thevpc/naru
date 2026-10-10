package net.thevpc.naru.ext.tools.ollama;

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

import java.util.List;
import net.thevpc.nuts.text.NText;

public class OllamaPsTool extends DefaultNaruTool {

    public OllamaPsTool() {
        super("ollama_ps", new String[]{NaruToolTags.AI, NaruToolTags.EXECUTE});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("List models currently active/loaded in GPU VRAM or system RAM on the Ollama server.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(name(), getDescription(task));
    }

    @Override
    public String execute(NaruToolCallContext context) {
        NaruTask task = context.task();
        List<NaruModelPsResult> ps = OllamaService.of().listPs(task.session());
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
        return NElementWriter.ofJson()
                .formatPlain(arr.build());
    }
}
