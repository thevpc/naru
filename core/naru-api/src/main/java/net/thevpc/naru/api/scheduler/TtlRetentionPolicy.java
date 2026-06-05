package net.thevpc.naru.api.scheduler;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.time.NDuration;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

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

    @Override
    public NElement toElement() {
        return NElement.ofNamedUplet("ttl",
                NElement.ofString(NDuration.ofDuration(ttl).toString())
        );
    }

    @Override
    public String toString() {
        return toElement().toString();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        TtlRetentionPolicy that = (TtlRetentionPolicy) o;
        return Objects.equals(ttl, that.ttl);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(ttl);
    }
}
