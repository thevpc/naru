package ledger;

/** A ledger account, denominated in one currency. */
public record Account(String id, String name, String currency) {
    public Account {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("account id is required");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("account currency is required");
        }
    }
}
