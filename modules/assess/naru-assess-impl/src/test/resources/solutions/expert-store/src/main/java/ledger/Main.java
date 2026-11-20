package ledger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Command line: {@code statement <csvFile> <accountId>} prints the account statement. */
public class Main {
    public static void main(String[] args) throws IOException {
        if (args.length != 3 || !(args[0].equals("statement") || args[0].equals("balance"))) {
            System.err.println("usage: statement|balance <csvFile> <accountId>");
            System.exit(1);
        }
        List<Transaction> txs = CsvImporter.parse(Files.readString(Path.of(args[1])));
        String accountId = args[2];
        Ledger ledger = new Ledger();
        txs.stream().filter(t -> t.accountId().equals(accountId)).findFirst().ifPresentOrElse(
                first -> ledger.addAccount(new Account(accountId, accountId, first.amount().currency())),
                () -> {
                    System.err.println("no transactions for account " + accountId);
                    System.exit(2);
                });
        for (Transaction t : txs) {
            if (t.accountId().equals(accountId)) {
                ledger.post(t);
            }
        }
        if (args[0].equals("balance")) {
            System.out.println(Report.balanceLine(ledger, accountId));
        } else {
            System.out.print(Report.statementText(ledger, accountId));
            System.out.println(Summary.line(ledger, accountId));
        }
    }
}
