package ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreHiddenTest {
    @TempDir
    Path dir;

    private static Transaction tx(String id, String acc, long cents, String desc) {
        return new Transaction(id, acc, LocalDate.parse("2024-04-01"), new Money(cents, "USD"), desc);
    }

    @Test
    void inMemoryStoreAppendsAndLists() {
        TransactionStore s = new InMemoryTransactionStore();
        s.append(tx("1", "a", 10, "one"));
        s.append(tx("2", "a", 20, "two"));
        assertEquals(List.of(tx("1", "a", 10, "one"), tx("2", "a", 20, "two")), s.all());
    }

    @Test
    void fileStorePersistsAcrossInstances() {
        Path f = dir.resolve("tx.csv");
        FileTransactionStore s1 = new FileTransactionStore(f);
        assertEquals(List.of(), s1.all());
        s1.append(tx("1", "a", 10, "plain"));
        s1.append(tx("2", "a", -3, "with, comma and \"quotes\""));
        FileTransactionStore s2 = new FileTransactionStore(f);
        assertEquals(List.of(tx("1", "a", 10, "plain"), tx("2", "a", -3, "with, comma and \"quotes\"")), s2.all());
        s2.append(tx("3", "b", 5, "third"));
        assertEquals(3, new FileTransactionStore(f).all().size());
    }

    @Test
    void ledgerWorksOnTopOfAStore() {
        Path f = dir.resolve("l.csv");
        Ledger l1 = new Ledger(new FileTransactionStore(f));
        l1.addAccount(new Account("a", "A", "USD"));
        l1.post(tx("1", "a", 1000, "in"));
        l1.post(tx("2", "a", -250, "out"));
        Ledger l2 = new Ledger(new FileTransactionStore(f));
        l2.addAccount(new Account("a", "A", "USD"));
        assertEquals(new Money(750, "USD"), l2.balance("a"));
        assertEquals(2, l2.statement("a").size());
    }

    @Test
    void defaultLedgerStillWorks() {
        Ledger l = new Ledger();
        l.addAccount(new Account("a", "A", "USD"));
        l.post(tx("1", "a", 5, "x"));
        assertEquals(new Money(5, "USD"), l.balance("a"));
    }

    @Test
    void cliBalanceCommand() throws Exception {
        Path f = dir.resolve("cli.csv");
        Files.writeString(f, CsvExporter.write(List.of(tx("1", "chk", 10000, "in"), tx("2", "chk", -2500, "out"))));
        PrintStream old = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true));
        try {
            Main.main(new String[]{"balance", f.toString(), "chk"});
        } finally {
            System.setOut(old);
        }
        assertEquals("chk: 75.00 USD", buf.toString().trim());
        assertTrue(Files.exists(f));
    }
}
