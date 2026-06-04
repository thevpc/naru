package net.thevpc.naru.api.scheduler;

/**
 * Never drop. Event lives until session ends.
 */
public class PersistentRetentionPolicy implements NaruRetentionPolicy {
    public static final PersistentRetentionPolicy INSTANCE = new PersistentRetentionPolicy();

    public boolean shouldDrop(NaruEvent event) {
        return false;
    }

    public long nextCheckMillis(NaruEvent event) {
        return Long.MAX_VALUE; // never check again
    }
}
