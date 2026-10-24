package net.thevpc.naru.desktop.diff;

import java.util.ArrayList;
import java.util.List;

/** The models the Changes tab paints: one parsed file diff, laid out into side-by-side rows. */
public final class DiffModel {

    private DiffModel() {
    }

    public enum Kind { CONTEXT, ADDED, DELETED, HUNK }

    /** One line of a unified-diff body, with the line numbers it carries in each version. */
    public record Line(Kind kind, String text, int oldNumber, int newNumber) {
    }

    /** One @@ … @@ group of lines. */
    public record Hunk(String header, List<Line> lines) {
    }

    /**
     * One screen row of the two-column view. A side is null when the row has nothing there and
     * {@code BLANK} when it is a spacer that keeps the columns aligned across an unequal change.
     */
    public record Side(String number, Kind kind, String text) {
        public static final Side BLANK = new Side("", null, "");
    }

    public sealed interface Row permits Row.Paired, Row.Changed {
        record Paired(Side shared) implements Row {
        }

        record Changed(Side left, Side right) implements Row {
        }
    }

    /** A whole file's diff: the paths it moves between, the header lines above the first hunk, and the hunks. */
    public static final class FileDiff {
        public final String oldPath;
        public final String newPath;
        public final List<String> headers;
        public final List<Hunk> hunks;
        public final boolean binary;

        public FileDiff(String oldPath, String newPath, List<String> headers, List<Hunk> hunks, boolean binary) {
            this.oldPath = oldPath;
            this.newPath = newPath;
            this.headers = headers;
            this.hunks = hunks;
            this.binary = binary;
        }

        public boolean renamed() {
            return oldPath != null && newPath != null && !oldPath.equals(newPath);
        }

        public int added() {
            int n = 0;
            for (Hunk h : hunks) {
                for (Line l : h.lines()) if (l.kind() == Kind.ADDED) n++;
            }
            return n;
        }

        public int deleted() {
            int n = 0;
            for (Hunk h : hunks) {
                for (Line l : h.lines()) if (l.kind() == Kind.DELETED) n++;
            }
            return n;
        }

        /** Every line of every hunk, with the hunk headers, which is what the unified view shows. */
        public List<Line> allLines() {
            List<Line> all = new ArrayList<>();
            for (Hunk h : hunks) {
                all.add(new Line(Kind.HUNK, h.header(), 0, 0));
                all.addAll(h.lines());
            }
            return all;
        }

        /**
         * Lay the hunks out as aligned two-column rows. Context sits across both sides; inside a
         * change block the i-th removed line faces the i-th added line, and the longer side pads
         * itself with blanks — the way meld keeps both panes on the same line height.
         */
        public List<Row> sideBySide() {
            List<Row> rows = new ArrayList<>();
            for (Hunk h : hunks) {
                rows.add(new Row.Paired(new Side("", Kind.HUNK, h.header())));
                List<Line> lines = h.lines();
                int i = 0;
                while (i < lines.size()) {
                    Line l = lines.get(i);
                    if (l.kind() == Kind.CONTEXT) {
                        rows.add(new Row.Paired(side(l)));
                        i++;
                        continue;
                    }
                    List<Line> deletes = new ArrayList<>();
                    while (i < lines.size() && lines.get(i).kind() == Kind.DELETED) deletes.add(lines.get(i++));
                    List<Line> adds = new ArrayList<>();
                    while (i < lines.size() && lines.get(i).kind() == Kind.ADDED) adds.add(lines.get(i++));
                    for (int k = 0; k < Math.max(deletes.size(), adds.size()); k++) {
                        rows.add(new Row.Changed(k < deletes.size() ? side(deletes.get(k)) : Side.BLANK,
                                k < adds.size() ? side(adds.get(k)) : Side.BLANK));
                    }
                }
            }
            return rows;
        }

        private static Side side(Line l) {
            return new Side(Integer.toString(l.kind() == Kind.ADDED ? l.newNumber() : l.oldNumber()),
                    l.kind(), l.text());
        }
    }
}
