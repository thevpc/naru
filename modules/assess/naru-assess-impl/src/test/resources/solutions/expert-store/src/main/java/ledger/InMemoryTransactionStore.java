package ledger;

import java.util.ArrayList;
import java.util.List;

public class InMemoryTransactionStore implements TransactionStore {
    private final List<Transaction> list = new ArrayList<>();

    @Override
    public void append(Transaction t) {
        list.add(t);
    }

    @Override
    public List<Transaction> all() {
        return List.copyOf(list);
    }
}
