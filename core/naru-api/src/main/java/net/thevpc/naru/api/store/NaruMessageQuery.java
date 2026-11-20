package net.thevpc.naru.api.store;

import net.thevpc.naru.api.agent.NaruRole;

import java.time.Instant;
import java.util.Set;

/** Keyset query over message creation order. The cursor is opaque to callers. */
public record NaruMessageQuery(String cursor, NaruMessageQueryDirection direction, int limit,
                               Set<NaruRole> roles, Instant createdFrom, Instant createdTo) {
    public NaruMessageQuery {
        direction = direction == null ? NaruMessageQueryDirection.FORWARD : direction;
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public static NaruMessageQuery first(int limit) {
        return new NaruMessageQuery(null, NaruMessageQueryDirection.FORWARD, limit, Set.of(), null, null);
    }
}
