package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.registry.DefaultNaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTagProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NaruPlanToolTagProvider implements NaruToolTagProvider {

    /** Tool tag owned by this feature, not by the core tag registry. */
    public static final String PLAN_TAG = "plan";

    private final List<NaruToolTag> all = new ArrayList<>();

    public NaruPlanToolTagProvider() {
        all.add(new DefaultNaruToolTag(PLAN_TAG, "Planning tools"));
    }

    @Override
    public String name() {
        return "plan";
    }

    @Override
    public List<NaruToolTag> tags() {
        return Collections.unmodifiableList(all);
    }
}
