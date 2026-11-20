package ledger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public class FileTransactionStore implements TransactionStore {
    private final Path file;

    public FileTransactionStore(Path file) {
        this.file = file;
    }

    @Override
    public void append(Transaction t) {
        try {
            String csv = CsvExporter.write(List.of(t));
            if (!Files.exists(file)) {
                Files.writeString(file, csv);
            } else {
                Files.writeString(file, csv.substring(csv.indexOf('\n') + 1), StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<Transaction> all() {
        try {
            return Files.exists(file) ? CsvImporter.parse(Files.readString(file)) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
