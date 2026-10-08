package net.thevpc.naru.ext.tools.java;

import net.thevpc.naru.api.registry.DefaultNaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTagProvider;
import net.thevpc.naru.api.registry.NaruToolTags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NaruJavaToolTagProvider implements NaruToolTagProvider {
    private final List<NaruToolTag> all = new ArrayList<>();

    public NaruJavaToolTagProvider() {
        all.add(new DefaultNaruToolTag(NaruToolTags.DEV, "development operations including compile and test"));
    }

    @Override
    public String name() {
        return "java";
    }

    @Override
    public List<NaruToolTag> tags() {
        return Collections.unmodifiableList(all);
    }
}
