package net.thevpc.naru.ext.budget.store;

import net.thevpc.naru.ext.budget.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

public class InMemoryNaruBudgetStore implements NaruBudgetStore {
    private final List<NaruTokenTransaction> allTransactions = new ArrayList<>();

    @Override
    public void saveTransaction(NaruTokenTransaction transaction) {
        synchronized (allTransactions) {
            allTransactions.add(transaction);
        }
    }

    @Override
    public NaruModelBudgetStats aggregate(NaruBudgetStoreQuery query, NAruUnitBudgetSupplier supplier) {
        NaruAccumulatorBuilder b = new NaruAccumulatorBuilder();
        visitTransactions(query, t -> b.accumulate(t, supplier));
        return b.build();
    }

    @Override
    public List<NaruTokenTransaction> findTransactions(NaruBudgetStoreQuery query) {
        List<NaruTokenTransaction> ret = new ArrayList<>();
        visitTransactions(query, ret::add);
        return ret;
    }

    @Override
    public Set<String> findLabelValues(String label, NaruBudgetStoreQuery query) {
        Set<String> ret = new TreeSet<>();
        visitTransactions(query, t->{
            String v = t.getLabels().asMap().get(label);
            if(v!=null){
                ret.add(v);
            }
        });
        return ret;
    }

    public void visitTransactions(NaruBudgetStoreQuery query, Consumer<NaruTokenTransaction> consumer) {
        synchronized (allTransactions) {
            Instant f = query == null ? null : query.from();
            Instant t = query == null ? null : query.to();
            Labels labels = query == null ? null : query.labels();
            for (NaruTokenTransaction transaction : allTransactions) {
                if (query != null) {
                    if (f != null) {
                        if (transaction.getTimestamp().isBefore(f)) {
                            continue;
                        }
                    }
                    if (t != null) {
                        if (transaction.getTimestamp().isAfter(t)) {
                            break;
                        }
                    }
                    if (labels != null && !labels.matches(transaction.getLabels())) {
                        continue;
                    }
                }
                consumer.accept(transaction);
            }
        }
    }

    @Override
    public long dropTransactionsBefore(Instant instant) {
        long count = 0;
        synchronized (allTransactions) {
            if (!allTransactions.isEmpty()) {
                NaruTokenTransaction a = allTransactions.getFirst();
                if (a.getTimestamp().isBefore(instant)) {
                    allTransactions.remove(a);
                    count++;
                } else {
                    return count;
                }
            }
        }
        return count;
    }
}
