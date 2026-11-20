package net.thevpc.naru.ext.tools.sh;

import net.thevpc.naru.api.registry.DefaultNaruToolset;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.naru.api.registry.NaruToolsetProvider;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NaruShToolsetProvider implements NaruToolsetProvider {

    @Override
    public String name() {
        return "sh";
    }

    @Override
    public List<String> supportedTypes() {
        return Arrays.asList("sh");
    }


    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        String type = NNameFormat.LOWER_KEBAB_CASE.format(id);
        switch (type) {
            case "sh":
                return new DefaultNaruToolset(id, shellTools());
            default:
                throw new NIllegalArgumentException(
                        NMsg.ofC(getClass().getSimpleName()+": unknown type '%s'", type)
                );
        }
    }



    private List<NaruTool> shellTools() {
        return Arrays.asList(
                new RunShellTool()
        );
    }


    private List<NaruTool> allCommonTools() {
        List<NaruTool> all = new ArrayList<>();
        all.addAll(shellTools());
        return all;
    }
}