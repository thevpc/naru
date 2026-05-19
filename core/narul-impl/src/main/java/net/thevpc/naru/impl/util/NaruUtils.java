package net.thevpc.naru.impl.util;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTextBuilder;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.util.NStringUtils;

import java.text.DecimalFormat;
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

    public static void showItems(List<NText> items, Set<Integer> toShow, NaruSession sessionContext) {
        int lastKey = items.size() + 1;
        if (toShow.isEmpty()) {
            if (!items.isEmpty()) {
                for (int i = 0; i < items.size(); i++) {
                    NText e = items.get(i);
                    sowItem(i + 1, lastKey, e, sessionContext);
                }
            }
        } else {
            List<Integer> bb = toShow.stream().sorted().collect(Collectors.toList());
            for (int k = 0; k < bb.size(); k++) {
                int i = bb.get(k);
                if (i >= 0 && i < items.size()) {
                    sowItem(i, lastKey, items.get(i), sessionContext);
                }
            }
        }
    }


    private static void sowItem(int rowIndex, int max, NText item, NaruSession sessionContext) {
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
        if(lines.isEmpty()){
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s",
                    NMsg.ofStyledNumber(zformat.format(rowIndex)),
                    item));
        }
    }

    public static Set<Integer> parseLineIndicesToShow(int historySize, NCmdLine cmdLine) {
        Set<Integer> toShow = new HashSet<>();
        while (!cmdLine.isEmpty()) {
            String a = cmdLine.next().get().image();
            for (String range : a.split(",;")) {
                range = range.trim();
                if (!range.isEmpty()) {
                    if (range.matches("[0-9]+")) {
                        toShow.add(Integer.parseInt(range) - 1);
                    } else if (range.matches("[0-9]+[-][0-9]+")) {
                        String[] ss = range.split("-");
                        int x = Integer.parseInt(ss[0]) - 1;
                        int y = Integer.parseInt(ss[1]);
                        if (x < 0) {
                            x = historySize + x;
                        }
                        if (y < 0) {
                            y = historySize + y + 1;
                        }
                        for (int i = x; i <= y; i++) {
                            toShow.add(i);
                        }
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
}
