package ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void addAndSubtract() {
        Money a = new Money(1050, "USD");
        assertEquals(new Money(1550, "USD"), a.add(new Money(500, "USD")));
        assertEquals(new Money(550, "USD"), a.subtract(new Money(500, "USD")));
    }

    @Test
    void currencyMismatchIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Money(1, "USD").add(new Money(1, "EUR")));
    }

    @Test
    void dividesExactly() {
        assertEquals(new Money(250, "USD"), new Money(1000, "USD").divide(4));
    }

    @Test
    void divideRoundsHalfUp() {
        assertEquals(new Money(501, "USD"), new Money(1001, "USD").divide(2));
        assertEquals(new Money(333, "USD"), new Money(1000, "USD").divide(3));
    }

    @Test
    void rendersAsText() {
        assertEquals("12.05 USD", new Money(1205, "USD").toString());
        assertEquals("-0.99 EUR", new Money(-99, "EUR").toString());
    }
}
