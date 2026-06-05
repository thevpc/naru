package net.thevpc.naru.api.scheduler;

import net.thevpc.nuts.elem.NElement;
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

    @Override
    public NElement toElement() {
        return NElement.ofName("default");
    }

    @Override
    public String toString() {
        return toElement().toString();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        DefaultRetentionPolicy that = (DefaultRetentionPolicy) o;
        return true;
    }

    @Override
    public int hashCode() {
        return 11;
    }
}
