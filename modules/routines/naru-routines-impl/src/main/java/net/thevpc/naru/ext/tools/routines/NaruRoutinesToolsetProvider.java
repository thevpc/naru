package net.thevpc.naru.ext.tools.routines;

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

public class NaruRoutinesToolsetProvider implements NaruToolsetProvider {

    @Override
    public String name() {
        return "routines";
    }

    @Override
    public List<String> supportedTypes() {
        return Arrays.asList("routines");
    }


    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        String type = NNameFormat.LOWER_KEBAB_CASE.format(id);
        switch (type) {
            case "routines":
                return new DefaultNaruToolset(id, routineTools());
            default:
                throw new NIllegalArgumentException(
                        NMsg.ofC(getClass().getSimpleName()+": unknown type '%s'", type)
                );
        }
    }





    private List<NaruTool> routineTools() {
        return Arrays.asList(
                new RoutineRunTool(), new RoutineAddLineTool(), new RoutineListLinesTool()
        );
    }

}