package net.thevpc.naru.ext.tools.index;

import net.thevpc.naru.api.registry.DefaultNaruToolset;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.naru.api.registry.NaruToolsetProvider;
import net.thevpc.naru.ext.tools.index.tools.*;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

import java.util.Arrays;
import java.util.List;

public class NaruIndexToolsetProvider implements NaruToolsetProvider {
    @Override
    public String name() { return "index"; }

    @Override
    public List<String> supportedTypes() { return Arrays.asList("index"); }

    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        String type = NNameFormat.LOWER_KEBAB_CASE.format(id);
        switch (type) {
            case "index":
                return new DefaultNaruToolset(id, indexTools());
            default:
                throw new NIllegalArgumentException(
                        NMsg.ofC(getClass().getSimpleName() + ": unknown type '%s'", type)
                );
        }
    }

    private static List<NaruTool> indexTools() {
        return Arrays.asList(
            new ProjectMapTool(),
            new CodeSymbolsTool(),
            new FindSymbolTool(),
            new ProjectSummaryTool()
        );
    }
}
