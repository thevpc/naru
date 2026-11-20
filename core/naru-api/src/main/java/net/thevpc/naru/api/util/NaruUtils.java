package net.thevpc.naru.api.util;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NListContainerElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NPairElement;
import net.thevpc.nuts.elem.NElementType;
import net.thevpc.nuts.text.*;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NStringUtils;

import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class NaruUtils {

    public static void showItemsWithFormat(String context2, String format, List<NaruUtils.LineRange> ranges, NaruTask task,Consumer<NMsg> textBuilder) {
        NText nText;
        switch (format) {
            case "markdown": {
                nText = NaruTerminalFormatter.formatMarkdown(context2, null);
                break;
            }
            default: {
                nText = NText.ofPlain(context2);
                break;
            }
        }
        List<NText> linesOk = nText.splitLines();
        Set<Integer> toShow = net.thevpc.naru.api.util.NaruUtils.resolveIndexes(ranges.toArray(new net.thevpc.naru.api.util.NaruUtils.LineRange[0]), linesOk.size());
        showItems(linesOk, toShow, task,textBuilder);
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


    public static void showItems(List<NText> items, Set<Integer> toShow, NaruTask task,Consumer<NMsg> textBuilder) {
        int lastKey = items.size() + 1;
        if (toShow.isEmpty()) {
            if (!items.isEmpty()) {
                for (int i = 0; i < items.size(); i++) {
                    NText e = items.get(i);
                    showItem(i + 1, lastKey, e, task,textBuilder);
                }
            }
        } else {
            List<Integer> bb = toShow.stream().sorted().collect(Collectors.toList());
            for (int k = 0; k < bb.size(); k++) {
                int i = bb.get(k);
                int im1 = i - 1;
                if (im1 >= 0 && im1 < items.size()) {
                    showItem(i, lastKey, items.get(im1), task,textBuilder);
                }
            }
        }
    }


    private static void showItem(int rowIndex, int max, NText item, NaruTask task, Consumer<NMsg> textBuilder) {
        int zeros = (int) Math.ceil(Math.log10(max));
        if (zeros <= 0) {
            zeros = 1;
        }
        DecimalFormat zformat = new DecimalFormat(NStringUtils.repeat("0", zeros));

        List<NText> lines = item.splitLines();
        for (int j = 0; j < lines.size(); j++) {
            NText line = lines.get(j);
            if (j == 0) {
                textBuilder.accept(NMsg.ofC("%s %s",
                        NMsg.ofStyledNumber(zformat.format(rowIndex)),
                        line));
            } else {
                textBuilder.accept(NMsg.ofC("        %s", line));
            }
        }
        if (lines.isEmpty()) {
            textBuilder.accept(NMsg.ofC("%s %s",
                    NMsg.ofStyledNumber(zformat.format(rowIndex)),
                    item));
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

    public static String snippet(String content) {
        if (NBlankable.isBlank(content)) {
            return "";
        }
        String cc = content.substring(0, Math.min(content.length(), 40));
        return cc.replace("\r\n", " ").replace("\n", " ");
    }

    public static NText snippet(NMsg content) {
        return snippet(NText.of(content));
    }

    public static NText snippet(NText content) {
        if (NBlankable.isBlank(content)) {
            return NText.ofBlank();
        }
        NText cc = content.substring(0, Math.min(content.length(), 40));
        NTextBuilder sb=NTextBuilder.of();
        for (NPrimitiveText a : cc.toCharArray()) {
            if(a.isNewLine()){
                sb.append(" ");
            }else{
                sb.append(a);
            }
        }
        return sb.build();
    }

    /**
     * The Java value an element was built from: scalars become String/Number/Boolean/null
     * and containers become Map/List, walked all the way down.
     *
     * <p>Values that crossed into {@code NElement}-typed slots are elements even when they
     * started as plain strings, so code that dispatches on {@code instanceof Map}, or that
     * puts a leaf into a JSON wire payload, must undo the wrap first.
     * {@link net.thevpc.nuts.elem.NElement#simpleOf} does not: it converts Java to element
     * and returns an element untouched, which leaves the element in front of value-shaped
     * code. A non-element passes through unchanged so nested Java values recurse cleanly.
     */
    public static Object plainValue(Object value) {
        if (!(value instanceof NElement element)) {
            return value;
        }
        NElementType type = element.type();
        if (type == NElementType.NULL) {
            return null;
        }
        if (type.isAnyString()) {
            return element.asStringValue().orNull();
        }
        if (type.isAnyNumber()) {
            return element.asNumberValue().orNull();
        }
        if (type == NElementType.BOOLEAN) {
            return element.asBooleanValue().orNull();
        }
        NObjectElement object = element.asObject().orNull();
        if (object != null) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (NElement pair : object.stream().toList()) {
                if (pair instanceof NPairElement p) {
                    map.put(p.key().asStringValue().orNull(), plainValue(p.value()));
                }
            }
            return map;
        }
        NListContainerElement list = element.asListContainer().orNull();
        if (list != null) {
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                values.add(plainValue(list.get(i).orNull()));
            }
            return values;
        }
        return element;
    }

    /**
     * The fields of an object element, kept as elements, in declaration order.
     *
     * <p>The counterpart of {@link #plainValue}: a JSON arguments object read off a model
     * response has to land in a {@code Map<String, NElement>} slot, and
     * {@code element.convertTo(Map.class)} is not it — that produces a map of Java values
     * and only fits because an unchecked cast erases the difference. Anything downstream
     * that then reads a value as an element gets a ClassCastException, or worse, a String
     * where every other producer stores an element.
     */
    public static Map<String, NElement> fields(NElement element) {
        Map<String, NElement> map = new LinkedHashMap<>();
        NObjectElement object = element == null ? null : element.asObject().orNull();
        if (object != null) {
            for (NElement pair : object.stream().toList()) {
                if (pair instanceof NPairElement p) {
                    map.put(p.key().asStringValue().orNull(), p.value());
                }
            }
        }
        return map;
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
