package net.thevpc.naru.ext.budget;

import java.math.BigDecimal;

public interface NAruUnitBudgetSupplier {
    default NaruSpending getInputUnitBudget(Labels labels) {
        return new NaruSpending(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
