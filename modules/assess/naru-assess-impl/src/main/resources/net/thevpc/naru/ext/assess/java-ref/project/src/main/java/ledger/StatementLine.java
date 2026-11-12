package ledger;

/** One row of an account statement: a transaction and the balance after it. */
public record StatementLine(Transaction transaction, Money runningBalance) {
}
