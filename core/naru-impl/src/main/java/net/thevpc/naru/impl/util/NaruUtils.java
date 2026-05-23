package net.thevpc.naru.impl.util;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.impl.cmd.NaruTerminalFormatter;
import net.thevpc.naru.impl.model.ollama.NaruOllamaProvider;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.log.NLog;
import net.thevpc.nuts.net.NWebRequest;
import net.thevpc.nuts.text.*;
import net.thevpc.nuts.time.NChronometer;
import net.thevpc.nuts.util.NStringUtils;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class NaruUtils {
    public static String stripAnsi(String text) {
        if (text == null) return null;
        return text.replaceAll("\u001B(\\[[;\\d]*[A-Za-z]|[^\\[\\]])", "");
    }

    public static String abbreviate(String s, int max) {
        if (s == null) return "(null)";
        s = s.replace('\n', ' ');
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public static void logWebRequest(NWebRequest url, NMsg text, Object input) {
        NLog.of(NaruOllamaProvider.class)
                .log(NMsg.ofC("%s : REQUEST %s\n%s", text, url.effectiveUrl(),
                        NElementWriter.ofJson().formatPlain(input)
                ));
    }
    public static void logWebResponse(NWebRequest request, NMsg text, Object input, NChronometer chronometer) {
        NLog.of(NaruOllamaProvider.class)
                .log(NMsg.ofC("%s : RESPONSE %s\n%s", text, request.effectiveUrl(),
                        NElementWriter.ofJson().formatPlain(input)
                ).withDuration(chronometer.duration()));
    }

    public static void showItems(List<NText> items, Set<Integer> toShow, NaruSession sessionContext) {
        int lastKey = items.size() + 1;
        if (toShow.isEmpty()) {
            if (!items.isEmpty()) {
                for (int i = 0; i < items.size(); i++) {
                    NText e = items.get(i);
                    showItem(i + 1, lastKey, e, sessionContext);
                }
            }
        } else {
            List<Integer> bb = toShow.stream().sorted().collect(Collectors.toList());
            for (int k = 0; k < bb.size(); k++) {
                int i = bb.get(k);
                int im1 = i - 1;
                if (im1 >= 0 && im1 < items.size()) {
                    showItem(i, lastKey, items.get(im1), sessionContext);
                }
            }
        }
    }


    private static void showItem(int rowIndex, int max, NText item, NaruSession sessionContext) {
        int zeros = (int) Math.ceil(Math.log10(max));
        if (zeros <= 0) {
            zeros = 1;
        }
        DecimalFormat zformat = new DecimalFormat(NStringUtils.repeat("0", zeros));

        List<NText> lines = item.splitLines();
        for (int j = 0; j < lines.size(); j++) {
            NText line = lines.get(j);
            if (j == 0) {
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s",
                        NMsg.ofStyledNumber(zformat.format(rowIndex)),
                        line));
            } else {
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("        %s", line));
            }
        }
        if (lines.isEmpty()) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s",
                    NMsg.ofStyledNumber(zformat.format(rowIndex)),
                    item));
        }
    }

    public static class LineRange {
        private final int from;
        private final int to;

        public LineRange(int from, int to) {
            this.from = from;
            this.to = to;
        }

        public LineRange(int from) {
            this.from = from;
            this.to = from;
        }
    }

    public static Set<Integer> resolveIndexes(LineRange[] ranges, int count) {
        Set<Integer> toShow = new HashSet<>();
        for (LineRange r : ranges) {
            int from = r.from;
            int to = r.to;
            if (from < 0) {
                from = count + from;
            }
            if (to < 0) {
                to = count + to;
            }
            for (int i = from; i <= to; i++) {
                toShow.add(i);
            }
        }
        return toShow;
    }

    public static List<LineRange> parseRanges(NCmdLine cmdLine) {
        List<LineRange> toShow = new ArrayList<>();
        while (!cmdLine.isEmpty()) {
            String a = cmdLine.next().get().image();
            for (String range : a.split(",;")) {
                range = range.trim();
                if (!range.isEmpty()) {
                    if (range.matches("[0-9]+")) {
                        toShow.add(new LineRange(Integer.parseInt(range) - 1));
                    } else if (range.matches("[0-9]+[-][0-9]+")) {
                        String[] ss = range.split("-");
                        int x = Integer.parseInt(ss[0]);
                        int y = Integer.parseInt(ss[1]);
                        toShow.add(new LineRange(x, y));
                    } else if (range.matches("[-]?[0-9]+[.][.][-]?[0-9]+")) {
                        String[] ss = range.split("[.][.]");
                        int x = Integer.parseInt(ss[0]);
                        int y = Integer.parseInt(ss[1]);
                        toShow.add(new LineRange(x, y));
                    } else if (range.matches("[-]?[0-9]+[.][.][.][-]?[0-9]+")) {
                        String[] ss = range.split("[.][.][.]");
                        int x = Integer.parseInt(ss[0]);
                        int y = Integer.parseInt(ss[1]);
                        toShow.add(new LineRange(x, y));
                    } else if (range.matches("[-]?[0-9]+[.][.][.]")) {
                        int x = Integer.parseInt(range.substring(0, range.length() - 3));
                        toShow.add(new LineRange(x, Integer.MAX_VALUE));
                    } else if (range.matches("[.][.][.][-]?[0-9]+")) {
                        int x = Integer.parseInt(range.substring(3));
                        toShow.add(new LineRange(1, x));
                    } else {
                        throw new IllegalArgumentException("invalid position to drop");
                    }
                }
            }
        }
        return toShow;
    }


    public static NText formattedTokensSize(long cl) {
        if (cl > 0) {
            long m = 1024 * 1024;
            long k = 1024;
            if (cl >= m) {
                return NTextBuilder.of()
                        .append((cl / m), NTextStyle.number())
                        .append("M", NTextStyle.separator())
                        .build();
            } else if (cl >= k) {
                return NTextBuilder.of()
                        .append((cl / k), NTextStyle.number())
                        .append("k", NTextStyle.separator())
                        .build();
            } else {
                return NTextBuilder.of()
                        .append(cl, NTextStyle.number())
                        .build();
            }
        } else if (cl == 0) {
            return NTextBuilder.of()
                    .append(0L, NTextStyle.number())
                    .build();
        } else {
            return NTextBuilder.of()
                    .append(0L, NTextStyle.number())
                    .build();
        }
    }

    public static void showItemsWithFormat(String context2, String format, List<LineRange> ranges, NaruSession sessionContext) {
        NText nText;
        switch (format) {
            case "markdown": {
                nText = NaruTerminalFormatter.formatMarkdown(context2, null);
                break;
            }
            default: {
                nText = NTexts.of().ofPlain(context2);
                break;
            }
        }
        List<NText> linesOk = nText.splitLines();
        Set<Integer> toShow = NaruUtils.resolveIndexes(ranges.toArray(new LineRange[0]), linesOk.size());
        NaruUtils.showItems(linesOk, toShow, sessionContext);
    }
}
