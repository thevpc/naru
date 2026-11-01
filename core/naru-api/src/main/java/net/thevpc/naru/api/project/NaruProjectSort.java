package net.thevpc.naru.api.project;

/**
 * How a project listing is ordered. The order also defines what a page cursor encodes:
 * a cursor minted under one sort is rejected by queries naming the other.
 */
public enum NaruProjectSort {
    /** Newest {@code updatedAt} first — the default, matching the session catalog. */
    UPDATED_DESC,
    /** Name order, case-insensitive after normalization. */
    NAME_ASC
}
