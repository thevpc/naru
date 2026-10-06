package net.thevpc.naru.ext.budget;

import net.thevpc.nuts.time.NDuration;

import java.math.BigDecimal;
import java.util.*;

public class NaruAccumulatorBuilder {
    private Map<String, Set<String>> labels = new HashMap<>();
    private long promptTokens;
    private long completionTokens;
    /**
     * Subset of {@link #promptTokens}. Tracked separately so a cache hit is
     * visible in /stats instead of being indistinguishable from a cold call.
     */
    private long cacheWriteTokens;
    private long cacheReadTokens;
    private long contextUsage;
    private long peakContextUsage;
    private long totalTokens;
    private long calls;
    private long accumulatedDuration;
    private long minDuration;
    private long maxDuration;
    private NaruSpending spending = new NaruSpending(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    /**
     * Folds one call into an accumulator, atomically.
     *
     * <p>The map is concurrent but the accumulator is a mutable bag of counters, so
     * {@code get} + {@code set} on two fields is not atomic: two parallel calls could
     * both read the same total and one increment would vanish. Nothing here is a lock
     * over the whole service -- unrelated models accumulate in parallel -- so the
     * accumulator instance itself is the monitor, and a read takes the same monitor to
     * get a snapshot that is internally consistent rather than a mix of two calls.
     */
    public void accumulate(NaruTokenTransaction part, NAruUnitBudgetSupplier supplier) {
        // These are running totals across every call made against this
        // model/user pair, so they must add. Assigning here would silently
        // report only the most recent call's prompt and completion sizes,
        // making a busy session look like a single-request one and understating
        // spend for budget purposes.
        for (Map.Entry<String, String> e : part.getLabels().asMap().entrySet()) {
            accumulateLabel(e.getKey(), e.getValue());
        }
        accumulateLabel("fullModel", part.getModel().name());
        accumulateLabel("provider", part.getModel().provider());
        accumulateLabel("model", part.getModel().model());
        accumulateLabel("user", part.getUserId());
        promptTokens += part.getPromptTokens();
        completionTokens += part.getCompletionTokens();

        // A provider that does not report cache accounting uses -1. Treat that
        // as zero so it cannot drag a running total backwards.
        if (part.getCacheWriteTokens() > 0) {
            cacheWriteTokens += part.getCacheWriteTokens();
        }
        if (part.getCacheReadTokens() > 0) {
            cacheReadTokens += part.getCacheReadTokens();
        }

        long callTokens = part.getPromptTokens() + part.getCompletionTokens();
        contextUsage += callTokens;
        totalTokens += callTokens;
        peakContextUsage += callTokens;
        calls++;
        accumulatedDuration += part.getDuration().toMillis();
        // "no duration recorded yet" is calls == 0, not minDuration == 0: a call
        // that really took 0 ms is a legitimate minimum, and testing for 0 would
        // keep re-adopting every such call and make min duration hover at zero.
        if (calls == 1) {
            minDuration = (part.getDuration().toMillis());
            maxDuration = (part.getDuration().toMillis());
        } else {
            minDuration = Math.min(minDuration, part.getDuration().toMillis());
            maxDuration = (Math.max(maxDuration, part.getDuration().toMillis()));
        }
        NaruSpending unitBudget = supplier.getInputUnitBudget(part.getLabels());
        NaruSpending tokensBD = new NaruSpending(new BigDecimal(part.getPromptTokens()), new BigDecimal(part.getCompletionTokens()), BigDecimal.ZERO);
        spending = spending.add(unitBudget.mul(tokensBD));
    }

    private void accumulateLabel(String key,String value){
        if(key!=null && value!=null){
            labels.computeIfAbsent(key, s -> new HashSet<>()).add(value);
        }
    }



    public NaruModelBudgetStats build() {
        Map<String, Set<String>> labels2 = new TreeMap<>();
        for (Map.Entry<String, Set<String>> e : labels.entrySet()) {
            labels2.put(e.getKey(), new TreeSet<>(e.getValue()));
        }
        return new NaruModelBudgetStats(
                labels2, promptTokens, completionTokens, cacheWriteTokens, cacheReadTokens, contextUsage, peakContextUsage, totalTokens, calls, accumulatedDuration, minDuration, maxDuration, spending
        );
    }

}
