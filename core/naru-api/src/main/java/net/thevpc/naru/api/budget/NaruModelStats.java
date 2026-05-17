package net.thevpc.naru.api.budget;

import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.nuts.time.NDuration;

import java.math.BigDecimal;

public class NaruModelStats {
    private final NaruModelKey model;
    private final String userId;
    private final long promptTokens;
    private final long completionTokens;
    private final long contextUsage;
    private final long peakContextUsage;
    private final long contextSize;
    private final long totalTokens;
    private BigDecimal unitBudget;
    private BigDecimal totalTokensBudget;
    private long callsCount;
    private NDuration avgDuration;
    private NDuration maxDuration;
    private NDuration minDuration;

    public NaruModelStats(NaruModelKey model, String userId, long promptTokens, long completionTokens, long contextUsage, long peakContextUsage,
                          long contextSize, long totalTokens, BigDecimal unitBudget, BigDecimal totalTokensBudget,
                          long callsCount,NDuration minDuration,NDuration avgDuration,NDuration maxDuration) {
        this.model = model;
        this.userId = userId;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.contextUsage = contextUsage;
        this.contextSize = contextSize;
        this.peakContextUsage = peakContextUsage;
        this.totalTokens = totalTokens;
        this.unitBudget = unitBudget;
        this.totalTokensBudget = totalTokensBudget;
        this.avgDuration = avgDuration;
        this.maxDuration = maxDuration;
        this.minDuration = minDuration;
        this.callsCount = callsCount;
    }

    public NDuration getMinDuration() {
        return minDuration;
    }

    public long getCallsCount() {
        return callsCount;
    }

    public NDuration getAvgDuration() {
        return avgDuration;
    }

    public NDuration getMaxDuration() {
        return maxDuration;
    }

    public BigDecimal getUnitBudget() {
        return unitBudget;
    }

    public BigDecimal getTotalTokensBudget() {
        return totalTokensBudget;
    }

    public String getUserId() {
        return userId;
    }

    public long getPeakContextUsage() {
        return peakContextUsage;
    }

    public NaruModelKey getModel() {
        return model;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public long getContextUsage() {
        return contextUsage;
    }

    public long getContextSize() {
        return contextSize;
    }

    public long getTotalTokens() {
        return totalTokens;
    }
}
