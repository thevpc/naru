package ledger;

/** One-line summaries of a ledger, built on {@link Report}. */
public final class Summary {
    private Summary() {
    }

    public static String line(Ledger ledger, String accountId) {
        Money balance = ledger.balance(accountId);
        String tone = balance.isNegative() ? "overdrawn by " + Report.fmt(balance.negate()) : "in credit by " + Report.fmt(balance);
        return ledger.account(accountId).name() + " is " + tone;
    }
}
