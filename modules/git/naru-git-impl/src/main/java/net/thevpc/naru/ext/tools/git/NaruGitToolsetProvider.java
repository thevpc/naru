package net.thevpc.naru.ext.tools.git;

import net.thevpc.naru.api.registry.DefaultNaruToolset;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.naru.api.registry.NaruToolsetProvider;
import net.thevpc.naru.ext.tools.git.tools.*;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

import java.util.Arrays;
import java.util.List;

public class NaruGitToolsetProvider implements NaruToolsetProvider {

    @Override
    public String name() {
        return "git";
    }

    @Override
    public List<String> supportedTypes() {
        return Arrays.asList("git");
    }

    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        String type = NNameFormat.LOWER_KEBAB_CASE.format(id);
        switch (type) {
            case "git":
                return new DefaultNaruToolset(id, gitTools());
            default:
                throw new NIllegalArgumentException(
                        NMsg.ofC(getClass().getSimpleName() + ": unknown type '%s'", type)
                );
        }
    }

    private static List<NaruTool> gitTools() {
        return Arrays.asList(
                new GitStatusTool(),
                new GitDiffTool(),
                new GitLogTool(),
                new GitCommitTool()
        );
    }
}
