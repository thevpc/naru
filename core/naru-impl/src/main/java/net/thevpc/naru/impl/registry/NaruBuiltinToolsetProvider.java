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

public class NaruBuiltinToolsetProvider implements NaruToolsetProvider {

    @Override
    public String name() {
        return "builtin";
    }

    @Override
    public List<String> supportedTypes() {
        return Arrays.asList("builtin", "builtin-fs", "builtin-shell", "builtin-maven", "builtin-routine", "builtin-web");
    }


    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        String type = NNameFormat.LOWER_KEBAB_CASE.format(
                config.getStringValue("type").orElse(""));
        switch (type) {
            case "builtin-fs":
                return new StaticToolset(id, fsTools());
            case "builtin-shell":
                return new StaticToolset(id, shellTools());
            case "builtin-maven":
                return new StaticToolset(id, mavenTools());
            case "builtin-routine":
                return new StaticToolset(id, routineTools());
            case "builtin-web":
                return new StaticToolset(id, webTools());
            case "builtin":
                return new StaticToolset(id, allBuiltinTools());
            default:
                throw new NIllegalArgumentException(
                        NMsg.ofC("NaruBuiltinToolsetProvider: unknown type '%s'", type)
                );
        }
    }

    private List<NaruTool> fsTools() {
        return Arrays.asList(
                new FileReadTool(), new FileWriteTool(), new FileAppendTool(),
                new FileEditLinesTool(), new FileGrepTool(), new DiffFilesTool()
        );
    }

    private List<NaruTool> shellTools() {
        return Arrays.asList(
                new RunShellTool(), new SetWorkingDirTool(),
                new GetWorkingDirTool(), new FolderFindTool()
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

    private List<NaruTool> allBuiltinTools() {
        List<NaruTool> all = new ArrayList<>();
        all.addAll(fsTools());
        all.addAll(shellTools());
        all.addAll(mavenTools());
        all.addAll(routineTools());
        all.addAll(webTools());
        return all;
    }
}