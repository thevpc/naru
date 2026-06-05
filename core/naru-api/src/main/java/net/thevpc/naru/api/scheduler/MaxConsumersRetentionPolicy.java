package net.thevpc.naru.api.scheduler;

import net.thevpc.nuts.elem.NElement;

import java.util.Objects;

/**
 * Drop after at least N distinct tasks have consumed it.
 */
public class MaxConsumersRetentionPolicy implements NaruRetentionPolicy {
    private final int maxConsumers;

    public MaxConsumersRetentionPolicy(int maxConsumers) {
        this.maxConsumers = maxConsumers;
    }

    public boolean shouldDrop(NaruEvent event) {
        return event.consumedCount() >= maxConsumers;
    }

    public long nextCheckMillis(NaruEvent event) {
        return 0; // check every GC tick
    }

    @Override
    public NElement toElement() {
        return NElement.ofNamedUplet("max",NElement.ofInt(maxConsumers));
    }

    @Override
    public String toString() {
        return toElement().toString();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        MaxConsumersRetentionPolicy that = (MaxConsumersRetentionPolicy) o;
        return maxConsumers == that.maxConsumers;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(maxConsumers);
    }
}
