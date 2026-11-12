package net.thevpc.naru.ext.assess;

/** Knobs of an assessment run. */
public final class AssessOptions {
    /** Multiplies every tier's wall-clock cap; local models on CPU usually need more than 1. */
    public double timeoutScale = 1.0;
    /** Null leaves the model's own thinking default; otherwise forces {@code model.thinking}. */
    public Boolean thinking;
    /** Upper bound on tool-call rounds of one model turn ({@code maxSteps}). */
    public int maxSteps = 60;
    /** Keep the work directory after scoring, to inspect what the model did. */
    public boolean keepWorkDir;

    public long timeoutMillis(AssessTier tier) {
        return Math.max(1000L, (long) (tier.defaultMinutes() * 60_000L * timeoutScale));
    }
}
