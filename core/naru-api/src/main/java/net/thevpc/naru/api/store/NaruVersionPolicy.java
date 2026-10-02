package net.thevpc.naru.api.store;

import java.time.Duration;

/**
 * How aggressively a version sweep removes history nobody has asked to keep.
 *
 * <p>Three independent limits, because "keep the useful stuff" is three different questions:
 *
 * <ul>
 *   <li>{@code keepHead} -- never drop the version HEAD points at, whatever the other
 *       limits say. A session with no restore point is a session whose work cannot be
 *       recovered, so this is not negotiable and is not a field.</li>
 *   <li>{@code keepCount} -- the newest N commits, so {@code /session restore <n>} keeps
 *       working for the recent past even under an aggressive policy.</li>
 *   <li>{@code maxAge} -- nothing older than this, which is what actually bounds the disk
 *       when a session is left running for a month.</li>
 * </ul>
 *
 * <p>Any limit set to a negative value is off. All three off means the sweep removes
 * nothing except unreferenced objects.
 */
public class NaruVersionPolicy {

    private final int keepCount;
    private final Duration maxAge;

    public NaruVersionPolicy(int keepCount, Duration maxAge) {
        this.keepCount = keepCount;
        this.maxAge = maxAge;
    }

    public static NaruVersionPolicy keepEverything() {
        return new NaruVersionPolicy(-1, null);
    }

    /**
     * A default worth having: the head, the last fifty commits, and nothing older than
     * ninety days. Fifty is enough for a session to be walked back through by hand, and
     * ninety days is longer than a half-finished piece of work tends to survive.
     */
    public static NaruVersionPolicy defaults() {
        return new NaruVersionPolicy(50, Duration.ofDays(90));
    }

    public int keepCount() {
        return keepCount;
    }

    public Duration maxAge() {
        return maxAge;
    }

    /**
     * Whether count or age protects this version, ignoring HEAD. The caller must check
     * HEAD separately and first -- {@code keepHead} is not a field here precisely so that
     * no policy expression can forget it.
     *
     * @param headSeq the sequence number HEAD currently points at; versions are counted
     *                back from it, so this is what makes "the last fifty" meaningful
     */
    public boolean protects(NaruVersionRef v, long headSeq, java.time.Instant now) {
        if (keepCount >= 0 && v.seq() > 0 && v.seq() <= headSeq) {
            if (headSeq - v.seq() < keepCount) {
                return true;
            }
        }
        if (maxAge != null && v.createdInstant() != null) {
            long ageMs = now.toEpochMilli() - v.createdInstant().toEpochMilli();
            return ageMs < maxAge.toMillis();
        }
        return false;
    }

    @Override
    public String toString() {
        return "NaruVersionPolicy{keepCount=" + keepCount + ", maxAge=" + maxAge + '}';
    }
}