package net.thevpc.naru.ext.budget;

import java.time.Instant;

public record NaruBudgetStatus(
        String ruleId,
        String description,       // human-readable, e.g. "5h rolling, per agent, tokens"
        long limit,
        long committed,           // settled spend inside the current window
        long reserved,            // in-flight, not yet settled
        Instant windowStart,      // for rolling windows: now - window
        Instant resetsAt          // see below
) {
    long remaining()  { return Math.max(0, limit - committed - reserved); }
    double usedRatio() { return limit == 0 ? 1.0 : (double)(committed + reserved) / limit; }
}
