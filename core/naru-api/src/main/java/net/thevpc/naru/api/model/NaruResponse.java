package net.thevpc.naru.api.model;

/**
 * Response from a {@link NaruModelProvider} chat call.
 */
public class NaruResponse {

    private NaruMessage message;
    private boolean done;
    private String stopReason;
    /** Total tokens used (prompt + completion), -1 if not reported */
    private int totalTokens = -1;
    private int promptTokens = -1;
    private int evalTokens = -1;
    /**
     * Tokens billed at the cache-write rate because they were not already
     * cached. -1 if the provider does not report cache accounting.
     */
    private int cacheWriteTokens = -1;
    /**
     * Tokens billed at the discounted cache-read rate because they were served
     * from a cache hit. -1 if the provider does not report cache accounting.
     *
     * <p>These are a breakdown <em>of</em> {@code promptTokens}, not an
     * addition to it: a cache-read token was also an input token. Cost models
     * must subtract them from the input total before pricing, or they will
     * double-count the discount as if it were extra spend.
     */
    private int cacheReadTokens = -1;

    public NaruResponse() {}

    public NaruResponse(NaruMessage message, boolean done) {
        this.message = message;
        this.done = done;
    }

    public NaruResponse(NaruMessage message, boolean done, String stopReason, int totalTokens, int promptTokens, int evalTokens) {
        this.message = message;
        this.done = done;
        this.stopReason = stopReason;
        this.totalTokens = totalTokens;
        this.promptTokens = promptTokens;
        this.evalTokens = evalTokens;
    }

    public NaruMessage getMessage() { return message; }
    public void setMessage(NaruMessage message) { this.message = message; }

    public boolean isDone() { return done; }
    public void setDone(boolean done) { this.done = done; }

    public String getStopReason() { return stopReason; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }

    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }

    public boolean hasToolCalls() {
        return message != null && message.hasToolCalls();
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public NaruResponse setPromptTokens(int promptTokens) {
        this.promptTokens = promptTokens;
        return this;
    }

    public int getEvalTokens() {
        return evalTokens;
    }

    public NaruResponse setEvalTokens(int evalTokens) {
        this.evalTokens = evalTokens;
        return this;
    }

    public int getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    public NaruResponse setCacheWriteTokens(int cacheWriteTokens) {
        this.cacheWriteTokens = cacheWriteTokens;
        return this;
    }

    public int getCacheReadTokens() {
        return cacheReadTokens;
    }

    public NaruResponse setCacheReadTokens(int cacheReadTokens) {
        this.cacheReadTokens = cacheReadTokens;
        return this;
    }

    /**
     * Whether the provider gave a cache breakdown at all. Callers must not
     * assume zero when this is false: "not reported" and "reported as zero"
     * mean very different things for cost.
     */
    public boolean hasCacheAccounting() {
        return cacheReadTokens >= 0 || cacheWriteTokens >= 0;
    }
}
