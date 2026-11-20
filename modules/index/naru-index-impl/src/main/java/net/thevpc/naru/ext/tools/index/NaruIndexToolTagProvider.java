package net.thevpc.naru.ext.tools.index;

import net.thevpc.naru.api.registry.DefaultNaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTagProvider;
import net.thevpc.naru.api.registry.NaruToolTags;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NaruIndexToolTagProvider implements NaruToolTagProvider {
    private final List<NaruToolTag> all = new ArrayList<>();

    public NaruIndexToolTagProvider() {
        all.add(new DefaultNaruToolTag(NaruToolTags.INDEX, "Codebase indexing and symbol search tools"));
    }

    @Override
    public String name() { return "index"; }

    @Override
    public List<NaruToolTag> tags() { return Collections.unmodifiableList(all); }
}
