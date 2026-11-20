package ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LedgerTest {
    private Ledger ledger;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        ledger.addAccount(new Account("chk", "Checking", "USD"));
    }

    private static Transaction tx(String id, String date, long cents) {
        return new Transaction(id, "chk", LocalDate.parse(date), new Money(cents, "USD"), "t" + id);
    }

    @Test
    void balanceSumsTransactions() {
        ledger.post(tx("1", "2024-01-01", 10000));
        ledger.post(tx("2", "2024-01-02", -2500));
        assertEquals(new Money(7500, "USD"), ledger.balance("chk"));
    }

    @Test
    void rejectsUnknownAccountAndDuplicates() {
        assertThrows(IllegalArgumentException.class, () -> ledger.balance("nope"));
        ledger.post(tx("1", "2024-01-01", 100));
        assertThrows(IllegalArgumentException.class, () -> ledger.post(tx("1", "2024-01-02", 100)));
    }

    @Test
    void rejectsWrongCurrency() {
        Transaction eur = new Transaction("9", "chk", LocalDate.parse("2024-01-01"), new Money(1, "EUR"), "x");
        assertThrows(IllegalArgumentException.class, () -> ledger.post(eur));
    }

    @Test
    void statementInPostingOrder() {
        ledger.post(tx("1", "2024-01-01", 10000));
        ledger.post(tx("2", "2024-01-02", -2500));
        var lines = ledger.statement("chk");
        assertEquals(new Money(10000, "USD"), lines.get(0).runningBalance());
        assertEquals(new Money(7500, "USD"), lines.get(1).runningBalance());
    }
}
