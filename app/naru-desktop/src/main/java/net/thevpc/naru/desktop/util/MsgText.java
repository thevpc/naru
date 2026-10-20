package net.thevpc.naru.desktop.util;

/**
 * Turns Nuts message text into something a Swing transcript can show.
 * <p>
 * {@code NMsg.toString()} returns the message with its style markup still embedded
 * ({@code ##{p9:ollama}##}), because rendering that markup is the terminal formatter's job and the
 * desktop is not a terminal. Neither {@code NMsg.toString(true)} nor {@code NText.of(msg).filteredText()}
 * fixes it: {@code toString(true)} only un-styled messages that Nuts itself wrapped in a style, and a
 * markup token that arrives through a {@code %s} parameter is left verbatim. Measured on the real
 * string that leaked into the Phase 1 gate run:
 * <pre>
 *   toString()      -> Selected model : ##{p9:ollama}####{separator:/}####{p6:qwen2.5-coder:14b}##
 *   filteredText()  -> same, because the token came from a parameter, not from a styled NMsg
 * </pre>
 * So the desktop strips the tokens where it converts a message to text. Only the documented
 * {@code ##{style:text}##} and {@code ##{style}##} shapes are handled, plus a leftover marker pair;
 * anything else is passed through untouched — a transcript that shows one odd character is
 * recoverable, a transcript that silently eats model output is not.
 */
public final class MsgText {

    private MsgText() {
    }

    /**
     * Remove Nuts inline-style markup, keeping the text it decorated.
     *
     * @param raw text as it came out of {@code NMsg.toString()}
     * @return plain text, or {@code raw} itself when no marker is present
     */
    public static String plain(String raw) {
        if (raw == null) {
            return null;
        }
        raw = stripTerminalControls(raw);
        if (raw.indexOf('#') < 0) {
            // the common case: model output almost never carries markup, and this keeps
            // the hot path a single scan instead of a regex
            return raw;
        }
        int i = 0;
        StringBuilder sb = null;
        int n = raw.length();
        while (i < n) {
            if (raw.charAt(i) == '#' && i + 1 < n && raw.charAt(i + 1) == '#'
                    && (i + 2 < n) && raw.charAt(i + 2) == '{') {
                int close = closing(raw, i + 2, '{', '}');
                int end = close < 0 ? -1 : raw.indexOf("##", close + 1);
                if (end > 0) {
                    if (sb == null) {
                        sb = new StringBuilder(n);
                        // chars before the first token are ordinary text and must survive
                        sb.append(raw, 0, i);
                    }
                    sb.append(inner(raw.substring(i + 3, close)));
                    i = end + 2;
                    continue;
                }
            }
            if (sb != null) {
                sb.append(raw.charAt(i));
            }
            i++;
        }
        return sb == null ? raw : sb.toString();
    }

    /** Console streams can contain ANSI CSI/OSC sequences and other non-printing controls. */
    private static String stripTerminalControls(String raw) {
        StringBuilder out = null;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == 0x1b) {
                if (out == null) { out = new StringBuilder(raw.length()); out.append(raw, 0, i); }
                if (i + 1 < raw.length() && raw.charAt(i + 1) == '[') {
                    i += 2;
                    while (i < raw.length()) {
                        char end = raw.charAt(i);
                        if (end >= 0x40 && end <= 0x7e) break;
                        i++;
                    }
                } else if (i + 1 < raw.length() && raw.charAt(i + 1) == ']') {
                    i += 2;
                    while (i < raw.length() && raw.charAt(i) != 7
                            && !(raw.charAt(i) == 0x1b && i + 1 < raw.length() && raw.charAt(i + 1) == '\\')) i++;
                    if (i < raw.length() && raw.charAt(i) == 0x1b) i++;
                } else if (i + 1 < raw.length()) i++;
                continue;
            }
            if (out != null) {
                if ((c >= 0x20 || c == '\n' || c == '\r' || c == '\t') && c != 0x7f) out.append(c);
            } else if (c < 0x20 && c != '\n' && c != '\r' && c != '\t' || c == 0x7f) {
                out = new StringBuilder(raw.length()); out.append(raw, 0, i);
            }
        }
        return out == null ? raw : out.toString();
    }

    /** {@code style:text} or {@code text} — a token with no colon is pure text. */
    private static String inner(String body) {
        int colon = body.indexOf(':');
        // only split on the first colon when the left side looks like a style id
        // (no spaces, no path separators): model output is full of "key:value" pairs
        // that must survive intact
        if (colon > 0 && colon < body.length() - 1 && isStyleName(body.substring(0, colon))) {
            return body.substring(colon + 1);
        }
        return body;
    }

    private static boolean isStyleName(String s) {
        if (s.isEmpty() || s.length() > 24) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static int closing(String s, int from, char open, char close) {
        if (s.charAt(from) != open) {
            return -1;
        }
        int depth = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
