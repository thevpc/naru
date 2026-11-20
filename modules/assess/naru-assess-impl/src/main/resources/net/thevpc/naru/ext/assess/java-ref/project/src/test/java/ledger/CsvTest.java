package ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvTest {
    @Test
    void roundTrip() {
        List<Transaction> in = List.of(
                new Transaction("1", "chk", LocalDate.parse("2024-03-01"), new Money(-1999, "USD"), "Books, and more"),
                new Transaction("2", "chk", LocalDate.parse("2024-03-02"), new Money(5000, "USD"), "Say \"hi\""));
        assertEquals(in, CsvImporter.parse(CsvExporter.write(in)));
    }
}
