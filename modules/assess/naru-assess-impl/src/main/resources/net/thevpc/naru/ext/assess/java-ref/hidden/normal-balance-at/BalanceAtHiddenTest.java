package ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BalanceAtHiddenTest {
    private Ledger ledger;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        ledger.addAccount(new Account("a", "A", "USD"));
        ledger.addAccount(new Account("b", "B", "USD"));
        post("1", "a", "2024-01-10", 1000);
        post("2", "a", "2024-02-10", -300);
        post("3", "a", "2024-02-10", 50);
        post("4", "a", "2024-03-01", 700);
        post("5", "b", "2024-01-05", 9999);
    }

    private void post(String id, String acc, String date, long cents) {
        ledger.post(new Transaction(id, acc, LocalDate.parse(date), new Money(cents, "USD"), "x"));
    }

    @Test
    void includesTheGivenDay() {
        assertEquals(new Money(750, "USD"), ledger.balanceAt("a", LocalDate.parse("2024-02-10")));
    }

    @Test
    void excludesLaterTransactions() {
        assertEquals(new Money(1000, "USD"), ledger.balanceAt("a", LocalDate.parse("2024-02-09")));
        assertEquals(new Money(1450, "USD"), ledger.balanceAt("a", LocalDate.parse("2030-01-01")));
    }

    @Test
    void zeroBeforeFirstTransaction() {
        assertEquals(new Money(0, "USD"), ledger.balanceAt("a", LocalDate.parse("2023-12-31")));
    }

    @Test
    void unknownAccountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ledger.balanceAt("zzz", LocalDate.parse("2024-01-01")));
    }

    @Test
    void agreesWithBalanceInTheFuture() {
        assertEquals(ledger.balance("b"), ledger.balanceAt("b", LocalDate.parse("2999-12-31")));
    }
}
