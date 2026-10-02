package net.thevpc.naru.api.context;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruSummaryInfo;

import java.util.List;

/**
 * The outcome of one compaction.
 *
 * <p>A failed compaction is a thrown exception, never an instance of this with a null
 * summary. A caller that gets a result has a summary, and a summary that was applied has
 * already been written -- so there is no "half done" state to represent, and no way to
 * accidentally act on one.
 */
public final class NaruCompactionResult {

    /** Why a compaction did not happen. */
    public enum Outcome {
        /** A summary was produced, and applied to the source task. */
        APPLIED,
        /** A summary was produced and returned, with {@code apply=false}. */
        PRODUCED,
        /** There was nothing old enough to compact. Not an error. */
        NOTHING_TO_COMPACT,
        /**
         * A summary for exactly this content and options already existed and was reused.
         * The covered items are unchanged, so applying it is a no-op worth skipping.
         */
        CACHE_HIT,
        /** Compaction was attempted and failed. The source is untouched. */
        FAILED
    }

    private final Outcome outcome;
    private final NaruMessage summaryItem;
    private final NaruSummaryInfo summary;
    private final long coveredTokens;
    private final long summaryTokens;
    private final long coveredItemCount;
    private final String modelUsed;
    private final String message;
    private final List<String> skippedModels;

    private NaruCompactionResult(Outcome outcome, NaruMessage summaryItem, NaruSummaryInfo summary,
                                 long coveredTokens, long summaryTokens, long coveredItemCount,
                                 String modelUsed, String message, List<String> skippedModels) {
        this.outcome = outcome;
        this.summaryItem = summaryItem;
        this.summary = summary;
        this.coveredTokens = coveredTokens;
        this.summaryTokens = summaryTokens;
        this.coveredItemCount = coveredItemCount;
        this.modelUsed = modelUsed;
        this.message = message;
        this.skippedModels = skippedModels == null ? List.of() : List.copyOf(skippedModels);
    }

    public static NaruCompactionResult applied(NaruMessage summaryItem, long coveredTokens,
                                              long summaryTokens, long coveredItemCount,
                                              String modelUsed, List<String> skippedModels) {
        return new NaruCompactionResult(Outcome.APPLIED, summaryItem, summaryItem == null ? null : summaryItem.getSummary(),
                coveredTokens, summaryTokens, coveredItemCount, modelUsed, null, skippedModels);
    }

    public static NaruCompactionResult produced(NaruMessage summaryItem, long coveredTokens,
                                                long summaryTokens, long coveredItemCount,
                                                String modelUsed, List<String> skippedModels) {
        return new NaruCompactionResult(Outcome.PRODUCED, summaryItem, summaryItem == null ? null : summaryItem.getSummary(),
                coveredTokens, summaryTokens, coveredItemCount, modelUsed, null, skippedModels);
    }

    public static NaruCompactionResult cacheHit(NaruMessage summaryItem, String modelUsed) {
        return new NaruCompactionResult(Outcome.CACHE_HIT, summaryItem,
                summaryItem == null ? null : summaryItem.getSummary(),
                summaryItem == null || summaryItem.getSummary() == null ? 0 : summaryItem.getSummary().coveredTokens(),
                summaryItem == null || summaryItem.getSummary() == null ? 0 : summaryItem.getSummary().summaryTokens(),
                summaryItem == null || summaryItem.getSummary() == null ? 0 : summaryItem.getSummary().coveredItemCount(),
                modelUsed, null, List.of());
    }

    public static NaruCompactionResult nothingToCompact() {
        return new NaruCompactionResult(Outcome.NOTHING_TO_COMPACT, null, null, 0, 0, 0, null,
                "nothing older than the window", List.of());
    }

    public static NaruCompactionResult failed(String message) {
        return new NaruCompactionResult(Outcome.FAILED, null, null, 0, 0, 0, null, message, List.of());
    }

    public Outcome outcome() {
        return outcome;
    }

    public boolean isSuccess() {
        return outcome == Outcome.APPLIED || outcome == Outcome.PRODUCED || outcome == Outcome.CACHE_HIT;
    }

    public NaruMessage summaryItem() {
        return summaryItem;
    }

    public NaruSummaryInfo summary() {
        return summary;
    }

    public long coveredTokens() {
        return coveredTokens;
    }

    public long summaryTokens() {
        return summaryTokens;
    }

    public long coveredItemCount() {
        return coveredItemCount;
    }

    /** Tokens the context view shed. Negative when the summary grew it. */
    public long savedTokens() {
        return coveredTokens - summaryTokens;
    }

    public String modelUsed() {
        return modelUsed;
    }

    /** Human-readable explanation, always set when {@link #outcome()} is not a success. */
    public String message() {
        return message;
    }

    /**
     * Models that were in the configured list and were not used, each with the reason.
     *
     * <p>Worth reporting: a compaction that silently used an expensive model because the
     * cheap one was rate limited is exactly the thing a user needs to see.
     */
    public List<String> skippedModels() {
        return skippedModels;
    }

    @Override
    public String toString() {
        return "NaruCompactionResult{" + outcome
                + " covered=" + coveredItemCount + " items " + coveredTokens + "->" + summaryTokens
                + " by " + modelUsed + (message == null ? "" : " (" + message + ")") + "}";
    }
}