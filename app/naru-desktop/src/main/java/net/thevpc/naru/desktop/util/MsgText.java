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
import net.thevpc.naru.desktop.render.TextHtml;
import net.thevpc.naru.desktop.ui.UiPalette;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTextAnchor;
import net.thevpc.nuts.text.NTextCode;
import net.thevpc.nuts.text.NTextLink;
import net.thevpc.nuts.text.NTextList;
import net.thevpc.nuts.text.NTextPlain;
import net.thevpc.nuts.text.NTextStyled;
import net.thevpc.nuts.text.NTextStyles;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.text.NTextStyleType;
import net.thevpc.nuts.text.NTextTitle;
import net.thevpc.nuts.text.NTextType;
import net.thevpc.nuts.text.NMsg;

import java.awt.Color;

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

    // ---------------------------------------------------------------- NTF rendering

    /** Render an NTF (Nuts Text Format) node tree as HTML, preserving style/color/accent. */
    public static String html(NText text) { return render(text); }
    public static String rich(NText text) { return render(text); }
    public static String styled(NText text) { return render(text); }
    public static String toHtml(NText text) { return render(text); }
    public static String ntfToHtml(NText text) { return render(text); }
    public static String renderNtf(NText text) { return render(text); }
    public static String ntf(NText text) { return render(text); }

    public static String html(NMsg msg) { return render(msg == null ? null : NText.of(msg)); }
    public static String rich(NMsg msg) { return render(msg == null ? null : NText.of(msg)); }
    public static String styled(NMsg msg) { return render(msg == null ? null : NText.of(msg)); }
    public static String toHtml(NMsg msg) { return render(msg == null ? null : NText.of(msg)); }
    public static String ntfToHtml(NMsg msg) { return render(msg == null ? null : NText.of(msg)); }
    public static String renderNtf(NMsg msg) { return render(msg == null ? null : NText.of(msg)); }

    public static String render(NText text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append("<p style='margin:0'>");
        renderNode(out, text, null);
        out.append("</p>");
        return out.toString();
    }

    private static void renderNode(StringBuilder out, NText node, NTextStyles styles) {
        if (node == null) {
            return;
        }
        NTextType type = node.type();
        switch (type) {
            case PLAIN -> appendText(out, ((NTextPlain) node).value(), styles, false);
            case STYLED -> {
                NTextStyled s = (NTextStyled) node;
                renderNode(out, s.child(), appendStyles(styles, s.styles()));
            }
            case LIST -> {
                NTextList list = (NTextList) node;
                for (int i = 0; i < list.children().size(); i++) {
                    if (i > 0) out.append("<br>");
                    renderNode(out, list.children().get(i), styles);
                }
            }
            case TITLE -> {
                NTextTitle t = (NTextTitle) node;
                renderNode(out, t.child(), appendStyles(styles, NTextStyles.of(NTextStyle.bold())));
            }
            case CODE -> {
                NTextCode c = (NTextCode) node;
                appendText(out, c.value(), styles, true);
            }
            case LINK -> appendText(out, ((NTextLink) node).value(), styles, false);
            case ANCHOR -> appendText(out, ((NTextAnchor) node).value(), styles, false);
            case INCLUDE -> appendText(out, ((net.thevpc.nuts.text.NTextInclude) node).text(), styles, false);
            default -> appendText(out, node.filteredText(), styles, false);
        }
    }

    private static NTextStyles appendStyles(NTextStyles base, NTextStyles add) {
        if (add == null || add.isPlain()) return base;
        if (base == null || base.isPlain()) return add;
        return base.append(add);
    }

    private static void appendText(StringBuilder out, String text, NTextStyles styles, boolean mono) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String[] lines = text.split("\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append("<br>");
            String escaped = TextHtml.escapeWrap(lines[i]);
            StringBuilder attr = new StringBuilder();
            if (mono) attr.append("font-family:monospace");
            String css = css(styles);
            if (!css.isEmpty()) {
                if (attr.length() > 0) attr.append(";");
                attr.append(css);
            }
            if (attr.length() > 0) {
                out.append("<span style='").append(attr).append("'>").append(escaped).append("</span>");
            } else {
                out.append(escaped);
            }
        }
    }

    private static String css(NTextStyles styles) {
        if (styles == null || styles.isPlain()) return "";
        StringBuilder css = new StringBuilder();
        boolean bold = false, italic = false, underline = false, strike = false;
        for (NTextStyle st : styles) {
            Color c = colorFor(st);
            if (c != null) {
                if (css.length() > 0) css.append(";");
                css.append("color:").append(UiPalette.css(c));
            }
            switch (st.type()) {
                case BOLD -> bold = true;
                case ITALIC -> italic = true;
                case UNDERLINED -> underline = true;
                case STRIKED -> strike = true;
                default -> {
                }
            }
        }
        if (bold) { if (css.length() > 0) css.append(";"); css.append("font-weight:bold"); }
        if (italic) { if (css.length() > 0) css.append(";"); css.append("font-style:italic"); }
        if (underline) { if (css.length() > 0) css.append(";"); css.append("text-decoration:underline"); }
        if (strike) { if (css.length() > 0) css.append(";"); css.append("text-decoration:line-through"); }
        return css.toString();
    }

    private static final Color[] ANSI = new Color[] {
            new Color(0x00,0x00,0x00), new Color(0xCD,0x00,0x00), new Color(0x00,0xCD,0x00),
            new Color(0xCD,0xCD,0x00), new Color(0x00,0x00,0xEE), new Color(0xCD,0x00,0xCD),
            new Color(0x00,0xCD,0xCD), new Color(0xE5,0xE5,0xE5), new Color(0x7F,0x7F,0x7F),
            new Color(0xFF,0x00,0x00), new Color(0x00,0xFF,0x00), new Color(0xFF,0xFF,0x00),
            new Color(0x5C,0x5C,0xFF), new Color(0xFF,0x00,0xFF), new Color(0x00,0xFF,0xFF),
            new Color(0xFF,0xFF,0xFF)
    };

    private static Color colorFor(NTextStyle style) {
        NTextStyleType type = style.type();
        int v = style.variant();
        return switch (type) {
            case PRIMARY, INFO -> UiPalette.accent();
            case SECONDARY, CONFIG, COMMENTS, PALE -> UiPalette.muted();
            case SUCCESS -> new Color(0x10, 0xB9, 0x81);
            case WARN -> new Color(0xF5, 0x9E, 0x0B);
            case ERROR, FAIL, DANGER -> new Color(0xEF, 0x44, 0x44);
            case STRING -> new Color(0xF0, 0xA3, 0x6F);
            case NUMBER -> new Color(0x6C, 0xD3, 0xC0);
            case PATH -> new Color(0x9C, 0xDC, 0xFE);
            case KEYWORD -> new Color(0xC7, 0x92, 0xEA);
            case FORE_COLOR -> (v >= 0 && v < ANSI.length) ? ANSI[v] : null;
            case FORE_TRUE_COLOR -> (v >= 0 && v <= 0xFFFFFF) ? new Color(v) : null;
            default -> null;
        };
    }

}
