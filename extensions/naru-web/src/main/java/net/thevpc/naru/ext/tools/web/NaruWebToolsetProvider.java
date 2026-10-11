package net.thevpc.naru.ext.tools.web;

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

public class NaruWebToolsetProvider implements NaruToolsetProvider {

    @Override
    public String name() {
        return "web";
    }

    @Override
    public List<String> supportedTypes() {
        return Arrays.asList("web");
    }

    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        String type = NNameFormat.LOWER_KEBAB_CASE.format(id);
        switch (type) {
            case "web":
                return new DefaultNaruToolset(id, webTools());
            default:
                throw new NIllegalArgumentException(
                        NMsg.ofC(getClass().getSimpleName()+": unknown type '%s'", type)
                );
        }
    }

    private List<NaruTool> webTools() {
        return List.of(new SearchWebScriptTool(), new WebFetchTool());
    }

}
