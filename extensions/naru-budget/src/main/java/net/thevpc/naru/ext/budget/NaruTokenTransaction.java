package net.thevpc.naru.ext.budget;

import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.nuts.time.NDuration;

import java.time.Instant;

public class NaruTokenTransaction {
    private final Labels labels;
    private final String sessionId;
    private final String userId;
    private final NaruModelConfig model;
    private final long promptTokens;
    private final long completionTokens;
    /**
     * Input tokens billed at the cache-write rate, or -1 when the provider
     * reports no cache accounting.
     */
    private final long cacheWriteTokens;
    /**
     * Input tokens billed at the discounted cache-read rate, or -1 when the
     * provider reports no cache accounting. These are a subset of
     * {@code promptTokens}, not an addition to it.
     */
    private final long cacheReadTokens;
    private final Instant timestamp;
    private final NDuration duration;

    public NaruTokenTransaction(String sessionId, String userId, NaruModelConfig model, long promptTokens, long completionTokens, Instant timestamp, NDuration duration) {
        this(sessionId, userId, model, promptTokens, completionTokens, -1, -1, timestamp, duration);
    }

    public NaruTokenTransaction(String sessionId, String userId, NaruModelConfig model, long promptTokens, long completionTokens,
                                long cacheWriteTokens, long cacheReadTokens, Instant timestamp, NDuration duration) {
        this(buildLabels(sessionId, userId, model), sessionId, userId, model, promptTokens, completionTokens,
                cacheWriteTokens, cacheReadTokens, timestamp, duration);
    }

    public NaruTokenTransaction(Labels labels, String sessionId, String userId, NaruModelConfig model, long promptTokens, long completionTokens,
                                long cacheWriteTokens, long cacheReadTokens, Instant timestamp, NDuration duration) {
        this.labels = labels == null ? buildLabels(sessionId, userId, model) : labels;
        this.sessionId = sessionId;
        this.userId = userId;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.cacheWriteTokens = cacheWriteTokens;
        this.cacheReadTokens = cacheReadTokens;
        this.timestamp = timestamp;
        this.duration = duration;
    }

    private static Labels buildLabels(String sessionId, String userId, NaruModelConfig model) {
        Labels.Builder b = Labels.builder();
        if (sessionId != null) {
            b.add("session", sessionId);
        }
        if (userId != null) {
            b.add("user", userId);
        }
        if (model != null) {
            if (model.provider() != null) {
                b.add("provider", model.provider());
            }
            if (model.model() != null) {
                b.add("model", model.model());
            }
        }
        return b.build();
    }

    public Labels getLabels() {
        return labels;
    }

    public NDuration getDuration() {
        return duration;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public NaruModelConfig getModel() {
        return model;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public long getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    public long getCacheReadTokens() {
        return cacheReadTokens;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
