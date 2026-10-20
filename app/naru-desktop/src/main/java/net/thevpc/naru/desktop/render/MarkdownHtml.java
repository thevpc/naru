package net.thevpc.naru.desktop.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown to the small HTML subset {@code JLabel} can render — used only for closed blocks,
 * off the EDT, cached on the row model (plan §4.3).
 * <p>
 * Why this exists rather than a library: §4.3 assumed {@code thevpc-common-md}/{@code nmarkdown}
 * could produce HTML. Neither has an HTML writer ({@code DefaultMdWriter} writes markdown back
 * out; the other writers emit AsciiDoc and Docusaurus), and naru itself only ever formats
 * markdown into terminal styles via {@code NaruTerminalFormatter}. Adding a third-party parser
 * to a Nuts application is a packaging decision, not a UI one, so v1 renders the subset that
 * actually appears in agent answers: fenced code, headings, unordered/ordered lists, block
 * quotes, hr, and inline code/bold/italic/links. Everything else passes through as a paragraph,
 * escaped, so a weird input can never inject markup.
 * <p>
 * Deterministic and headless — no Swing, no I/O — which is what makes it unit-testable.
 */
public final class MarkdownHtml {

    private MarkdownHtml() {
    }

    /**
     * An HTML <em>fragment</em>: no {@code <html>} or {@code <body>} wrapper, because the caller
     * supplies the width constraint that makes a {@code JLabel} wrap at the column instead of
     * running off the pane.
     */
    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        List<String> lines = splitLines(markdown);
        int i = 0;
        boolean inList = false;
        String listKind = null;
        while (i < lines.size()) {
            String line = lines.get(i);

            if (line.startsWith("```") || line.startsWith("~~~")) {
                if (inList) {
                    out.append("</").append(listKind).append(">");
                    inList = false;
                }
                String fence = line.substring(0, 3);
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.size() && !lines.get(i).startsWith(fence)) {
                    code.append(lines.get(i)).append('\n');
                    i++;
                }
                if (i < lines.size()) {
                    i++; // skip closing fence
                }
                out.append(TextHtml.codeBlock(code.toString()));
                continue;
            }

            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (inList) {
                    out.append("</").append(listKind).append(">");
                    inList = false;
                }
                i++;
                continue;
            }
            if (trimmed.matches("(-{3,}|\\*{3,}|_{3,})")) {
                if (inList) {
                    out.append("</").append(listKind).append(">");
                    inList = false;
                }
                out.append("<hr>");
                i++;
                continue;
            }

            int heading = headingLevel(trimmed);
            if (heading > 0) {
                if (inList) {
                    out.append("</").append(listKind).append(">");
                    inList = false;
                }
                String body = trimmed.substring(heading).trim();
                out.append("<b style=\"font-size:")
                        .append(switch (heading) {
                            case 1 -> "1.35em";
                            case 2 -> "1.2em";
                            case 3 -> "1.1em";
                            default -> "1.0em";
                        })
                        .append("\">").append(inline(body)).append("</b><br>");
                i++;
                continue;
            }

            String bullet = bulletOf(trimmed);
            if (bullet != null) {
                String kind = Character.isDigit(bullet.charAt(0)) ? "ol" : "ul";
                if (!inList) {
                    out.append("<").append(kind).append(" style=\"margin:2px 0 2px 14px;padding:0\">");
                    inList = true;
                    listKind = kind;
                }
                out.append("<li>").append(inline(trimmed.substring(trimmed.indexOf(bullet) + bullet.length()).trim()))
                        .append("</li>");
                i++;
                continue;
            }
            if (trimmed.startsWith(">")) {
                if (inList) {
                    out.append("</").append(listKind).append(">");
                    inList = false;
                }
                out.append("<i>&nbsp;&nbsp;").append(inline(trimmed.substring(1).trim())).append("</i><br>");
                i++;
                continue;
            }

            if (inList) {
                out.append("</").append(listKind).append(">");
                inList = false;
            }
            out.append(inline(trimmed)).append("<br>");
            i++;
        }
        if (inList) {
            out.append("</").append(listKind).append(">");
        }
        return out.toString();
    }

    private static int headingLevel(String trimmed) {
        int n = 0;
        while (n < 6 && n < trimmed.length() && trimmed.charAt(n) == '#') {
            n++;
        }
        return n > 0 && n < trimmed.length() && trimmed.charAt(n) == ' ' ? n : -1;
    }

    /** The marker text of a list item ("-", "*•", "1."), or null when the line is not a item. */
    private static String bulletOf(String trimmed) {
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            return trimmed.substring(0, 1);
        }
        int dot = trimmed.indexOf('.');
        if (dot > 0 && dot <= 3 && trimmed.length() > dot + 1 && trimmed.charAt(dot + 1) == ' ') {
            String num = trimmed.substring(0, dot);
            if (num.chars().allMatch(Character::isDigit)) {
                return num + ".";
            }
        }
        return null;
    }

    private static String inline(String s) {
        StringBuilder out = new StringBuilder();
        // Plain runs are buffered so they can be escaped and seeded with break opportunities in
        // one step — a long URL in a paragraph must wrap like any other token.
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i) {
                    flushPlain(plain, out);
                    out.append("<tt><b>").append(TextHtml.escapeWrap(s.substring(i + 1, end))).append("</b></tt>");
                    i = end + 1;
                    continue;
                }
            }
            if (c == '*' || c == '_') {
                String run = String.valueOf(c);
                int len = 0;
                while (i + len < s.length() && s.charAt(i + len) == c) {
                    len++;
                }
                if (len >= 2) {
                    int close = s.indexOf(run + run, i + len);
                    if (close > i) {
                        flushPlain(plain, out);
                        out.append("<b>").append(inline(s.substring(i + len, close))).append("</b>");
                        i = close + len;
                        continue;
                    }
                }
                if (len == 1) {
                    int close = s.indexOf(run, i + 1);
                    if (close > i + 1) {
                        flushPlain(plain, out);
                        out.append("<i>").append(inline(s.substring(i + 1, close))).append("</i>");
                        i = close + 1;
                        continue;
                    }
                }
                plain.append(run.repeat(len));
                i += len;
                continue;
            }
            if (c == '[') {
                int closeBracket = s.indexOf(']', i);
                if (closeBracket > i && closeBracket + 1 < s.length() && s.charAt(closeBracket + 1) == '(') {
                    int closeParen = s.indexOf(')', closeBracket + 2);
                    if (closeParen > closeBracket) {
                        flushPlain(plain, out);
                        String label = s.substring(i + 1, closeBracket);
                        String href = s.substring(closeBracket + 2, closeParen);
                        out.append("<u>").append(TextHtml.escapeWrap(label)).append("</u>");
                        out.append(" <font size=-2>(").append(TextHtml.escapeWrap(href)).append(")</font>");
                        i = closeParen + 1;
                        continue;
                    }
                }
                plain.append(c);
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                flushPlain(plain, out);
                out.append(' ');
                i++;
                continue;
            }
            plain.append(c);
            i++;
        }
        flushPlain(plain, out);
        return out.toString();
    }

    private static void flushPlain(StringBuilder plain, StringBuilder out) {
        if (plain.length() > 0) {
            out.append(TextHtml.escapeWrap(plain.toString()));
            plain.setLength(0);
        }
    }

    private static List<String> splitLines(String s) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }
}
