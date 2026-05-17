package net.thevpc.naru.impl.budget;

import net.thevpc.naru.api.model.NaruModelKey;

import java.math.BigDecimal;

public class NaruModelStatsAccumulator {
    private NaruModelKey model;
    private String userId;
    private long promptTokens;
    private long completionTokens;
    private long contextUsage;
    private long peakContextUsage;
    private long contextSize;
    private long totalTokens;
    private long calls;
    private long accumulatedDuration;
    private long minDuration;
    private long maxDuration;
    private BigDecimal unitBudget;
    private BigDecimal totalTokensBudget;

    public long getMinDuration() {
        return minDuration;
    }

    public NaruModelStatsAccumulator setMinDuration(long minDuration) {
        this.minDuration = minDuration;
        return this;
    }

    public long getMaxDuration() {
        return maxDuration;
    }

    public NaruModelStatsAccumulator setMaxDuration(long maxDuration) {
        this.maxDuration = maxDuration;
        return this;
    }

    public long getCalls() {
        return calls;
    }

    public NaruModelStatsAccumulator setCalls(long calls) {
        this.calls = calls;
        return this;
    }

    public long getAccumulatedDuration() {
        return accumulatedDuration;
    }

    public NaruModelStatsAccumulator setAccumulatedDuration(long accumulatedDuration) {
        this.accumulatedDuration = accumulatedDuration;
        return this;
    }

    public BigDecimal getUnitBudget() {
        return unitBudget;
    }

    public NaruModelStatsAccumulator setUnitBudget(BigDecimal unitBudget) {
        this.unitBudget = unitBudget;
        return this;
    }

    public BigDecimal getTotalTokensBudget() {
        return totalTokensBudget;
    }

    public NaruModelStatsAccumulator setTotalTokensBudget(BigDecimal totalTokensBudget) {
        this.totalTokensBudget = totalTokensBudget;
        return this;
    }

    public long getPeakContextUsage() {
        return peakContextUsage;
    }

    public NaruModelStatsAccumulator setPeakContextUsage(long peakContextUsage) {
        this.peakContextUsage = peakContextUsage;
        return this;
    }

    public String getUserId() {
        return userId;
    }

    public NaruModelStatsAccumulator setUserId(String userId) {
        this.userId = userId;
        return this;
    }

    public NaruModelKey getModel() {
        return model;
    }

    public NaruModelStatsAccumulator setModel(NaruModelKey model) {
        this.model = model;
        return this;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public NaruModelStatsAccumulator setPromptTokens(long promptTokens) {
        this.promptTokens = promptTokens;
        return this;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public NaruModelStatsAccumulator setCompletionTokens(long completionTokens) {
        this.completionTokens = completionTokens;
        return this;
    }

    public long getContextUsage() {
        return contextUsage;
    }

    public NaruModelStatsAccumulator setContextUsage(long contextUsage) {
        this.contextUsage = contextUsage;
        return this;
    }

    public long getContextSize() {
        return contextSize;
    }

    public NaruModelStatsAccumulator setContextSize(long contextSize) {
        this.contextSize = contextSize;
        return this;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public NaruModelStatsAccumulator setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
        return this;
    }
}
