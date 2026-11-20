package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.registry.DefaultNaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTagProvider;

import java.util.List;

/**
 * Declares the {@code skills} tool tag and owns the {@code skill} tool that wears it (WP5).
 * <p>
 * The tag is opt-in: a task that was not granted {@code skills} does not see the
 * {@code skill} tool, and the skills extension therefore does not advertise its catalog
 * either. Installing the jar makes the tag available; granting it is a task decision, the
 * same as every other tool tag in NARU.
 */
public class NaruSkillsToolTagProvider implements NaruToolTagProvider {

    /** Tool tag owned by this feature, not by the core tag registry. */
    public static final String SKILLS_TAG = "skills";

    @Override
    public String name() {
        return SKILLS_TAG;
    }

    @Override
    public List<NaruToolTag> tags() {
        return List.of(new DefaultNaruToolTag(SKILLS_TAG,
                "Load a skill's instructions on demand"));
    }
}
