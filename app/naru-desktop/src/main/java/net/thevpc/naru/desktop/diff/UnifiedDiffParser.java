package net.thevpc.naru.desktop.diff;

import net.thevpc.naru.desktop.diff.DiffModel.FileDiff;
import net.thevpc.naru.desktop.diff.DiffModel.Hunk;
import net.thevpc.naru.desktop.diff.DiffModel.Kind;
import net.thevpc.naru.desktop.diff.DiffModel.Line;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the text of a {@code git diff --unified=N} stream into {@link FileDiff}s.
 * <p>
 * The panel wants per-file hunks with real line numbers, which a unified body carries but in a form
 * built for patches rather than for a two-column view. Parsing it is exact — git's own counts
 * ({@code --numstat}) and hunk headers give the numbers, so no re-implementation of a diff
 * algorithm is needed for the files git already told us about. Anything the scanner cannot
 * classify is kept as context rather than dropped, so a malformed body still shows every line.
 */
public final class UnifiedDiffParser {

    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$");
    private static final Pattern GIT_PATHS = Pattern.compile("^diff --git a/(.*?) b/(.*)$");
    private static final Pattern BINARY_PATHS = Pattern.compile("^Binary files a/(.*?) and b/(.*) differ");

    private UnifiedDiffParser() {
    }

    /** The paths of a {@code diff --git a/X b/Y} line; renames carry both sides. */
    private static String[] pathsFromGitHeader(String raw) {
        String rest = raw.substring("diff --git ".length());
        String[] quoted = quotedPaths(rest, " ");
        if (quoted != null) return quoted;
        Matcher m = GIT_PATHS.matcher(raw);
        return m.matches() ? new String[]{ pathOf(m.group(1)), pathOf(m.group(2)) } : new String[]{ null, null };
    }

    /**
     * A pair of git C-style quoted paths, {@code "a/x"} then {@code joiner} then {@code "b/y"};
     * null when the text is not quoted that way. Git quotes a path whenever it holds a control
     * character or a non-ASCII byte, so this is how every unicode filename reaches the panel.
     */
    private static String[] quotedPaths(String rest, String joiner) {
        if (!rest.startsWith("\"")) return null;
        int end = closingQuote(rest, 0);
        if (end < 0) return null;
        String tail = rest.substring(end + 1);
        if (!tail.startsWith(joiner + "\"")) return null;
        if (closingQuote(tail, joiner.length()) < 0) return null;
        return new String[]{ pathOf(rest.substring(0, end + 1)), pathOf(tail.substring(joiner.length())) };
    }

    /** Index of the quote that closes the one at {@code from}, or -1. */
    private static int closingQuote(String s, int from) {
        for (int i = from + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') i++;
            else if (c == '"') return i;
        }
        return -1;
    }

