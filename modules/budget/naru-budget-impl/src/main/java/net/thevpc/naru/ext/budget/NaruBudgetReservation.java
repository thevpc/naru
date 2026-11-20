package net.thevpc.naru.ext.budget;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NaruBudgetReservation {
    private final String ruleId;
    private final long amount;          // the estimate that was held
    private final Instant time;         // when it was taken (picks the window/bucket)
    private final Object key;           // which counter: group key, bucket, etc. (rule-private)
    private final AtomicBoolean settled = new AtomicBoolean();

    public NaruBudgetReservation(String ruleId, long amount, Instant time, Object key) {
        this.ruleId = ruleId;
        this.amount = amount;
        this.time = time;
        this.key = key;
    }

    public String ruleId() {
        return ruleId;
    }

    public long amount() {
        return amount;
    }

    public Instant time() {
        return time;
    }

    public Object key() {
        return key;
    }

    public boolean isSettled() {
        return settled.get();
    }

    boolean markSettled() { return settled.compareAndSet(false, true); }
}
