package net.thevpc.naru.desktop.ui;

import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

/**
 * Guesses what a non-command composer draft is written in, so it can be coloured as that language
 * instead of as grey text.
 * <p>
 * The engine offers {@code NCodeHighlighter} ids — java, python, sh, markdown, json — but no
 * detection behind them: nothing in naru looks at content and answers "this is Java". Inventing that
 * in the engine would be a new behaviour, not a new seam, and the plan forbids changing agent
 * behaviour from this work, so the guess lives here, in the one place that needs a colour.
 * <p>
 * The rules are signatures, not statistics: a shebang, a statement only one language writes, or three
 * weak signals from the same family. Anything else stays plain, because painting prose as Java is
 * worse than not painting it. It reads a bounded prefix and never touches a file or a registry, so it
 * is safe to call on every keystroke.
 */
public final class ComposerLanguage {

    /** The prefix a guess is made from: long enough for imports and a function, short enough to scan. */
    private static final int LOOKAT = 4_000;

    private ComposerLanguage() {
    }

    /**
     * @return a {@link SyntaxConstants} id RSTA can build a token maker for, or
     * {@link SyntaxConstants#SYNTAX_STYLE_NONE} when nothing matched — which is the answer for prose
     */
    public static String detect(String draft) {
        if (draft == null || draft.isBlank()) {
            return SyntaxConstants.SYNTAX_STYLE_NONE;
        }
        String text = draft.length() > LOOKAT ? draft.substring(0, LOOKAT) : draft;
        String first = firstNonBlankLine(text);
        if (first == null) {
            return SyntaxConstants.SYNTAX_STYLE_NONE;
        }
        String stripped = first.strip();
        String low = text.toLowerCase();

        // A shebang is the one thing a draft says about itself that is never ambiguous.
        if (stripped.startsWith("#!")) {
            String line = stripped.toLowerCase();
            if (line.contains("python")) {
                return SyntaxConstants.SYNTAX_STYLE_PYTHON;
            }
            if (line.contains("node")) {
                return SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
            }
            return SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL;
        }
        if ((stripped.startsWith("{") || stripped.startsWith("[")) && text.contains("\":")) {
            return SyntaxConstants.SYNTAX_STYLE_JSON;
        }
        if (startsWithAny(stripped.toLowerCase(), "select ", "insert into ", "update ", "delete from ",
                "with ", "create table ", "drop table ")) {
            return SyntaxConstants.SYNTAX_STYLE_SQL;
        }
        if (contains(text, "public class ", "private static ", "protected void ",
                "public static void main", "import java.", "import net.thevpc.", "@Override", "@Test")) {
            return SyntaxConstants.SYNTAX_STYLE_JAVA;
        }
        if (contains(text, "def ", "elif ", "self.", "import ", "print(") && !text.contains(";")) {
            return SyntaxConstants.SYNTAX_STYLE_PYTHON;
        }
        if (contains(text, "const ", "let ", "=> ", "console.log", "function (")) {
            return SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
        }
        if (contains(low, "$(", "${", " sudo ", " apt-get ", "| grep", "set -e", "then\n", "fi\n")) {
            return SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL;
        }
        if (contains(text, "```", "\n## ", "\n- [", "\n> ")) {
            return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        }
        // Three of four shape signals is code in a brace language, one is a sentence with a comma.
        if (score(text, ";", "{", "}", "//") >= 3) {
            return SyntaxConstants.SYNTAX_STYLE_JAVA;
        }
        if (score(low, "\n$", " | ", "echo ", "chmod ", "export ") >= 2) {
            return SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL;
        }
        return SyntaxConstants.SYNTAX_STYLE_NONE;
    }

    private static String firstNonBlankLine(String text) {
        for (String line : text.split("\n", 8)) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return null;
    }

    private static boolean startsWithAny(String text, String... prefixes) {
        for (String p : prefixes) {
            if (text.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String text, String... any) {
        for (String s : any) {
            if (text.contains(s)) {
                return true;
            }
        }
        return false;
    }

    private static int score(String text, String... signals) {
        int n = 0;
        for (String s : signals) {
            if (text.contains(s)) {
                n++;
            }
        }
        return n;
    }
}
