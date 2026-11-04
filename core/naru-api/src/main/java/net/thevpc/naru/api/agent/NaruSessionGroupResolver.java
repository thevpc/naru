package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.registry.NaruAgentExtension;
import net.thevpc.nuts.util.NOptional;

/**
 * Turns a session-group reference typed by a person (or stored in a script) into the
 * {@link NaruSessionGroup} a new session should be seeded from.
 *
 * <p>This is the only way the kernel learns a session's starting folder and user from a
 * short name, and it deliberately knows nothing about what the groups <em>are</em>: the
 * bundled project manager registers one resolver, another feature could register a resolver
 * over bookmarks or a team roster, and a build with no resolver at all simply rejects any
 * group reference at session creation. Everything else — the tree, the rename rules, the
 * per-group counts — belongs to the feature that owns the groups, reached through
 * {@link NaruWorkspace#extension(String, Class)}.
 *
 * <p>A resolver is an agent extension, not a plain SPI bean: it is created once per
 * workspace, {@link NaruAgentExtension#open(NaruWorkspace) opened} with it, and shares the
 * workspace's store handles. Resolving a reference therefore never opens a second view of
 * the same folder. Implementations are listed in
 * {@code META-INF/services/net.thevpc.nuts.spi.NComponent}.
 */
public interface NaruSessionGroupResolver extends NaruAgentExtension {

    /**
     * Resolve {@code ref}, or empty when this resolver knows nothing of it — which lets the
     * next resolver try. A resolver must not throw for an unknown reference.
     */
    NOptional<NaruSessionGroup> resolve(String ref);
}
