package ledger;

/** Plain-text rendering of ledger data. */
public final class Report {
    private Report() {
    }

    /** Formats an amount with thousands separators, e.g. {@code 1,234.56 USD}. */
    public static String format(Money m) {
        long abs = Math.abs(m.cents());
        String whole = String.format("%,d", abs / 100);
        return (m.isNegative() ? "-" : "") + whole + "." + String.format("%02d", abs % 100) + " " + m.currency();
    }

    public static String statementText(Ledger ledger, String accountId) {
        StringBuilder sb = new StringBuilder();
        sb.append("Statement for ").append(ledger.account(accountId).name()).append('\n');
        for (StatementLine line : ledger.statement(accountId)) {
            Transaction t = line.transaction();
            sb.append(t.date()).append("  ")
                    .append(String.format("%-24s", t.description())).append("  ")
                    .append(format(t.amount())).append("  ")
                    .append(format(line.runningBalance())).append('\n');
        }
        sb.append("Balance: ").append(format(ledger.balance(accountId))).append('\n');
        return sb.toString();
    }

    public static String balanceLine(Ledger ledger, String accountId) {
        return ledger.account(accountId).name() + ": " + format(ledger.balance(accountId));
    }
}
