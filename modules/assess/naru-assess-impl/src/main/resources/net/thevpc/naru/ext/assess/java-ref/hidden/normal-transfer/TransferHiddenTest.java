package ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransferHiddenTest {
    private Ledger ledger;
    private final LocalDate day = LocalDate.parse("2024-05-05");

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        ledger.addAccount(new Account("a", "A", "USD"));
        ledger.addAccount(new Account("b", "B", "USD"));
        ledger.addAccount(new Account("e", "E", "EUR"));
    }

    @Test
    void movesMoneyBetweenAccounts() {
        ledger.transfer("a", "b", new Money(5000, "USD"), day, "rent");
        assertEquals(new Money(-5000, "USD"), ledger.balance("a"));
        assertEquals(new Money(5000, "USD"), ledger.balance("b"));
        assertEquals(1, ledger.transactions("a").size());
        assertEquals(1, ledger.transactions("b").size());
        assertEquals("rent", ledger.transactions("a").get(0).description());
        assertEquals(day, ledger.transactions("b").get(0).date());
    }

    @Test
    void repeatedTransfersGetDistinctIds() {
        ledger.transfer("a", "b", new Money(100, "USD"), day, "one");
        ledger.transfer("a", "b", new Money(200, "USD"), day, "two");
        assertEquals(new Money(-300, "USD"), ledger.balance("a"));
        assertEquals(new Money(300, "USD"), ledger.balance("b"));
    }

    @Test
    void invalidTransfersLeaveNoTrace() {
        assertThrows(IllegalArgumentException.class, () -> ledger.transfer("a", "zzz", new Money(1, "USD"), day, "x"));
        assertThrows(IllegalArgumentException.class, () -> ledger.transfer("zzz", "a", new Money(1, "USD"), day, "x"));
        assertThrows(IllegalArgumentException.class, () -> ledger.transfer("a", "e", new Money(1, "USD"), day, "x"));
        assertThrows(IllegalArgumentException.class, () -> ledger.transfer("a", "b", new Money(1, "EUR"), day, "x"));
        assertThrows(IllegalArgumentException.class, () -> ledger.transfer("a", "b", new Money(0, "USD"), day, "x"));
        assertThrows(IllegalArgumentException.class, () -> ledger.transfer("a", "b", new Money(-5, "USD"), day, "x"));
        assertThrows(IllegalArgumentException.class, () -> ledger.transfer("a", "a", new Money(5, "USD"), day, "x"));
        assertEquals(0, ledger.transactions("a").size());
        assertEquals(0, ledger.transactions("b").size());
        assertEquals(0, ledger.transactions("e").size());
    }
}
