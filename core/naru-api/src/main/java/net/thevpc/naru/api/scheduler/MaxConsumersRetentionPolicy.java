package net.thevpc.naru.api.scheduler;

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
}
