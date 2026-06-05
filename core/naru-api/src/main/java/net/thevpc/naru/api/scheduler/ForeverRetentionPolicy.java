package net.thevpc.naru.api.scheduler;

import net.thevpc.nuts.elem.NElement;

/**
 * Never drop. Event lives until session ends.
 */
public class ForeverRetentionPolicy implements NaruRetentionPolicy {
    public static final ForeverRetentionPolicy INSTANCE = new ForeverRetentionPolicy();

    public boolean shouldDrop(NaruEvent event) {
        return false;
    }

    public long nextCheckMillis(NaruEvent event) {
        return Long.MAX_VALUE; // never check again
    }


    @Override
    public NElement toElement() {
        return NElement.ofName("forever");
    }

    @Override
    public String toString() {
        return toElement().toString();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ForeverRetentionPolicy that = (ForeverRetentionPolicy) o;
        return true;
    }

    @Override
    public int hashCode() {
        return 41;
    }
}
