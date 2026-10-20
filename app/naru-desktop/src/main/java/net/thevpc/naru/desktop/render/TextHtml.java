package net.thevpc.naru.desktop.render;

/**
 * Plain text as the HTML the transcript uses, under two hard constraints of Swing's HTML engine:
 * <ul>
 *     <li>a {@code <pre>} line never wraps (it maps to an unbreakable line view), so everything
 *     that must stay inside the message width is emitted as a normal paragraph where line breaks
 *     survive as {@code <br>} and leading indentation as {@code &nbsp;} (which does not collapse),</li>
 *     <li>a paragraph can never be laid out narrower than its longest unbreakable run, so a URL
 *     or an id anywhere in the text would keep every line at that run's width and the rest would
 *     just be clipped — even though the engine <em>can</em> break such a run when asked.</li>
 * </ul>
 * The second constraint is why long tokens are emitted as a run of small {@code <span>}s: an
 * inline element boundary is a break opportunity, and the paragraph's minimum width stops being
 * the token's width, so the message wraps at the width the card gives it — at any window size.
 * Deterministic and headless — no Swing — so it stays unit-testable like {@link MarkdownHtml}.
 */
public final class TextHtml {

    /** Tokens stay readable as one word until wrapping them actually matters. */
    private static final int MIN_SPLIT_TOKEN = 16;
    /** Width of one span: small enough that a chat column can always be narrower than it. */
    private static final int SPLIT_EVERY = 8;

    private TextHtml() {
    }

    /** A wrapping paragraph for readable text: line breaks survive, indentation is kept. */
    public static String paragraphs(String text) {
        return block(text, "margin:0");
    }

    /** The same, in a fixed-width font — what a code block or a log line wants. */
    public static String codeBlock(String text) {
        return block(text, "font-family:monospace;margin:2px 0");
    }

    /**
     * Escapes one line (or a whitespace-free run such as a URL) and, when it is long enough to
     * block wrapping, splits it into wrappable spans — escaping hostile model output and making
     * it wrappable is one step.
     */
    public static String escapeWrap(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 32);
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                flushToken(token, out);
                out.append(' ');
            } else {
                token.append(c);
            }
        }
        flushToken(token, out);
        return out.toString();
    }

    private static void flushToken(StringBuilder token, StringBuilder out) {
        if (token.isEmpty()) {
            return;
        }
        String value = token.toString();
        token.setLength(0);
        if (value.length() <= MIN_SPLIT_TOKEN) {
            out.append(escape(value));
            return;
        }
        for (int i = 0; i < value.length(); i += SPLIT_EVERY) {
            out.append("<span>")
                    .append(escape(value.substring(i, Math.min(i + SPLIT_EVERY, value.length()))))
                    .append("</span>");
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String block(String text, String style) {
        StringBuilder out = new StringBuilder("<p style='").append(style).append("'>");
        if (text != null && !text.isEmpty()) {
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    out.append("<br>");
                }
                out.append(line(lines[i]));
            }
        }
        return out.append("</p>").toString();
    }

    /** One line: leading whitespace becomes non-collapsing spaces, the rest escapes and wraps. */
    private static String line(String line) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == ' ') {
                out.append("&nbsp;");
            } else if (c == '\t') {
                out.append("&nbsp;&nbsp;&nbsp;&nbsp;");
            } else {
                break;
            }
            i++;
        }
        return out.append(escapeWrap(line.substring(i))).toString();
    }
}
