package net.thevpc.naru.api.scheduler;

import net.thevpc.nuts.time.NDuration;

public class DefaultRetentionPolicy implements NaruRetentionPolicy {
    public static final DefaultRetentionPolicy INSTANCE = new DefaultRetentionPolicy();

    // TTL of 1 hour OR once consumed by target — whichever comes first
    private final NaruRetentionPolicy delegate = new AnyRetentionPolicy(
            new TtlRetentionPolicy(NDuration.ofHours(1)),
            new OnceRetentionPolicy()
    );

    public boolean shouldDrop(NaruEvent event) {
        return delegate.shouldDrop(event);
    }

    public long nextCheckMillis(NaruEvent event) {
        return delegate.nextCheckMillis(event);
    }
}