    /** Decode git's C-style path quoting: {@code \t}, {@code \"}, {@code \\} and octal {@code \345}. */
    private static String unquoted(String body) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\' || i + 1 >= body.length()) {
                out.write(c);
                continue;
            }
            char n = body.charAt(++i);
            int value = switch (n) {
                case 'a' -> 7;
                case 'b' -> 8;
                case 'f' -> 12;
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'v' -> 11;
                case '\\' -> '\\';
                case '"' -> '"';
                default -> -1;
            };
            if (value < 0) {
                if (n < '0' || n > '7') {
                    out.write(n);
                    continue;
                }
                value = n - '0';
                for (int digits = 1; digits < 3 && i + 1 < body.length()
                        && body.charAt(i + 1) >= '0' && body.charAt(i + 1) <= '7'; digits++) {
                    value = value * 8 + (body.charAt(++i) - '0');
                }
            }
            out.write(value);
        }
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    public static List<FileDiff> parse(String diffText) {
        List<FileDiff> result = new ArrayList<>();
        if (diffText == null || diffText.isBlank()) return result;
        List<String> headers = new ArrayList<>();
        String oldPath = null, newPath = null;
        List<Hunk> hunks = new ArrayList<>();
        List<Line> current = null;
        String hunkHeader = null;
        int oldLine = 0, newLine = 0;
        boolean binary = false, inFile = false;
        for (String raw : splitLines(diffText)) {
            if (raw.startsWith("diff --git ")) {
                flushHunk(hunks, hunkHeader, current);
                if (inFile) result.add(closeFile(oldPath, newPath, headers, hunks, binary));
                headers = new ArrayList<>();
                hunks = new ArrayList<>();
                current = null;
                hunkHeader = null;
                binary = false;
                inFile = true;
                String[] paths = pathsFromGitHeader(raw);
                oldPath = paths[0] != null ? paths[0] : paths[1];
                newPath = paths[1] != null ? paths[1] : paths[0];
                headers.add(raw);
                continue;
            }
            if (raw.startsWith("index ") && inFile && current == null) {
                headers.add(raw);
                continue;
            }
            if (raw.startsWith("--- ")) {
                String p = pathOf(raw.substring(4));
                oldPath = p != null ? p : oldPath;
                headers.add(raw);
                current = null;
                continue;
            }
            if (raw.startsWith("+++ ")) {
                String p = pathOf(raw.substring(4));
                newPath = p != null ? p : newPath;
                headers.add(raw);
                current = null;
                continue;
            }
            boolean markerLine = false;
            for (String marker : List.of("new file", "deleted file", "rename from", "rename to",
                    "similarity index", "dissimilarity index", "old mode", "new mode",
                    "Binary files", "GIT binary patch")) {
                if (raw.startsWith(marker)) {
                    if (raw.startsWith("rename to ")) newPath = pathOf(raw.substring("rename to ".length()));
                    else if (raw.startsWith("rename from ")) oldPath = pathOf(raw.substring("rename from ".length()));
                    if (raw.startsWith("Binary files")) {
                        binary = true;
                        String rest = raw.substring("Binary files ".length());
                        Matcher m = BINARY_PATHS.matcher(raw);
                        String[] quoted = quotedPaths(rest, " and");
                        if (quoted != null) {
                            oldPath = quoted[0];
                            newPath = quoted[1];
                        } else if (m.find()) {
                            oldPath = pathOf(m.group(1));
                            newPath = pathOf(m.group(2));
                        }
                    } else if (raw.startsWith("GIT binary patch")) {
                        binary = true;
                    }
                    if (inFile) headers.add(raw);
                    current = null;
                    markerLine = true;
                    break;
                }
            }
            if (markerLine) continue;
            if (raw.startsWith("@@ ")) {
                Matcher m = HUNK.matcher(raw);
                if (!m.matches()) {
                    if (inFile) headers.add(raw);
                    continue;
                }
                if (!inFile) {
                    inFile = true;
                    headers.add("@@ ... @@");
                }
                flushHunk(hunks, hunkHeader, current);
                hunkHeader = raw;
                current = new ArrayList<>();
                oldLine = Integer.parseInt(m.group(1));
                newLine = Integer.parseInt(m.group(3));
                continue;
            }
            if (current != null) {
                char c = raw.isEmpty() ? ' ' : raw.charAt(0);
                String body = raw.isEmpty() ? "" : raw.substring(1);
                switch (c) {
                    case ' ' -> current.add(new Line(Kind.CONTEXT, body, oldLine++, newLine++));
                    case '-' -> current.add(new Line(Kind.DELETED, body, oldLine++, newLine));
                    case '+' -> current.add(new Line(Kind.ADDED, body, oldLine, newLine++));
                    case '\\' -> { /* "\ No newline at end of file" — nothing to show. */ }
                    default -> {
                        // A line that does not look like diff body: keep it visible as context.
                        current.add(new Line(Kind.CONTEXT, raw, oldLine++, newLine++));
                    }
                }
                continue;
            }
            if (inFile && !raw.isBlank()) headers.add(raw);
        }
        flushHunk(hunks, hunkHeader, current);
        if (inFile) result.add(closeFile(oldPath, newPath, headers, hunks, binary));
        return result;
    }

    private static void flushHunk(List<Hunk> hunks, String header, List<Line> lines) {
        if (header != null && lines != null) hunks.add(new Hunk(header, lines));
    }

    private static FileDiff closeFile(String oldPath, String newPath, List<String> headers,
                                      List<Hunk> hunks, boolean binary) {
        String o = oldPath != null ? oldPath : (newPath != null ? newPath : "?");
        String n = newPath != null ? newPath : (oldPath != null ? oldPath : "?");
        return new FileDiff(o, n, List.copyOf(headers), List.copyOf(hunks), binary);
    }

    /** Strips the a/ b/ prefixes, git's C-style quoting, and any tab-separated timestamp. */
    private static String pathOf(String field) {
        String p = field;
        if (p.startsWith("\"")) {
            int end = closingQuote(p, 0);
            p = unquoted(p.substring(1, end < 0 ? p.length() : end));
        } else {
            int tab = p.indexOf('\t');
            if (tab >= 0) p = p.substring(0, tab);
        }
        if (p.equals("/dev/null")) return null;
        if (p.startsWith("a/") || p.startsWith("b/")) p = p.substring(2);
        return p;
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                lines.add(text.substring(start, i));
                start = i + 1;
            }
        }
        if (start < text.length()) lines.add(text.substring(start));
        return lines;
    }
}
