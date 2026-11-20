package ledger;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.HashMap;
import java.util.Map;

public class FixedRates implements ExchangeRates {
    private final Map<String, BigDecimal> rates = new HashMap<>();

    public FixedRates with(String from, String to, BigDecimal rate) {
        rates.put(from + "->" + to, rate);
        return this;
    }

    @Override
    public BigDecimal rate(String from, String to) {
        if (from.equals(to)) {
            return BigDecimal.ONE;
        }
        BigDecimal direct = rates.get(from + "->" + to);
        if (direct != null) {
            return direct;
        }
        BigDecimal inverse = rates.get(to + "->" + from);
        if (inverse != null) {
            return BigDecimal.ONE.divide(inverse, MathContext.DECIMAL64);
        }
        throw new IllegalArgumentException("no rate " + from + "->" + to);
    }
}
