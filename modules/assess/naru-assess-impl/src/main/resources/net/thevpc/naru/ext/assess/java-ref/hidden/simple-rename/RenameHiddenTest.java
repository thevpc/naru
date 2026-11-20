package ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class RenameHiddenTest {
    @Test
    void formatReplacesFmt() {
        assertEquals("1,234.56 USD", Report.format(new Money(123456, "USD")));
        assertEquals("-0.05 EUR", Report.format(new Money(-5, "EUR")));
    }

    @Test
    void renameDoesNotChangeBehaviour() {
        Ledger l = new Ledger();
        l.addAccount(new Account("a", "Alpha", "USD"));
        l.post(new Transaction("1", "a", LocalDate.parse("2024-01-01"), new Money(100000, "USD"), "Salary"));
        l.post(new Transaction("2", "a", LocalDate.parse("2024-01-02"), new Money(-2500, "USD"), "Coffee"));
        String text = Report.statementText(l, "a");
        assertFalse(text.contains("\\n"), "line breaks must stay real line breaks: " + text);
        assertEquals(4, text.split("\n").length, text);
        assertTrue(text.endsWith("Balance: 975.00 USD\n"), text);
        assertEquals("Alpha is in credit by 975.00 USD", Summary.line(l, "a"));
    }
}
