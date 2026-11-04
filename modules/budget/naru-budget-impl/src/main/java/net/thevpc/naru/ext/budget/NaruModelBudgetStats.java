package net.thevpc.naru.ext.budget;

import java.util.Map;
import java.util.Set;

/**
 * Totals for one group of transactions (by default a provider, or a model).
 *
 * <p>Token counts are cumulative; {@code contextUsage} is the size of the most
 * recent call and {@code peakContextUsage} the largest single call seen. Cost is
 * the snapshot-priced {@link NaruSpending}, with the unpriced and approximate
 * call counts alongside so a report can say what the number does not cover.
 */
public record NaruModelBudgetStats(

        Map<String, Set<String>> labels,
        long promptTokens,
        long completionTokens,
        /**
         * Subset of {@link #promptTokens}. Tracked separately so a cache hit is
         * visible in the report instead of being indistinguishable from a cold call.
         */
        long cacheWriteTokens,
        long cacheReadTokens,
        long contextUsage,
        long peakContextUsage,
        long totalTokens,
        long calls,
        long accumulatedDuration,
        long minDuration,
        long maxDuration,
        /**
         * Calls whose model had no price when they were tracked. They contribute
         * no money and are reported so a priced total is never read as complete.
         */
        long unpricedCalls,
        /**
         * Calls whose cost priced cache tokens at the input price because the
         * model had no explicit cache-read/cache-write price.
         */
        long approximateCalls,
        /**
         * Calls that reported a cache split at all. A group where this is smaller
         * than {@link #calls} reports a partial hit ratio, not a full one.
         */
        long cacheReportedCalls,
        /**
         * Prompt tokens summed over only the calls that reported cache data, the
         * denominator for the hit ratio.
         */
        long promptTokensCacheReported,
        NaruSpending spending) {


    /**
     * Share of reported input tokens that were served from cache, in [0,1], or -1
     * when no call in the group reported a cache split and no ratio can be
     * computed.
     *
     * <p>Computed over only the transactions that reported cache data: a provider
     * that never reports would otherwise drag the ratio towards zero and make a
     * cache-heavy model look cold.
     */
    public double cacheHitRatio() {
        if (cacheReportedCalls <= 0 || promptTokensCacheReported <= 0) {
            return -1;
        }
        return Math.min(1.0, (double) Math.max(0, cacheReadTokens) / promptTokensCacheReported);
    }

    /**
     * True when the group mixes reporting and non-reporting calls, so the hit
     * ratio covers only part of it.
     */
    public boolean cacheRatioPartial() {
        return cacheReportedCalls > 0 && cacheReportedCalls < calls;
    }

    public boolean isApproximate() {
        return approximateCalls > 0;
    }

    public String providerKey() {
        return labelKey("provider");
    }

    public String modelKey() {
        return labelKey("model");
    }

    public String fullModelKey() {
        return labelKey("fullModel");
    }

    public String userKey() {
        return labelKey("user");
    }

    public String labelKey(String name) {
        if(labels==null || labels.isEmpty()){
            return null;
        }
        Set<String> s = labels.get(name);
        if(s==null || s.isEmpty()){
            return null;
        }
        if(s.size()==1){
            for (String ss : s) {
                return ss;
            }
        }
        return String.join("|", s);
    }
}
