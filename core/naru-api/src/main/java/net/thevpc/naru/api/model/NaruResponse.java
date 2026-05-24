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
}
