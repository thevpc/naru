package net.thevpc.naru.ext.budget;

import java.math.BigDecimal;

/**
 * Money already spent, split into the input side and the output side.
 *
 * <p>There is deliberately no {@code thinking} component: reasoning tokens are
 * billed as output by every provider NARU talks to, so a separate price class
 * would be a number that can never be filled in. Input here includes the cache
 * read and cache write classes, which are input tokens billed at their own rates.
 */
public record NaruSpending(BigDecimal input, BigDecimal output) {

    public static final NaruSpending ZERO = new NaruSpending(BigDecimal.ZERO, BigDecimal.ZERO);

    public NaruSpending add(NaruSpending other) {
        return new NaruSpending(input.add(other.input), output.add(other.output));
    }

    public BigDecimal total() {
        return input.add(output);
    }
}
