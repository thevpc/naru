package net.thevpc.naru.impl.registry;

import net.thevpc.naru.api.registry.DefaultNaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTagProvider;
import net.thevpc.naru.api.registry.NaruToolTags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NaruBuiltinToolTagProvider implements NaruToolTagProvider {
    private final List<NaruToolTag> all = new ArrayList<>();

    public NaruBuiltinToolTagProvider() {
        all.add(new DefaultNaruToolTag(NaruToolTags.FILE_SYSTEM, "file system operations including add,edit,search files"));
        all.add(new DefaultNaruToolTag(NaruToolTags.ROUTINE, "routine operations including add,edit,search routines"));
        all.add(new DefaultNaruToolTag(NaruToolTags.AI, "ai operations including calling other llms/models"));
        all.add(new DefaultNaruToolTag(NaruToolTags.NETWORK, "networking operations including search web"));
        all.add(new DefaultNaruToolTag(NaruToolTags.DEV, "development operations including compile and test"));
        all.add(new DefaultNaruToolTag(NaruToolTags.WRITE, "write operations enabling persistent modifications in files, folders, databases"));
        all.add(new DefaultNaruToolTag(NaruToolTags.EXECUTE, "execute operations involving the spawning of new processes of tasks"));
        all.add(new DefaultNaruToolTag(NaruToolTags.MCP, "MCP tools"));
    }

    @Override
    public String name() {
        return "builtin";
    }

    @Override
    public List<NaruToolTag> tags() {
        return Collections.unmodifiableList(all);
    }
}
