package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.cmdline.NArgCompleteCandidate;
import net.thevpc.nuts.cmdline.NArgCompletePosition;
import net.thevpc.nuts.cmdline.NArgCompleteResult;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.io.NPath;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Completes a raw input line at a caret position, without a terminal.
 * <p>
 * The console gets its candidates from {@code NArgCompleteResolver}, which is fed by the readline
 * library and only ever sees a <b>parsed</b> {@link NCmdLine} plus a word index. A GUI has neither:
 * it has a string and a caret, and it must be able to ask "what could come next" from plain code,
 * on the EDT, with no terminal state involved. This is that entry point.
 * <p>
 * It also settles the ambiguity the parsed form cannot express. {@code /home} is a directive if a
 * directive by that name is registered, and a path if it is not, and the terminal decides by
 * accident of which map lookup happens first. Here both are computed, ordered, and reported, so a
 * completion popup can show {@code /help (command)} next to {@code /home/ (directory)} instead of
 * silently picking one.
 *
 * <h2>What it does not do</h2>
 * It never mutates the session, never touches the terminal, and never blocks longer than one
 * directory listing. Deep argument completion is delegated to the directive's own
 * {@link NaruDirective#resolveCandidates}, so a directive that knows its subcommands still wins.
 */
public final class NaruLineCompletion {

    /** What kind of thing a candidate is, so the widget can group and iconise it. */
    public enum Kind {
        DIRECTIVE, SUBCOMMAND, DIRECTIVE_ARGUMENT, FILE, KEYWORD, NONE
    }

    /**
     * @param insert  the exact text to put in place of the current token
     * @param display one-line description, may be null
     * @param kind    what this candidate is
     */
    public record Candidate(String insert, String display, Kind kind) {
    }

    /**
     * @param candidates  ordered, never null, may be empty
     * @param token       the token under the caret that the candidate replaces ("" after a space)
     * @param replaceStart index in the raw line where the replacement begins
     * @param replaceEnd   index in the raw line where the replacement ends (the caret)
     * @param ambiguous    true when both a command and a path match the same token, so the caller
     *                     should show the kind rather than assume the user meant one
     */
    public record Result(List<Candidate> candidates, String token, int replaceStart, int replaceEnd,
                         boolean ambiguous) {
        public static Result none(String token, int start, int end) {
            return new Result(List.of(), token, start, end, false);
        }

        public boolean isEmpty() {
            return candidates.isEmpty();
        }
    }

    private NaruLineCompletion() {
    }

    /**
     * Complete {@code rawLine} with the caret at {@code caret}.
     *
     * @param session used for the directive registry, the working directory, and the keyword lists
     *                a directive may want
     * @param rawLine the whole line as it sits in the input field
     * @param caret   0..rawLine.length(); a caret past the end is clamped, not rejected
     */
    public static Result complete(NaruSession session, String rawLine, int caret) {
        if (session == null || rawLine == null) {
            return new Result(List.of(), "", 0, 0, false);
        }
        String line = rawLine;
        int pos = Math.max(0, Math.min(caret, line.length()));
        Words w = tokenize(line.substring(0, pos));
        String token = w.currentToken;
        int start = w.currentStart;
        int wordIndex = w.index;

        if (wordIndex == 0) {
            if (token.isEmpty()) {
                // nothing typed yet: an empty field is a prompt for commands, not for files
                return new Result(directives(session, ""), "", start, pos, false);
            }
            if (token.startsWith("/")) {
                List<Candidate> commands = directives(session, token.substring(1));
                List<Candidate> files = files(session, token);
                List<Candidate> all = new ArrayList<>();
                boolean exactCommand = commands.stream()
                        .anyMatch(c -> ("/" + token.substring(1)).equalsIgnoreCase(c.insert()));
                if (exactCommand) {
                    // "/help " is the command, whatever file may also exist
                    all.addAll(commands);
                } else {
                    all.addAll(commands);
                    all.addAll(files);
                }
                List<Candidate> merged = dedupe(all);
                return new Result(merged, token, start, pos,
                        !commands.isEmpty() && !files.isEmpty() && !exactCommand);
            }
            // plain prose for the model: no completion
            return Result.none(token, start, pos);
        }

        // word 1 or later: only a directive line has arguments to complete
        String head = w.words.isEmpty() ? "" : w.words.get(0);
        if (!head.startsWith("/")) {
            return Result.none(token, start, pos);
        }
        NaruDirective directive = session.registry()
                .findDirective(head.substring(1)).orNull();
        if (directive != null) {
            List<String> args = new ArrayList<>(w.words);
            if (!token.isEmpty()) {
                // the half-typed word under the caret is part of the line the directive must parse
                args.add(token);
            }
            NArgCompleteResult r = null;
            try {
                r = directive.resolveCandidates(NCmdLine.of(args),
                        NArgCompletePosition.of(wordIndex, token.length(), pos), session);
            } catch (RuntimeException e) {
                // a directive that throws on a half-typed argument line is not our problem to fix here
                r = null;
            }
            List<Candidate> fromDirective = toCandidates(r);
            if (!fromDirective.isEmpty()) {
                return new Result(fromDirective, token, start, pos, false);
            }
        }
        // the directive did not know -- fall back to a path, which is what a human expects
        List<Candidate> files = files(session, token);
        if (!files.isEmpty()) {
            return new Result(files, token, start, pos, false);
        }
        return Result.none(token, start, pos);
    }

    // ------------------------------------------------------------------ commands

    private static List<Candidate> directives(NaruSession session, String prefix) {
        Map<String, NaruDirective> all = session.registry().directives();
        List<Candidate> out = new ArrayList<>();
        String p = prefix.toLowerCase(java.util.Locale.ROOT);
        for (Map.Entry<String, NaruDirective> e : all.entrySet()) {
            String name = e.getKey();
            if (name.toLowerCase(java.util.Locale.ROOT).startsWith(p)) {
                String desc;
                try {
                    desc = e.getValue().getDescription();
                } catch (RuntimeException ex) {
                    desc = null;
                }
                out.add(new Candidate("/" + name,
                        desc == null || desc.isBlank() ? "/" + name : "/" + name + " — " + firstLine(desc),
                        Kind.DIRECTIVE));
            }
        }
        out.sort(Comparator.comparing(Candidate::insert, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    // ------------------------------------------------------------------ files

    /**
     * Candidates for a token that names (part of) a path. A leading {@code /} is an absolute path
     * only when it is not a directive, which is why this is called with the token as typed and the
     * caller decides the order.
     */
    private static List<Candidate> files(NaruSession session, String token) {
        if (token.isEmpty()) {
            return List.of();
        }
        String t = token.replace("\"", "").replace("'", "");
        // A leading '/' means two different things to two different users: in naru it is how every
        // directive starts, and at a shell it is the file-system root. The project reading is tried
        // first ("/src/main" under the working directory), and only if that finds nothing does the
        // absolute reading get a turn. Either way the candidate keeps the shape the user typed, so
        // the widget can drop it back into the field without re-deriving what "/" meant.
        List<Candidate> project = listing(session.workingDir(), t,
                t.startsWith("/") ? t.substring(1) : t);
        if (!project.isEmpty()) {
            return project;
        }
        return listing(session.workingDir(), t, t);
    }

    /**
     * @param shaped the path actually looked up, which may differ from {@code t} by a leading '/'
     */
    private static List<Candidate> listing(NPath cwd, String t, String shaped) {
        int cut = shaped.lastIndexOf('/');
        String namePart = cut < 0 ? shaped : shaped.substring(cut + 1);
        NPath base = cut < 0 ? cwd : cwd.resolve(shaped.substring(0, cut));
        String insertPrefix = t.substring(0, t.length() - namePart.length());
        List<Candidate> out = new ArrayList<>();
        try {
            if (namePart.isEmpty()) {
                // token ends with '/' -- list the directory itself
                for (NPath child : listChildren(base)) {
                    out.add(candidateFor(child, insertPrefix, child.name()));
                }
            } else {
                NPath direct = base.resolve(namePart);
                if (direct.exists()) {
                    out.add(candidateFor(direct, insertPrefix, namePart));
                }
                for (NPath child : listChildren(base)) {
                    String n = child.name();
                    if (n != null && n.toLowerCase(java.util.Locale.ROOT)
                            .startsWith(namePart.toLowerCase(java.util.Locale.ROOT))
                            && !n.equals(namePart)) {
                        out.add(candidateFor(child, insertPrefix, n));
                    }
                }
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        out.sort(Comparator.comparing(Candidate::insert, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static Candidate candidateFor(NPath p, String insertPrefix, String name) {
        String insert = insertPrefix + name + (p.isDirectory() ? "/" : "");
        return new Candidate(insert, p.isDirectory() ? insert + " (directory)" : insert, Kind.FILE);
    }

    private static List<NPath> listChildren(NPath dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return List.of();
        }
        List<NPath> out = new ArrayList<>();
        try {
            out.addAll(dir.list());
        } catch (Exception e) {
            return List.of();
        }
        out.sort(Comparator.comparing((NPath p) -> p.isDirectory() ? 0 : 1)
                .thenComparing(p -> String.valueOf(p.name()), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static List<Candidate> toCandidates(NArgCompleteResult r) {
        if (r == null || r.isBlank()) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        for (NArgCompleteCandidate c : r.candidates()) {
            out.add(new Candidate(c.value(), c.display() == null ? c.value() : c.display(),
                    Kind.DIRECTIVE_ARGUMENT));
        }
        return out;
    }

    private static List<Candidate> dedupe(List<Candidate> in) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : in) {
            if (seen.add(c.insert())) {
                out.add(c);
            }
        }
        return out;
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }

    // ------------------------------------------------------------------ tokenizer

    static final class Words {
        final List<String> words = new ArrayList<>();
        String currentToken = "";
        int currentStart;
        int index;

        Words(List<String> words, String currentToken, int currentStart, int index) {
            this.words.addAll(words);
            this.currentToken = currentToken;
            this.currentStart = currentStart;
            this.index = index;
        }
    }

    /**
     * Split the text before the caret into words, quote-aware, and report the token the caret sits
     * in. A caret right after a space opens a new empty token, which is why {@code trailing} is
     * passed separately from the text.
     */
    static Words tokenize(String beforeCaret) {
        List<String> words = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int start = -1;
        boolean inQuotes = false;
        char quote = 0;
        for (int i = 0; i < beforeCaret.length(); i++) {
            char ch = beforeCaret.charAt(i);
            if (inQuotes) {
                if (ch == quote) {
                    inQuotes = false;
                }
                cur.append(ch);
                continue;
            }
            if (ch == '"' || ch == '\'') {
                if (start < 0) {
                    start = i;
                }
                inQuotes = true;
                quote = ch;
                cur.append(ch);
                continue;
            }
            if (Character.isWhitespace(ch)) {
                if (cur.length() > 0) {
                    words.add(cur.toString());
                    spans.add(new int[]{start, i});
                    cur.setLength(0);
                }
                start = -1;
                continue;
            }
            if (start < 0) {
                start = i;
            }
            cur.append(ch);
        }
        if (cur.length() > 0) {
            // caret in the middle of a word
            return new Words(words, cur.toString(), start, words.size());
        }
        if (beforeCaret.isEmpty() || Character.isWhitespace(beforeCaret.charAt(beforeCaret.length() - 1))) {
            // caret after a space (or at the very beginning): a fresh empty token
            return new Words(words, "", beforeCaret.length(), words.size());
        }
        return new Words(words, "", beforeCaret.length(), Math.max(0, words.size() - 1));
    }
}
