package net.thevpc.naru.api.store;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;

import java.util.List;

/**
 * The audit log of one session: an append-only record of every provider call attempt.
 *
 * <p>Payloads are stored out of line. {@link #append} takes the bodies as raw text and the
 * store decides where they go; {@link #read} hands them back resolved. A caller that wants
 * the references without resolving them -- to display a listing, or to measure what a
 * prune would reclaim -- asks for {@link #listRefs}.
 */
public interface NaruAuditStore {

    /**
     * Appends one record.
     *
     * @param requestBody  the exact bytes sent, or null
     * @param responseBody the exact bytes received, or null
     */
    void append(NaruAuditRecord record, String requestBody, String responseBody);

    /**
     * Records from newest to oldest, with bodies resolved.
     *
     * @param limit at most this many, or negative for all
     * @param since only records at or after this instant, or null for all
     */
    List<NaruAuditRecord> read(int limit, java.time.Instant since);

    /**
     * Records from newest to oldest with bodies left as references. Cheap enough to build
     * a listing from, which {@link #read} is not.
     */
    List<NaruAuditRecord> listRefs(int limit, java.time.Instant since);

    /** The stored body a reference points at, or empty if it has been pruned. */
    NOptional<NElement> resolveBody(String ref);

    /**
     * Drops records the retention does not keep, then sweeps the blobs nothing refers to.
     *
     * <p>In that order, and never the other way round: sweeping first would delete bodies
     * the surviving records still point at, and the log would be left holding references to
     * nothing -- which is exactly the corruption the split exists to prevent.
     */
    NaruAuditPruneResult prune(NaruAuditRetention retention);

    /** Total bytes of stored bodies, for {@code /session audit stats}. */
    long payloadBytes();
}