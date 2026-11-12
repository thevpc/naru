package ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatementOrderHiddenTest {
    private Ledger ledger;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        ledger.addAccount(new Account("a", "Alpha", "USD"));
    }

    private void post(String id, String date, long cents) {
        ledger.post(new Transaction(id, "a", LocalDate.parse(date), new Money(cents, "USD"), "d" + id));
    }

    @Test
    void runningBalanceFollowsDateOrder() {
        post("late", "2024-03-01", -400);
        post("early", "2024-01-01", 1000);
        post("mid", "2024-02-01", 250);
        List<StatementLine> lines = ledger.statement("a");
        assertEquals(List.of("early", "mid", "late"), lines.stream().map(l -> l.transaction().id()).toList());
        assertEquals(new Money(1000, "USD"), lines.get(0).runningBalance());
        assertEquals(new Money(1250, "USD"), lines.get(1).runningBalance());
        assertEquals(new Money(850, "USD"), lines.get(2).runningBalance());
    }

    @Test
    void sameDayKeepsPostingOrder() {
        post("x", "2024-01-01", 100);
        post("y", "2024-01-01", 200);
        post("z", "2024-01-01", -50);
        List<StatementLine> lines = ledger.statement("a");
        assertEquals(List.of("x", "y", "z"), lines.stream().map(l -> l.transaction().id()).toList());
        assertEquals(new Money(100, "USD"), lines.get(0).runningBalance());
        assertEquals(new Money(300, "USD"), lines.get(1).runningBalance());
        assertEquals(new Money(250, "USD"), lines.get(2).runningBalance());
    }

    @Test
    void lastRunningBalanceEqualsBalance() {
        post("3", "2024-03-03", 30);
        post("1", "2024-01-01", 10);
        post("2", "2024-02-02", -5);
        List<StatementLine> lines = ledger.statement("a");
        assertEquals(ledger.balance("a"), lines.get(lines.size() - 1).runningBalance());
    }

    @Test
    void reportShowsSortedRows() {
        post("b", "2024-02-01", 100);
        post("a", "2024-01-01", 300);
        String text = Report.statementText(ledger, "a");
        assertTrue(text.indexOf("2024-01-01") < text.indexOf("2024-02-01"), text);
        assertTrue(text.contains("Balance: 4.00 USD"), text);
    }
}
