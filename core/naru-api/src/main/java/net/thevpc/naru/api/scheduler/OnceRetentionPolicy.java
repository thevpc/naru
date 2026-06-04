package net.thevpc.naru.api.scheduler;

/**
 * Drop after exactly one consumption — unicast semantics.
 */
public class OnceRetentionPolicy implements NaruRetentionPolicy {
    public static final OnceRetentionPolicy INSTANCE = new OnceRetentionPolicy();

    public boolean shouldDrop(NaruEvent event) {
        return event.consumedCount() > 0;
    }

    public long nextCheckMillis(NaruEvent event) {
        return 0;
    }
}
