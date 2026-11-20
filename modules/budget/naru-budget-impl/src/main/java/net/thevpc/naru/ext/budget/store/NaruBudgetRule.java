package net.thevpc.naru.ext.budget.store;

import net.thevpc.naru.ext.budget.*;

import java.time.Duration;
import java.time.Instant;

public interface NaruBudgetRule {
    String id();
    boolean matches(Labels labels);
    Duration lookback();                       // for rebuild; Duration.ZERO if stateless

    NaruBudgetDecision tryReserve(Labels labels, Instant time, long estimate);
    void commit(NaruBudgetReservation r, NaruTokenTransaction tx);   // settles estimate to actual
    void release(NaruBudgetReservation r);                           // call failed or interrupted
    void replay(NaruTokenTransaction tx);                  // rebuild only, no reservation
    NaruBudgetStatus status(Labels labels, Instant time);            // used, limit, resetsAt
}
