package net.thevpc.naru.ext.tools.semantic;

import net.thevpc.naru.api.registry.DefaultNaruToolset;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.naru.api.registry.NaruToolsetProvider;
import net.thevpc.naru.ext.tools.semantic.tools.SemanticIndexTool;
import net.thevpc.naru.ext.tools.semantic.tools.SemanticSearchTool;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

import java.util.Arrays;
import java.util.List;

public class NaruSemanticToolsetProvider implements NaruToolsetProvider {

    @Override
    public String name() {
        return "semantic";
    }

    @Override
    public List<String> supportedTypes() {
        return Arrays.asList("semantic");
    }

    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        String type = NNameFormat.LOWER_KEBAB_CASE.format(id);
        switch (type) {
            case "semantic":
                return new DefaultNaruToolset(id, semanticTools());
            default:
                throw new NIllegalArgumentException(
                        NMsg.ofC(getClass().getSimpleName() + ": unknown type '%s'", type)
                );
        }
    }

    private static List<NaruTool> semanticTools() {
        return Arrays.asList(
                new SemanticSearchTool(),
                new SemanticIndexTool()
        );
    }
}
