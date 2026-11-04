package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.registry.DefaultNaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTagProvider;

import java.util.List;

/**
 * The {@code perm} tag — the grant a model needs before it may change rules.
 *
 * <p>Listing and checking need no tag: reading the policy back is what lets the
 * model reason about its own limits. Granting and revoking are what need a tag,
 * because a model that can widen its own permissions has none.
 */
public class NaruPermToolTagProvider implements NaruToolTagProvider {

    @Override
    public String name() {
        return "permissions";
    }

    @Override
    public List<NaruToolTag> tags() {
        return List.of(new DefaultNaruToolTag("perm",
                "read the access policy and, when granted, add or remove rules"));
    }
}
