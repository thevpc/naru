package net.thevpc.naru.api.scheduler;

public interface NaruRetentionPolicy {
    boolean shouldDrop(NaruEvent event);
    long nextCheckMillis(NaruEvent event);
}
