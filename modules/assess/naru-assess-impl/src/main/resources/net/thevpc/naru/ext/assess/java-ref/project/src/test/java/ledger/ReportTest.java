package ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ReportTest {
    @Test
    void statementContainsBalance() {
        Ledger l = new Ledger();
        l.addAccount(new Account("a", "Alpha", "USD"));
        l.post(new Transaction("1", "a", LocalDate.parse("2024-01-01"), new Money(123456, "USD"), "Salary"));
        String text = Report.statementText(l, "a");
        assertTrue(text.contains("Balance: 1,234.56 USD"));
        assertEquals("Alpha: 1,234.56 USD", Report.balanceLine(l, "a"));
    }

    @Test
    void summaryDescribesBalance() {
        Ledger l = new Ledger();
        l.addAccount(new Account("a", "Alpha", "USD"));
        l.post(new Transaction("1", "a", LocalDate.parse("2024-01-01"), new Money(-50000, "USD"), "Rent"));
        assertEquals("Alpha is overdrawn by 500.00 USD", Summary.line(l, "a"));
    }
}
