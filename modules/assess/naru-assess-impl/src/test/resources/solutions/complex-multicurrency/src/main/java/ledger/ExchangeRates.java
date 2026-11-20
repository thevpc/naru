package ledger;

import java.math.BigDecimal;

public interface ExchangeRates {
    BigDecimal rate(String from, String to);
}
