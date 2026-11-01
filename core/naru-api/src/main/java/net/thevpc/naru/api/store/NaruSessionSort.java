package net.thevpc.naru.api.store;

/**
 * Ordering of a session query. v1 implements modification-time order only — the one the
 * catalog has always used; a name sort is a separate, additive decision for later, not a
 * half-implementation to keep honest today.
 */
public enum NaruSessionSort {
    /** Newest {@code modificationInstant} first; ties broken by id. The default. */
    UPDATED_DESC
}
