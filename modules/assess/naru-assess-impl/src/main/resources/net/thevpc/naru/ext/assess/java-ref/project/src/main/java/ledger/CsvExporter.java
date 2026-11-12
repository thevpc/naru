package ledger;

import java.util.List;

/** Writes transactions as CSV, the inverse of {@link CsvImporter}. */
public final class CsvExporter {
    public static final String HEADER = "id,accountId,date,amountCents,currency,description";

    private CsvExporter() {
    }

    public static String write(List<Transaction> transactions) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Transaction t : transactions) {
            sb.append(t.id()).append(',')
                    .append(t.accountId()).append(',')
                    .append(t.date()).append(',')
                    .append(t.amount().cents()).append(',')
                    .append(t.amount().currency()).append(',')
                    .append(quote(t.description())).append('\n');
        }
        return sb.toString();
    }

    private static String quote(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
