package net.thevpc.naru.api.scheduler;

import net.thevpc.nuts.elem.NElement;

/**
 * Never drop. Event lives until session ends.
 */
public class ForgetRetentionPolicy implements NaruRetentionPolicy {
    public static final ForgetRetentionPolicy INSTANCE = new ForgetRetentionPolicy();

    public boolean shouldDrop(NaruEvent event) {
        return true;
    }

    public long nextCheckMillis(NaruEvent event) {
        return 0; // always check
    }

    @Override
    public NElement toElement() {
        return NElement.ofName("forget");
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ForgetRetentionPolicy that = (ForgetRetentionPolicy) o;
        return true;
    }

    @Override
    public int hashCode() {
        return 7;
    }
}
