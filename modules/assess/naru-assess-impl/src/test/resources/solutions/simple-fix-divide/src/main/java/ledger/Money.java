package ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** An amount of money in minor units (cents) of a single currency. Immutable. */
public final class Money implements Comparable<Money> {
    private final long cents;
    private final String currency;

    public Money(long cents, String currency) {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }
        this.cents = cents;
        this.currency = currency;
    }

    public static Money zero(String currency) {
        return new Money(0, currency);
    }

    public long cents() {
        return cents;
    }

    public String currency() {
        return currency;
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(cents + other.cents, currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(cents - other.cents, currency);
    }

    public Money negate() {
        return new Money(-cents, currency);
    }

    public boolean isNegative() {
        return cents < 0;
    }

    public Money multiply(int factor) {
        return new Money(cents * factor, currency);
    }

    /** Divides by {@code divisor}, rounding half away from zero to the nearest cent. */
    public Money divide(int divisor) {
        if (divisor == 0) {
            throw new IllegalArgumentException("division by zero");
        }
        return new Money(BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(divisor), 0, RoundingMode.HALF_UP).longValue(), currency);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(cents, other.cents);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Money m && m.cents == cents && m.currency.equals(currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cents, currency);
    }

    @Override
    public String toString() {
        long abs = Math.abs(cents);
        return (cents < 0 ? "-" : "") + (abs / 100) + "." + String.format("%02d", abs % 100) + " " + currency;
    }
}
