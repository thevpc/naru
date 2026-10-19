package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.agent.NaruSession;

import java.util.ArrayList;
import java.util.List;

/**
 * The typed spans of one input line: what each word <em>is</em>, where it starts, where it ends.
 * <p>
 * The terminal already colours a line this way — {@code NaruTerminalFormatter.formatInput} picks the
 * line kind, checks the directive against the registry, and hands the arguments to the system
 * highlighter — but it produces a styled {@code NText} and throws the structure away afterwards. A
 * GUI cannot paint from that: it needs offsets. Before this class, a GUI that wanted the same colours
 * had to write its own grammar, and the two would drift the first time the language changed. So the
 * decision lives here, once, and both hosts read it.
 *
 * <h2>What the roles are, and where each one comes from</h2>
 * <ul>
 *   <li>{@link Role#COMMENT} — the same two prefixes the engine accepts for a no-op statement: a line
 *       whose first word begins with {@code #} or {@code //}. See {@code NaruTaskImpl}, which turns
 *       such a line into a {@code NaruNopStmt}.</li>
 *   <li>{@link Role#DIRECTIVE} / {@link Role#UNKNOWN_DIRECTIVE} — the head word starting with
 *       {@code /}, split by whether the registry knows it, which is exactly the test
 *       {@code NaruTerminalFormatter.formatDirective} makes when it chooses blue or red.</li>
 *   <li>{@link Role#SUBCOMMAND}, {@link Role#OPTION_KEY}, {@link Role#OPTION_VALUE} — positional.
 *       {@link NaruDirective} declares no argument grammar, so nothing here can know that
 *       {@code /model use} takes a model name: a word starting with {@code -} is an option and what
 *       follows an {@code =} or a bare option is its value; otherwise the first word after a known
 *       head is the sub-command, unless its shape says path. That is a convention, not a fact from
 *       the directive, and it is documented as such.</li>
 *   <li>{@link Role#STRING}, {@link Role#NUMBER}, {@link Role#PATH} — by shape: quoted, all digits,
 *       or containing a separator. Nothing touches the filesystem, because a highlighter runs on
 *       every keystroke and an {@code exists()} check is an I/O call.</li>
 *   <li>{@link Role#TEXT} — everything else, including the whole line when it is not a directive at
 *       all: prose for the model, or a code snippet, has no naru grammar to find.</li>
 * </ul>
 *
 * <h2>What it does not do</h2>
 * It never mutates the session, never reads the disk or the network, and never returns overlapping
 * spans. Gaps between spans are the caller's default colour, not a hole to fill.
 */
public final class NaruLineSyntax {

    /** What a span of the line means. */
    public enum Role {
        COMMENT, DIRECTIVE, UNKNOWN_DIRECTIVE, SUBCOMMAND, OPTION_KEY, OPTION_VALUE,
        STRING, NUMBER, PATH, TEXT
    }

    /**
     * @param role what this piece of the line is
     * @param start index of the first character, inclusive
     * @param end   index past the last character, exclusive
     */
    public record Span(Role role, int start, int end) {
    }

    private NaruLineSyntax() {
    }

    /** Scan one line. {@code session} may be null, which only costs the directive/registry check. */
    public static List<Span> scan(NaruSession session, String line) {
        List<Span> out = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            return out;
        }
        String head = line.stripLeading();
        if (head.startsWith("#") || head.startsWith("//")) {
            out.add(new Span(Role.COMMENT, 0, line.length()));
            return out;
        }
        List<NaruLineCompletion.Token> tokens = NaruLineCompletion.tokens(line);
        if (tokens.isEmpty()) {
            return out;
        }
        NaruLineCompletion.Token first = tokens.get(0);
        if (!first.text().startsWith("/")) {
            // not a command line: prose or code, and naru has nothing to say about its words
            out.add(new Span(Role.TEXT, first.start(), line.length()));
            return out;
        }
        boolean known = isDirective(session, first.text());
        out.add(new Span(known ? Role.DIRECTIVE : Role.UNKNOWN_DIRECTIVE,
                first.start(), first.end()));
        boolean afterOption = false;
        for (int i = 1; i < tokens.size(); i++) {
            NaruLineCompletion.Token t = tokens.get(i);
            String w = t.text();
            // "-3.5" starts with a dash and is not a flag; the only way to tell them apart is the shape
            boolean number = !t.quoted() && isNumber(w);
            if (w.startsWith("-") && !number) {
                int eq = w.indexOf('=');
                if (eq > 0) {
                    out.add(new Span(Role.OPTION_KEY, t.start(), t.start() + eq + 1));
                    out.add(new Span(Role.OPTION_VALUE, t.start() + eq + 1, t.end()));
                    // the value came inline, so the next word is not one
                    afterOption = false;
                } else {
                    out.add(new Span(Role.OPTION_KEY, t.start(), t.end()));
                    afterOption = true;
                }
                continue;
            }
            if (afterOption) {
                // what follows an option is its value, whatever shape it has: a colour that says
                // "number" here would hide which word the flag belongs to
                out.add(new Span(Role.OPTION_VALUE, t.start(), t.end()));
            } else if (t.quoted()) {
                out.add(new Span(Role.STRING, t.start(), t.end()));
            } else if (number) {
                out.add(new Span(Role.NUMBER, t.start(), t.end()));
            } else if (looksLikePath(w)) {
                // before the sub-command rule: "/project /tmp/x" takes a path, and a shape test is
                // the only thing here that can tell the two apart without knowing the directive
                out.add(new Span(Role.PATH, t.start(), t.end()));
            } else if (i == 1 && known) {
                out.add(new Span(Role.SUBCOMMAND, t.start(), t.end()));
            } else {
                out.add(new Span(Role.TEXT, t.start(), t.end()));
            }
            afterOption = false;
        }
        return out;
    }

    private static boolean isDirective(NaruSession session, String word) {
        if (session == null) {
            return false;
        }
        String name = word.substring(1);
        int cut = name.indexOf('/');
        if (cut >= 0) {
            // "/cat" and "/cat/" are the same command to a user mid-word; a path is not a directive
            name = name.substring(0, cut);
        }
        if (name.isEmpty()) {
            // a bare "/" is how the command list is asked for, not an unknown command
            return true;
        }
        return session.registry().findDirective(name).isPresent();
    }

    private static boolean isNumber(String w) {
        if (w.isEmpty()) {
            return false;
        }
        int i = (w.charAt(0) == '-' || w.charAt(0) == '+') ? 1 : 0;
        if (i >= w.length()) {
            return false;
        }
        boolean dot = false;
        for (; i < w.length(); i++) {
            char c = w.charAt(i);
            if (c == '.') {
                if (dot) {
                    return false;
                }
                dot = true;
            } else if (!Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikePath(String w) {
        return w.indexOf('/') >= 0 || w.indexOf('\\') >= 0 || w.startsWith("~");
    }
}
