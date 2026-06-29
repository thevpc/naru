package net.thevpc.naru.impl.registry;

import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.naru.api.registry.NaruToolsetProvider;
import net.thevpc.naru.impl.registry.builtintools.*;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

import java.util.Arrays;
import java.util.List;

public class NaruBuiltinToolsetProvider implements NaruToolsetProvider {

    @Override
    public String name() {
        return "builtin";
    }

    @Override
    public List<String> supportedTypes() {
        return Arrays.asList("builtin");
    }


    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        String type = NNameFormat.LOWER_KEBAB_CASE.format(id);
        switch (type) {
            case "builtin":
                return new StaticToolset(id, builtins());
            default:
                throw new NIllegalArgumentException(
                        NMsg.ofC("NaruCommonToolsetProvider: unknown type '%s'", type)
                );
        }
    }

    private List<NaruTool> builtins() {
        return Arrays.asList(new ToolTagAddTool(), new ToolTagRemoveTool());
    }


}