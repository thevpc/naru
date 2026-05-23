package net.thevpc.naru.impl.cmd;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.tool.NaruDirective;
import net.thevpc.naru.impl.agent.NaruAgentImpl;
import net.thevpc.nuts.io.NTerminalFormatter;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTextBuilder;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.text.NTextStyles;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NStringUtils;

import java.util.List;

public class NaruTerminalFormatter implements NTerminalFormatter {
    private final NaruAgentImpl naruAgent;

    public NaruTerminalFormatter(NaruAgentImpl naruAgent) {
        this.naruAgent = naruAgent;
    }

    @Override
    public NText format(Context context) {
        return formatInput(context.buffer(), naruAgent);
    }

    public static List<NText> formatOutputLines(String b, NText prefix) {
        NText m = formatMarkdown(b, NTextStyles.of());
        if (prefix == null || prefix.isEmpty()) {
            return m.normalize().splitLines();
        }
        List<NText> lines = m.normalize().splitLines();
        List<NText> newLines = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            NText line = lines.get(i);
            newLines.add(NTextBuilder.of().append(prefix).append(line).build());
        }
        return newLines;
    }

    public static NText formatOutput(String b, NText prefix) {
        NText m = formatMarkdown(b, NTextStyles.of());
        if (prefix == null || prefix.isEmpty()) {
            return m;
        }
        NTextBuilder tb = NTextBuilder.of();
        List<NText> lines = m.normalize().splitLines();
        for (int i = 0; i < lines.size(); i++) {
            NText line = lines.get(i);
            if (i == lines.size() - 1 && line.isEmpty()) break; // trailing newline artifact
            tb.append(prefix).append(line).append("\n");
        }
        return tb.build();
    }

    public static NText formatInput(String b, NaruAgent agent) {
        String b2 = b.trim();
        if (!b2.isEmpty()) {
            if (b2.startsWith("#")) {
                return formatCommentsLine(b);
            }
            if (b2.startsWith("/")) {
                return formatDirective(b, agent);
            }
            if (Character.isDigit(b2.charAt(0))) {
                return formatRoutineLine(b);
            }
        }
        return formatMarkdown(b, NTextStyles.of(NTextStyle.italic()));
    }

    public static NText formatCommentsLine(String b) {
        return NTextBuilder.of()
                .append(b, NTextStyle.comments()).build();
    }

    public static NText formatRoutineLine(String b) {
        StringBuilder prefixWhites = new StringBuilder();
        StringBuilder nbr = new StringBuilder();
        StringBuilder rest = new StringBuilder();
        int step = 0;
        for (int i = 0; i < b.length(); i++) {
            char c = b.charAt(i);
            switch (step) {
                case 0: {
                    if (Character.isWhitespace(c)) {
                        prefixWhites.append(c);
                    } else if (Character.isDigit(c)) {
                        nbr.append(c);
                        step = 1;
                    } else {
                        rest.append(c);
                        step = 2;
                    }
                    break;
                }
                case 1: {
                    if (Character.isDigit(c)) {
                        nbr.append(c);
                    } else {
                        step = 2;
                        rest.append(c);
                    }
                    break;
                }
                default: {
                    rest.append(c);
                }
            }
        }
        return NTextBuilder.of()
                .append(prefixWhites.toString())
                .append(nbr.toString(), NTextStyle.number())
                .append(rest.toString(), NTextStyle.italic())
                .build();
    }

    public static NText formatMarkdown(String b, NTextStyles extraStyles) {
        if (extraStyles == null) {
            extraStyles = NTextStyles.of();
        }
        NTextBuilder tb = NTextBuilder.of();
        int i = 0;
        int len = b.length();

        while (i < len) {
            // bold: **text**
            if (i + 1 < len && b.charAt(i) == '*' && b.charAt(i + 1) == '*') {
                int end = b.indexOf("**", i + 2);
                if (end != -1) {
                    tb.append("**", NTextStyle.pale());
                    tb.append(b.substring(i + 2, end), extraStyles.append(NTextStyle.bold()));
                    tb.append("**", NTextStyle.pale());
                    i = end + 2;
                    continue;
                }
            }

            // italic: *text*
            if (b.charAt(i) == '*') {
                int end = b.indexOf('*', i + 1);
                if (end != -1) {
                    tb.append("*", NTextStyle.pale());
                    tb.append(b.substring(i + 1, end), extraStyles.append(NTextStyle.italic()));
                    tb.append("*", NTextStyle.pale());
                    i = end + 1;
                    continue;
                }
            }

            if (b.startsWith("```", i)) {
                int end = i + 3;
                while (end < len) {
                    if (b.startsWith("```", end)) {
                        end += 3;
                        break;
                    }
                    if (b.startsWith("\\", end)) {
                        end++;
                    }
                    end++;
                }
                String code = b.substring(i + 3, end - 3);
                i = end;
                int j = NStringUtils.firstIndexOf(code, ' ', '\t', '\n', '\r');
                if (j > 0) {
                    if (code.substring(0, j).matches("[a-z]+")) {
                        tb.append(NText.ofCode(code.substring(0, j), code.substring(j + 1).trim()));
                    } else {
                        tb.append(NText.ofCode("", code));
                    }
                } else {
                    tb.append(NText.ofCode("", code));
                }
                continue;
            }

            // inline code: `text`
            if (b.charAt(i) == '`') {
                int end = b.indexOf('`', i + 1);
                if (end != -1) {
                    tb.append("`", NTextStyle.pale());
                    tb.append(b.substring(i + 1, end), extraStyles.append(NTextStyle.primary3()));
                    tb.append("`", NTextStyle.pale());
                    i = end + 1;
                    continue;
                }
            }
            // URL: http:// or https://
            if (b.startsWith("http://", i) || b.startsWith("https://", i)) {
                int end = i;
                while (end < len && !Character.isWhitespace(b.charAt(end))) end++;
                tb.append(b.substring(i, end), extraStyles.append(NTextStyle.underlined()));
                i = end;
                continue;
            }

            // file path: /foo or ~/foo
            if (b.charAt(i) == '/' || (b.charAt(i) == '~' && i + 1 < len && b.charAt(i + 1) == '/')) {
                int end = i;
                while (end < len && !Character.isWhitespace(b.charAt(end))) end++;
                if (end > i + 1) { // avoid single '/' which is a directive
                    tb.append(b.substring(i, end), extraStyles.append(NTextStyle.primary6()));
                    i = end;
                    continue;
                }
            }

            // plain character
            int start = i;
            while (i < len) {
                char c = b.charAt(i);
                if (c == '*' || c == '`' || c == '/' || c == '~') break;
                if (b.startsWith("http://", i) || b.startsWith("https://", i)) break;
                i++;
            }
            if (i > start) {
                tb.append(b.substring(start, i), extraStyles);
            } else {
                // No pattern matched and plain-char loop broke immediately:
                // consume the character as-is to guarantee progress
                tb.append(String.valueOf(b.charAt(i)), extraStyles);
                i++;
            }
        }
        return tb.build();
    }

    public static NText formatDirective(String b, NaruAgent agent) {
        String b2 = b.trim();
        if (b2.equals("/")) {
            return NTextBuilder.of()
                    .append(b, NTextStyle.primary4()).build();
        }
        int i0 = b.indexOf('/');
        int i1 = b.length();
        for (int i = i0 + 1; i < b.length(); i++) {
            char c = b.charAt(i);
            if (c == '?' || c == '#' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || (c == '-' || c == '_')) {
                //valid directive
            } else {
                i1 = i;
                break;
            }
        }
        NTextBuilder tb = NTextBuilder.of();
        tb.append(b.substring(0, i0));
        String directive = b.substring(i0, i1);
        NOptional<NaruDirective> d = agent.registry().findDirective(directive.substring(1));
        if (d.isPresent()) {
            tb.append(directive, NTextStyle.primary5());
        } else {
            tb.append(directive, NTextStyle.error());
        }
        String directiveArgs = b.substring(i1);
        tb.append(NTerminalFormatter.ofSystemHighlighter().format(new Context() {
            @Override
            public String buffer() {
                return directiveArgs;
            }
        }), NTextStyle.primary4());

        return tb.build();
    }
}
