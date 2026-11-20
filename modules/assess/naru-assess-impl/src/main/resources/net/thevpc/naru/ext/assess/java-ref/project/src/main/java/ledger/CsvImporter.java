package ledger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses transactions from CSV text. Columns:
 * {@code id,accountId,date,amountCents,currency,description}; the first line is a header.
 * The description may be double-quoted and then may contain commas and doubled quotes.
 */
public final class CsvImporter {
    private CsvImporter() {
    }

    public static List<Transaction> parse(String csv) {
        List<Transaction> out = new ArrayList<>();
        String[] lines = csv.split("\\R");
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            List<String> f = splitLine(lines[i]);
            if (f.size() != 6) {
                throw new IllegalArgumentException("line " + (i + 1) + ": expected 6 fields, got " + f.size());
            }
            out.add(new Transaction(f.get(0), f.get(1), LocalDate.parse(f.get(2)),
                    new Money(Long.parseLong(f.get(3)), f.get(4)), f.get(5)));
        }
        return out;
    }

    static List<String> splitLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields;
    }
}
