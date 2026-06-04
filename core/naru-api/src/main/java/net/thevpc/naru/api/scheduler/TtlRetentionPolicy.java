package net.thevpc.naru.api.scheduler;

import net.thevpc.nuts.time.NDuration;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Drop after a fixed duration since firedAt.
 */
public class TtlRetentionPolicy implements NaruRetentionPolicy {
    private final Duration ttl;

    public TtlRetentionPolicy(NDuration ttl) {
        this.ttl = ttl.toDuration();
    }

    public boolean shouldDrop(NaruEvent event) {
        return Instant.now().isAfter(event.firedAt().plus(ttl));
    }

    public long nextCheckMillis(NaruEvent event) {
        Instant expiry = event.firedAt().plus(ttl);
        long remaining = Instant.now().until(expiry, ChronoUnit.MILLIS);
        return Math.max(0, remaining);
    }
}
