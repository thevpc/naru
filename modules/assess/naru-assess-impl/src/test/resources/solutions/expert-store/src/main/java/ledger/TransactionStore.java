package ledger;

import java.util.List;

public interface TransactionStore {
    void append(Transaction t);

    List<Transaction> all();
}
