package net.thevpc.naru.ext.budget;

import net.thevpc.nuts.time.NDuration;

import java.math.BigDecimal;
import java.util.*;

/**
 * Folds transactions into the totals {@link NaruModelBudgetStats} reports.
 *
 * <p>Cost comes from the price <em>snapshotted into each transaction</em>, not
 * from a table looked up now: that is what keeps {@code price set} from
 * rewriting the bill of calls already made. A transaction with no price adds no
 * money and increments {@code unpricedCalls}; a transaction whose cache tokens
 * were priced at the input price (because the model has no cache-read or
 * cache-write price) increments {@code approximateCalls}.
 */
public class NaruAccumulatorBuilder {
    private Map<String, Set<String>> labels = new HashMap<>();
    private long promptTokens;
    private long completionTokens;
    /**
     * Subset of {@link #promptTokens}. Tracked separately so a cache hit is
     * visible in the report instead of being indistinguishable from a cold call.
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
    /** Calls whose model had no price at track time; they contribute no money. */
    private long unpricedCalls;
    /** Calls whose cost used the input price as a stand-in for a cache price. */
    private long approximateCalls;
    /**
     * Calls that told us their cache split at all. Separate from {@link #calls}
     * because the hit ratio must be computed over only these, and a mixed group
     * has to be able to say so.
     */
    private long cacheReportedCalls;
    /**
     * Prompt tokens summed over only the calls that reported cache data, so the
     * hit ratio is not diluted by providers that report nothing.
     */
    private long promptTokensCacheReported;
    private NaruSpending spending = NaruSpending.ZERO;

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
    public void accumulate(NaruTokenTransaction part) {
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

        // Every one of these can be -1 ("provider did not report") or, in a
        // malformed transaction, negative. Clamp before any arithmetic: the
        // sentinel must never reach a total, own or derived.
        long prompt = Math.max(0, part.getPromptTokens());
        long completion = Math.max(0, part.getCompletionTokens());
        long cacheRead = Math.max(0, part.getCacheReadTokens());
        long cacheWrite = Math.max(0, part.getCacheWriteTokens());
        boolean cacheReported = part.getCacheReadTokens() >= 0 || part.getCacheWriteTokens() >= 0;

        promptTokens += prompt;
        completionTokens += completion;
        cacheReadTokens += cacheRead;
        cacheWriteTokens += cacheWrite;

        long callTokens = prompt + completion;
        // contextUsage is the size of the most recent call, not a running sum: adding
        // every call's prompt back in made a long session report a context far larger
        // than any single request. Only totalTokens accumulates; the peak is the
        // largest single call seen and must not shrink.
        contextUsage = callTokens;
        totalTokens += callTokens;
        peakContextUsage = Math.max(peakContextUsage, callTokens);
        calls++;
        accumulatedDuration += part.getDuration().toMillis();
        // "no duration recorded yet" is calls == 0, not minDuration == 0: a call
        // that really took 0 ms is a legitimate minimum, and testing for 0 would
        // keep re-adopting every such call and make min duration hover at zero.
        if (calls == 1) {
            minDuration = part.getDuration().toMillis();
            maxDuration = part.getDuration().toMillis();
        } else {
            minDuration = Math.min(minDuration, part.getDuration().toMillis());
            maxDuration = Math.max(maxDuration, part.getDuration().toMillis());
        }

        if (cacheReported) {
            cacheReportedCalls++;
            promptTokensCacheReported += prompt;
        }

        NaruUnitPrice price = part.getPrice();
        if (price == null) {
            unpricedCalls++;
        } else {
            // fresh = prompt minus what was billed as a cache read or write. Clamped
            // so a provider that reports cache tokens outside prompt (or a malformed
            // report) can never produce a negative full-rate portion.
            long fresh = Math.max(0, prompt - cacheRead - cacheWrite);
            // Prices are per 1M tokens, so the token*price product is scaled
            // down by six decimal places. movePointLeft is exact (it only shifts
            // the exponent), so no rounding happens here; money is rounded only
            // when it is printed.
            BigDecimal inputCost = price.input().multiply(BigDecimal.valueOf(fresh))
                    .add(price.cacheReadPrice().multiply(BigDecimal.valueOf(cacheRead)))
                    .add(price.cacheWritePrice().multiply(BigDecimal.valueOf(cacheWrite)))
                    .movePointLeft(6);
            BigDecimal outputCost = price.output().multiply(BigDecimal.valueOf(completion)).movePointLeft(6);
            spending = spending.add(new NaruSpending(inputCost, outputCost));
            // The true cache-read price is far below input and the cache-write price
            // above it, so defaulting either to input is an approximation -- but only
            // for tokens that actually flowed through the defaulted class.
            if ((cacheRead > 0 && !price.hasExplicitCacheRead())
                    || (cacheWrite > 0 && !price.hasExplicitCacheWrite())) {
                approximateCalls++;
            }
        }
    }

    private void accumulateLabel(String key, String value) {
        if (key != null && value != null) {
            labels.computeIfAbsent(key, s -> new HashSet<>()).add(value);
        }
    }

    public NaruModelBudgetStats build() {
        Map<String, Set<String>> labels2 = new TreeMap<>();
        for (Map.Entry<String, Set<String>> e : labels.entrySet()) {
            labels2.put(e.getKey(), new TreeSet<>(e.getValue()));
        }
        return new NaruModelBudgetStats(
                labels2, promptTokens, completionTokens, cacheWriteTokens, cacheReadTokens,
                contextUsage, peakContextUsage, totalTokens, calls, accumulatedDuration,
                minDuration, maxDuration, unpricedCalls, approximateCalls, cacheReportedCalls,
                promptTokensCacheReported, spending
        );
    }
}
