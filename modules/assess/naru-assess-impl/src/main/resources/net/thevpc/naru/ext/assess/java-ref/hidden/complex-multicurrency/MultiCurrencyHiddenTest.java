package ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MultiCurrencyHiddenTest {
    private final ExchangeRates rates = new FixedRates()
            .with("USD", "EUR", new BigDecimal("0.50"))
            .with("GBP", "USD", new BigDecimal("1.25"));

    @Test
    void ratesIdentityDirectAndInverse() {
        assertEquals(0, BigDecimal.ONE.compareTo(rates.rate("USD", "USD")));
        assertEquals(0, new BigDecimal("0.50").compareTo(rates.rate("USD", "EUR")));
        assertEquals(0, new BigDecimal("2").compareTo(rates.rate("EUR", "USD").setScale(6, java.math.RoundingMode.HALF_EVEN)));
        assertEquals(0, new BigDecimal("0.8").compareTo(rates.rate("USD", "GBP").setScale(6, java.math.RoundingMode.HALF_EVEN)));
    }

    @Test
    void unknownPairIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> rates.rate("EUR", "GBP"));
    }

    @Test
    void convertRoundsHalfUpToTheCent() {
        assertEquals(new Money(500, "EUR"), new Money(1000, "USD").convertTo("EUR", rates));
        assertEquals(new Money(51, "EUR"), new Money(101, "USD").convertTo("EUR", rates));
        assertEquals(new Money(-51, "EUR"), new Money(-101, "USD").convertTo("EUR", rates));
        assertEquals(new Money(777, "USD"), new Money(777, "USD").convertTo("USD", rates));
    }

    @Test
    void netWorthSumsConvertedBalances() {
        Ledger l = new Ledger();
        l.addAccount(new Account("u", "U", "USD"));
        l.addAccount(new Account("e", "E", "EUR"));
        l.addAccount(new Account("g", "G", "GBP"));
        l.post(new Transaction("1", "u", LocalDate.parse("2024-01-01"), new Money(10000, "USD"), "a"));
        l.post(new Transaction("2", "e", LocalDate.parse("2024-01-01"), new Money(2500, "EUR"), "b"));
        l.post(new Transaction("3", "g", LocalDate.parse("2024-01-01"), new Money(1000, "GBP"), "c"));
        // 10000 USD + 2500 EUR (=5000 USD) + 1000 GBP (=1250 USD) = 16250 USD
        assertEquals(new Money(16250, "USD"), l.netWorth("USD", rates));
        Ledger two = new Ledger();
        two.addAccount(new Account("u", "U", "USD"));
        two.addAccount(new Account("e", "E", "EUR"));
        two.post(new Transaction("1", "u", LocalDate.parse("2024-01-01"), new Money(10000, "USD"), "a"));
        two.post(new Transaction("2", "e", LocalDate.parse("2024-01-01"), new Money(2500, "EUR"), "b"));
        assertEquals(new Money(7500, "EUR"), two.netWorth("EUR", rates));
    }

    @Test
    void netWorthFailsWhenNoRate() {
        Ledger l = new Ledger();
        l.addAccount(new Account("e", "E", "EUR"));
        l.addAccount(new Account("g", "G", "GBP"));
        assertThrows(IllegalArgumentException.class, () -> l.netWorth("GBP", rates));
    }
}
