package net.thevpc.naru.ext.budget.store;

import net.thevpc.naru.ext.budget.NAruUnitBudgetSupplier;
import net.thevpc.naru.ext.budget.NaruModelBudgetStats;
import net.thevpc.naru.ext.budget.NaruTokenTransaction;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface NaruBudgetStore {
    void saveTransaction(NaruTokenTransaction transaction);

    List<NaruTokenTransaction> findTransactions(NaruBudgetStoreQuery query);

    Set<String> findLabelValues(String label, NaruBudgetStoreQuery query);

    NaruModelBudgetStats aggregate(NaruBudgetStoreQuery query, NAruUnitBudgetSupplier supplier);

    long dropTransactionsBefore(Instant instant);
}
