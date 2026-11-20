package ledger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An in-memory general ledger: accounts plus the transactions posted on them. */
public class Ledger {
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final List<Transaction> transactions = new ArrayList<>();

    public void addAccount(Account account) {
        if (accounts.containsKey(account.id())) {
            throw new IllegalArgumentException("duplicate account: " + account.id());
        }
        accounts.put(account.id(), account);
    }

    public Account account(String accountId) {
        Account a = accounts.get(accountId);
        if (a == null) {
            throw new IllegalArgumentException("unknown account: " + accountId);
        }
        return a;
    }

    public List<Account> accounts() {
        return List.copyOf(accounts.values());
    }

    public void post(Transaction t) {
        Account a = account(t.accountId());
        if (!a.currency().equals(t.amount().currency())) {
            throw new IllegalArgumentException("currency mismatch on account " + a.id());
        }
        for (Transaction existing : transactions) {
            if (existing.id().equals(t.id())) {
                throw new IllegalArgumentException("duplicate transaction: " + t.id());
            }
        }
        transactions.add(t);
    }

    public List<Transaction> transactions(String accountId) {
        account(accountId);
        List<Transaction> out = new ArrayList<>();
        for (Transaction t : transactions) {
            if (t.accountId().equals(accountId)) {
                out.add(t);
            }
        }
        return out;
    }

    public Money balance(String accountId) {
        Money sum = Money.zero(account(accountId).currency());
        for (Transaction t : transactions(accountId)) {
            sum = sum.add(t.amount());
        }
        return sum;
    }

    /** The account's transactions in date order, each with the balance after it. */
    public List<StatementLine> statement(String accountId) {
        List<Transaction> sorted = new ArrayList<>(transactions(accountId));
        sorted.sort(Comparator.comparing(Transaction::date));
        Money running = Money.zero(account(accountId).currency());
        List<StatementLine> lines = new ArrayList<>();
        for (Transaction t : sorted) {
            running = running.add(t.amount());
            lines.add(new StatementLine(t, running));
        }
        return lines;
    }

    public Money balanceAt(String accountId, java.time.LocalDate date) {
        Money sum = Money.zero(account(accountId).currency());
        for (Transaction t : transactions(accountId)) {
            if (!t.date().isAfter(date)) {
                sum = sum.add(t.amount());
            }
        }
        return sum;
    }
}
