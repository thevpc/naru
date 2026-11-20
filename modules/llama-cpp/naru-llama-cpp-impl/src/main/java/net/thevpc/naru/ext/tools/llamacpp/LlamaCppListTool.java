package net.thevpc.naru.ext.tools.llamacpp;

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

public class LlamaCppListTool extends DefaultNaruTool {

    public LlamaCppListTool() {
        super("llamacpp_list", new String[]{NaruToolTags.AI, NaruToolTags.EXECUTE});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("List the local GGUF models available to llama.cpp.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(name(), getDescription(task));
    }

    @Override
    public String execute(NaruToolCallContext context) {
        NArrayElementBuilder arr = NArrayElementBuilder.of();
        for (LlamaCppModelInfo m : LlamaCppService.of().listModels(context.task().session())) {
            NObjectElementBuilder obj = NObjectElementBuilder.of();
            obj.set("id", m.getId());
            obj.set("file", m.getFileName());
            obj.set("sizeBytes", m.getSizeBytes());
            obj.set("sharded", m.isSharded());
            arr.add(obj.build());
        }
        return NElementWriter.ofJson().formatPlain(arr.build());
    }
}
