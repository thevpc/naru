package net.thevpc.naru.impl.util;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.impl.cmd.NaruTerminalFormatter;
import net.thevpc.naru.impl.model.ollama.NaruOllamaProvider;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.core.NSession;
import net.thevpc.nuts.core.NStoreKey;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.elem.NPairElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NPathOption;
import net.thevpc.nuts.log.NLog;
import net.thevpc.nuts.net.NWebRequest;
import net.thevpc.nuts.platform.NStoreScope;
import net.thevpc.nuts.platform.NStoreType;
import net.thevpc.nuts.text.*;
import net.thevpc.nuts.time.NChronometer;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NStringUtils;

import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class NaruUtils {
    public static String stripAnsi(String text) {
        if (text == null) return null;
        return text.replaceAll("\u001B(\\[[;\\d]*[A-Za-z]|[^\\[\\]])", "");
    }

    public static Map<String, NElement> parseEnv(String msg) {
        if (NBlankable.isBlank(msg)) {
            return Collections.emptyMap();
        }
        NElement aa = NElementReader.ofTson().read(msg);
        return parseEnv(aa);
    }

    public static Map<String, NElement> parseEnv(NElement aa) {
        if (aa == null) {
            return new LinkedHashMap<>();
        }
        Map<String, NElement> env = new HashMap<>();
        if (aa.isNamedPair()) {
            NPairElement p = aa.asPair().get();
            env.put(p.key().asStringValue().orNull(), p.value());
        } else if (aa.isListContainer()) {
            for (NPairElement p : aa.asListContainer().get().namedPairs()) {
                env.put(p.key().asStringValue().orNull(), p.value());
            }
        } else if (aa.isFragment()) {
            for (NPairElement p : aa.asFragment().get().namedPairs()) {
                env.put(p.key().asStringValue().orNull(), p.value());
            }
        }
        return env;
    }

    private static void log(NMsg msg) {
        NPath path = NPath.of(NStoreKey.of(
                NStoreScope.WORKSPACE,
                NStoreType.LOG,
                NId.of("net.thevpc.naru:naru").sharedId(),
                "naru.log"
        ));
        path.mkParentDirs().writeString(msg.toFullString() + "\n", NPathOption.APPEND, NPathOption.CREATE);
    }

    public static String abbreviate(String s, int max) {
        if (s == null) return "(null)";
        s = s.replace('\n', ' ');
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public static void logWebRequest(NWebRequest request, NMsg text, Object input) {
        NTextBuilder sb = NTextBuilder.of();
        sb.append(NMsg.ofStyledPath(request.effectiveUri()));
        sb.append(" : ");
        sb.append(text);
        sb.append(" : ");
        if (input != null) {
            sb.append("\n REQUEST : ");
            sb.append("\n").append(NElementWriter.ofJson().formatPlain(input));
        }
        log(NMsg.ofC("%s", sb.build()));
    }

    public static void logWebResponse(NWebRequest request, NMsg text, Object input, Object output, NChronometer chronometer) {
        NTextBuilder sb = NTextBuilder.of();
        sb.append(NMsg.ofStyledPath(request.effectiveUri()));
        sb.append(" : ");
        sb.append(text);
        sb.append(" : ");
        if (input != null) {
            sb.append("\n REQUEST : ");
            sb.append("\n").append(NElementWriter.ofJson().formatPlain(input));
        }
        if (output != null) {
            sb.append("\n RESPONSE : ");
            sb.append("\n").append(NElementWriter.ofJson().formatPlain(output));
        }
        log(NMsg.ofC("%s", sb.build()).withDuration(chronometer.duration()));
    }

    public static void showItems(List<NText> items, Set<Integer> toShow, NaruSession session) {
        int lastKey = items.size() + 1;
        if (toShow.isEmpty()) {
            if (!items.isEmpty()) {
                for (int i = 0; i < items.size(); i++) {
                    NText e = items.get(i);
                    showItem(i + 1, lastKey, e, session);
                }
            }
        } else {
            List<Integer> bb = toShow.stream().sorted().collect(Collectors.toList());
            for (int k = 0; k < bb.size(); k++) {
                int i = bb.get(k);
                int im1 = i - 1;
                if (im1 >= 0 && im1 < items.size()) {
                    showItem(i, lastKey, items.get(im1), session);
                }
            }
        }
    }


    private static void showItem(int rowIndex, int max, NText item, NaruSession session) {
        int zeros = (int) Math.ceil(Math.log10(max));
        if (zeros <= 0) {
            zeros = 1;
        }
        DecimalFormat zformat = new DecimalFormat(NStringUtils.repeat("0", zeros));

        List<NText> lines = item.splitLines();
        for (int j = 0; j < lines.size(); j++) {
            NText line = lines.get(j);
            if (j == 0) {
                session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s",
                        NMsg.ofStyledNumber(zformat.format(rowIndex)),
                        line));
            } else {
                session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("        %s", line));
            }
        }
        if (lines.isEmpty()) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s",
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

    public static void showItemsWithFormat(String context2, String format, List<LineRange> ranges, NaruSession session) {
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
        NaruUtils.showItems(linesOk, toShow, session);
    }

    public static String timeAgo(Instant instant) {
        if (instant == null) return null;
        long seconds = Duration.between(instant, Instant.now()).getSeconds();

        if (seconds < 0) return "in the future";
        if (seconds < 5) return "just now";
        if (seconds < 60) return seconds + " seconds ago";

        long minutes = seconds / 60;
        if (minutes < 60) return minutes + (minutes == 1 ? " minute ago" : " minutes ago");

        long hours = minutes / 60;
        if (hours < 24) return hours + (hours == 1 ? " hour ago" : " hours ago");

        long days = hours / 24;
        if (days < 7) return days + (days == 1 ? " day ago" : " days ago");

        long weeks = days / 7;
        if (weeks < 4) return weeks + (weeks == 1 ? " week ago" : " weeks ago");

        long months = days / 30;
        if (months < 12) return months + (months == 1 ? " month ago" : " months ago");

        long years = days / 365;
        return years + (years == 1 ? " year ago" : " years ago");
    }
}
