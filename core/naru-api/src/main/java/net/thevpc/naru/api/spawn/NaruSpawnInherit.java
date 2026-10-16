package net.thevpc.naru.api.spawn;

import net.thevpc.nuts.util.NOptional;

/**
 * A kind of state a spawned task may inherit from its parent as a spawn-time snapshot.
 * <p>
 * Skills are deliberately <em>not</em> here: the skills extension already resolves a
 * task's active skills at read time by walking {@code parentId()}, so seeding a spawn-time
 * snapshot on top of that would double the set. Skills are only ever <em>added</em> at a
 * spawn (flags or contract); their ancestor-driven inheritance is the extension's own
 * mechanism.
 */
public enum NaruSpawnInherit {
    /** Copy the parent's granted tool tags (snapshot, later parent changes never reach the child). */
    TAGS,
    /** Copy the parent's task environment (snapshot). */
    ENV,
    /**
     * Copy the parent's user-defined budget labels (snapshot). Unlike {@link #TAGS} and
     * {@link #ENV}, this kind is inherited <em>by default</em>: a caller who enumerates no
     * inherit set at all still gets label inheritance, because labels are descriptive
     * dimensions a child almost always shares with its spawner. A caller who enumerates
     * another inherit set without {@code labels} opts out.
     */
    LABELS;

    public static NOptional<NaruSpawnInherit> parse(String value) {
        if (value == null) {
            return NOptional.ofNullable(null);
        }
        String s = value.trim();
        if (s.isEmpty()) {
            return NOptional.ofNullable(null);
        }
        for (NaruSpawnInherit k : values()) {
            if (k.name().equalsIgnoreCase(s)) {
                return NOptional.of(k);
            }
        }
        return NOptional.ofNullable(null);
    }
}