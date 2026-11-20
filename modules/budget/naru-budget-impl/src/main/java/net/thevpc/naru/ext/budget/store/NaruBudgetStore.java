package net.thevpc.naru.ext.budget.store;

import net.thevpc.naru.ext.budget.Labels;
import net.thevpc.naru.ext.budget.NaruModelBudgetStats;
import net.thevpc.naru.ext.budget.NaruTokenTransaction;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface NaruBudgetStore {
    void saveTransaction(NaruTokenTransaction transaction);

    List<NaruTokenTransaction> findTransactions(NaruBudgetStoreQuery query);

    Set<String> findLabelValues(String label, NaruBudgetStoreQuery query);

    NaruModelBudgetStats aggregate(NaruBudgetStoreQuery query);

    long dropTransactionsBefore(Instant instant);

    /**
     * Whether one transaction satisfies a query's label constraints (equality plus
     * prefix). Time bounds are checked by the caller, which owns the ordering it can
     * rely on.
     */
    static boolean matchesLabels(NaruTokenTransaction transaction, NaruBudgetStoreQuery query) {
        if (query == null) {
            return true;
        }
        Labels labels = query.labels();
        if (labels != null && !labels.asMap().isEmpty() && !transaction.getLabels().matches(labels)) {
            return false;
        }
        for (java.util.Map.Entry<String, String> e : query.labelPrefixes().entrySet()) {
            String v = transaction.getLabels().asMap().get(e.getKey());
            if (v == null || !v.startsWith(e.getValue())) {
                return false;
            }
        }
        return true;
    }

    static boolean matchesTime(NaruTokenTransaction transaction, NaruBudgetStoreQuery query) {
        if (query == null) {
            return true;
        }
        Instant from = query.from();
        Instant to = query.to();
        if (from != null && transaction.getTimestamp().isBefore(from)) {
            return false;
        }
        return to == null || !transaction.getTimestamp().isAfter(to);
    }
}
