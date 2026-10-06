package net.thevpc.naru.ext.budget;

import java.math.BigDecimal;

public record NaruSpending(BigDecimal input, BigDecimal output, BigDecimal thinking) {
    public NaruSpending add(NaruSpending other) {
        return new NaruSpending(input.add(other.input), output.add(other.output), thinking.add(other.thinking));
    }
    public NaruSpending mul(NaruSpending other) {
        return new NaruSpending(input.multiply(other.input), output.multiply(other.output), thinking.multiply(other.thinking));
    }

    public BigDecimal total() {
        return input.add(output).add(thinking);
    }
}
