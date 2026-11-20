package net.thevpc.naru.ext.tools.java;

import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.naru.api.registry.NaruToolsetProvider;
import net.thevpc.naru.api.registry.DefaultNaruToolset;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

import java.util.Arrays;
import java.util.List;

public class NaruJavaToolsetProvider implements NaruToolsetProvider {

    @Override
    public String name() {
        return "java";
    }

    @Override
    public List<String> supportedTypes() {
        return List.of("java");
    }


    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        String type = NNameFormat.LOWER_KEBAB_CASE.format(id);
        switch (type) {
            case "java":
                return new DefaultNaruToolset(id, mavenTools());
            default:
                throw new NIllegalArgumentException(
                        NMsg.ofC(getClass().getSimpleName()+  ": unknown type '%s'", type)
                );
        }
    }





    private List<NaruTool> mavenTools() {
        return Arrays.asList(new MavenCompileTool(), new MavenTestTool(), new MavenPackageTool());
    }


}