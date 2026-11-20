package net.thevpc.naru.ext.tools.semantic;

import net.thevpc.naru.api.registry.DefaultNaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTagProvider;
import net.thevpc.naru.api.registry.NaruToolTags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NaruSemanticToolTagProvider implements NaruToolTagProvider {
    private final List<NaruToolTag> all = new ArrayList<>();

    public NaruSemanticToolTagProvider() {
        all.add(new DefaultNaruToolTag(NaruToolTags.SEMANTIC, "Semantic code search and vector indexing tools"));
    }

    @Override
    public String name() {
        return "semantic";
    }

    @Override
    public List<NaruToolTag> tags() {
        return Collections.unmodifiableList(all);
    }
}
