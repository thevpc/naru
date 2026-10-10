package net.thevpc.naru.ext.tools.llm;

import net.thevpc.naru.api.registry.DefaultNaruToolset;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.naru.api.registry.NaruToolsetProvider;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

import java.util.Arrays;
import java.util.List;

public class NaruLlmToolsetProvider implements NaruToolsetProvider {

    @Override
    public String name() {
        return "llm";
    }

    @Override
    public List<String> supportedTypes() {
        return Arrays.asList("llm");
    }


    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        String type = NNameFormat.LOWER_KEBAB_CASE.format(id);
        switch (type) {
            case "llm":
                return new DefaultNaruToolset(id, aiTools());
            default:
                throw new NIllegalArgumentException(
                        NMsg.ofC(getClass().getSimpleName()+": unknown type '%s'", type)
                );
        }
    }






    private List<NaruTool> aiTools() {
        return List.of(new ModelDelegateTool(), new ModelListTool());
    }

}