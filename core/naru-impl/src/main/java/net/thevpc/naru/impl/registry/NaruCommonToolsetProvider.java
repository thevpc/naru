package net.thevpc.naru.impl.registry;

import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.naru.api.registry.NaruToolsetProvider;
import net.thevpc.naru.impl.registry.builtintools.*;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NaruCommonToolsetProvider implements NaruToolsetProvider {

    @Override
    public String name() {
        return "common";
    }

    @Override
    public List<String> supportedTypes() {
        return Arrays.asList("common", "common-fs", "common-shell", "common-maven", "common-routine", "common-web");
    }


    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        String type = NNameFormat.LOWER_KEBAB_CASE.format(id);
        switch (type) {
            case "common-fs":
                return new StaticToolset(id, fsTools());
            case "common-shell":
                return new StaticToolset(id, shellTools());
            case "common-maven":
                return new StaticToolset(id, mavenTools());
            case "common-routine":
                return new StaticToolset(id, routineTools());
            case "common-web":
                return new StaticToolset(id, webTools());
            case "common-ai":
                return new StaticToolset(id, aiTools());
            case "common":
                return new StaticToolset(id, allCommonTools());
            default:
                throw new NIllegalArgumentException(
                        NMsg.ofC("NaruCommonToolsetProvider: unknown type '%s'", type)
                );
        }
    }

    private List<NaruTool> fsTools() {
        return Arrays.asList(
                new FileReadTool(), new FileWriteTool(), new FileAppendTool(),
                new FileEditLinesTool(), new FileGrepTool(), new DiffFilesTool(),
                new GetWorkingDirTool(),new FolderFindTool(), new SetWorkingDirTool()
        );
    }

    private List<NaruTool> shellTools() {
        return Arrays.asList(
                new RunShellTool()
        );
    }

    private List<NaruTool> mavenTools() {
        return Arrays.asList(new MavenCompileTool(), new MavenTestTool());
    }

    private List<NaruTool> routineTools() {
        return Arrays.asList(
                new RoutineRunTool(), new RoutineAddLineTool(), new RoutineListLinesTool()
        );
    }

    private List<NaruTool> webTools() {
        return List.of(new SearchWebScriptTool());
    }
    private List<NaruTool> aiTools() {
        return List.of(new ModelDelegateTool());
    }

    private List<NaruTool> allCommonTools() {
        List<NaruTool> all = new ArrayList<>();
        all.addAll(fsTools());
        all.addAll(shellTools());
        all.addAll(mavenTools());
        all.addAll(routineTools());
        all.addAll(webTools());
        return all;
    }
}