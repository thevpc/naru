package net.thevpc.naru.ext.budget;

/**
 * Where a {@link NaruUnitPrice} came from.
 *
 * <p>The distinction is load-bearing: a user-entered price is an assertion NARU is
 * not allowed to overwrite, while a fetched one is a cache of somebody else's
 * published number and may be refreshed. Refresh never replaces {@link #USER}.
 */
public enum NaruPriceSource {
    USER,
    FETCHED
}
