package net.thevpc.naru.ext.budget.store;

import net.thevpc.naru.ext.budget.Labels;
import net.thevpc.naru.ext.budget.NaruTokenTransaction;

import java.time.Instant;

public interface NaruBudgetRuleManager {
    NaruBudgetRuleManager addRule(NaruBudgetRule rule);
    NaruBudgetRuleManager removeRule(NaruBudgetRule rule);
    boolean acceptNext(Labels labels, Instant time);
    boolean onTransaction(NaruTokenTransaction tokenTransaction);
}
