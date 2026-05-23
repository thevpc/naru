package net.thevpc.naru.api.budget;

import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.nuts.time.NDuration;

import java.time.Instant;

public class NaruTokenTransaction {
    private final String sessionId;
    private final String userId;
    private final NaruModelConfig model;
    private final long promptTokens;
    private final long completionTokens;
    private final Instant timestamp;
    private final NDuration duration;

    public NaruTokenTransaction(String sessionId, String userId, NaruModelConfig model, long promptTokens, long completionTokens, Instant timestamp, NDuration duration) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.timestamp = timestamp;
        this.duration = duration;
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

    public Instant getTimestamp() {
        return timestamp;
    }
}
