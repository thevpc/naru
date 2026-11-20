package net.thevpc.naru.ext.tools.tags;

import net.thevpc.naru.api.registry.DefaultNaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTagProvider;
import net.thevpc.naru.api.registry.NaruToolTags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NaruTagsToolTagProvider implements NaruToolTagProvider {

    private final List<NaruToolTag> all = new ArrayList<>();

    public NaruTagsToolTagProvider() {
        // The tag the tag tools carry. Without a provider for it the tag was
        // never registered, so it could not be granted: tag_add stayed invisible
        // while tag_remove -- whose tags() was overridden to the empty set --
        // showed up for everyone. A tag that gates the tools changing tags has
        // to exist before it can gate anything.
        all.add(new DefaultNaruToolTag(NaruToolTags.TAGS, "grant and revoke tool tags at runtime"));
    }

    @Override
    public String name() {
        return "tags";
    }

    @Override
    public List<NaruToolTag> tags() {
        return Collections.unmodifiableList(all);
    }
}