package ledger;

import java.time.LocalDate;

/** A posted movement on one account. Positive amounts are credits, negative amounts are debits. */
public record Transaction(String id, String accountId, LocalDate date, Money amount, String description) {
    public Transaction {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("transaction id is required");
        }
        if (accountId == null || date == null || amount == null) {
            throw new IllegalArgumentException("accountId, date and amount are required");
        }
        if (description == null) {
            description = "";
        }
    }
}
