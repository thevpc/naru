package net.thevpc.naru.api.context;

import net.thevpc.nuts.spi.NComponent;

/**
 * Turns a context view that has grown too large into one that fits, without losing anything
 * irreversibly.
 *
 * <p>Discovered the same way as every other NARU SPI: implement {@link NComponent}, list the
 * class in {@code META-INF/services/net.thevpc.nuts.spi.NComponent}, and the registry picks
 * it up. Optional by construction -- the core has no compaction of its own, so a build with
 * no implementation simply has the feature, and a caller that needs it gets a clear error
 * rather than a silent pass-through.
 *
 * <p>Implementations must be non-destructive. Compaction is allowed to add a summary item
 * and flag what it covers; it is not allowed to remove a history item, and it is not
 * allowed to leave the task half-modified when it fails. Both are enforced by construction
 * rather than by review: {@link NaruCompactionRequest#apply()} says whether to write at
 * all, and a compactor that fails must throw before touching anything.
 */
public interface NaruContextCompactor extends NComponent {

    /** Stable name, used to report which compactor handled a request. */
    String name();

    /**
     * Compacts the request's source view according to its spec.
     *
     * <p>When {@link NaruCompactionRequest#apply()} is true, on success the source task must
     * have the summary item inserted at the cut and {@code excludedBy} set on exactly the
     * items the summary covers. On any failure it must be untouched.
     *
     * @throws NaruCompactionException if no summary could be produced; the source task is
     *         guaranteed unmodified when this is thrown
     */
    NaruCompactionResult compact(NaruCompactionRequest request);

    /** The result of the most recent {@link #compact} call on this compactor. */
    default NaruCompactionResult lastResult() {
        return null;
    }
}