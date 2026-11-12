package ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DivideHiddenTest {
    @Test
    void roundsHalfAwayFromZero() {
        assertEquals(new Money(501, "USD"), new Money(1001, "USD").divide(2));
        assertEquals(new Money(-501, "USD"), new Money(-1001, "USD").divide(2));
        assertEquals(new Money(101, "USD"), new Money(1005, "USD").divide(10));
        assertEquals(new Money(-101, "USD"), new Money(-1005, "USD").divide(10));
    }

    @Test
    void roundsToNearest() {
        assertEquals(new Money(233, "EUR"), new Money(700, "EUR").divide(3));
        assertEquals(new Money(-333, "EUR"), new Money(-1000, "EUR").divide(3));
        assertEquals(new Money(667, "EUR"), new Money(2000, "EUR").divide(3));
    }

    @Test
    void exactAndEdgeCases() {
        assertEquals(new Money(250, "USD"), new Money(1000, "USD").divide(4));
        assertEquals(new Money(0, "USD"), new Money(0, "USD").divide(7));
        assertThrows(IllegalArgumentException.class, () -> new Money(1, "USD").divide(0));
    }
}
