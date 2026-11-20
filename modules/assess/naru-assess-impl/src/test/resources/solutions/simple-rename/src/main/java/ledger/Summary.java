package ledger;

/** One-line summaries of a ledger, built on {@link Report}. */
public final class Summary {
    private Summary() {
    }

    public static String line(Ledger ledger, String accountId) {
        Money balance = ledger.balance(accountId);
        String tone = balance.isNegative() ? "overdrawn by " + Report.format(balance.negate()) : "in credit by " + Report.format(balance);
        return ledger.account(accountId).name() + " is " + tone;
    }
}
