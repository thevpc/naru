package net.thevpc.naru.ext.budget;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public record NaruModelBudgetStats(

        Map<String, Set<String>> labels,
        long promptTokens,
        long completionTokens,
        /**
         * Subset of {@link #promptTokens}. Tracked separately so a cache hit is
         * visible in /stats instead of being indistinguishable from a cold call.
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
        NaruSpending spending) {


    /**
     * Share of all input tokens that were served from cache, in [0,1], or -1
     * when nothing has been reported and no ratio can be computed.
     */
    public double cacheHitRatio() {
        if (promptTokens <= 0) {
            return -1;
        }
        return Math.min(1.0, (double) Math.max(0, cacheReadTokens) / promptTokens);
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
