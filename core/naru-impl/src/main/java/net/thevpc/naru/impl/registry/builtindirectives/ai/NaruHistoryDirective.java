package net.thevpc.naru.impl.registry.builtindirectives.ai;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.util.NStringUtils;
import org.w3c.dom.Text;

import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class NaruHistoryDirective extends AbstractDirective {
    public NaruHistoryDirective() {
        super("history", "ai", "print or manipulate history");
        noCommand("list");
        register(new AbstractSubCommand("list", NText.ofPlain("list history"),
                new SubCommandHelp("[<n1>-<n2>]", "list history (user only) selected lines, or all")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                executeList(context, false, cmdLine);
            }
        });
        register(new AbstractSubCommand("all", NText.ofPlain("list all context history"),
                new SubCommandHelp("[<n1>-<n2>]", "list all context history (user only) selected lines, or all")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                executeList(context, true, cmdLine);
            }
        });
        register(new AbstractSubCommand("delete", NText.ofPlain("delete history lines"),
                new SubCommandHelp("[<n1>-<n2>]", "delete all context history selected lines")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                executeDrop(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("clear", NText.ofPlain("delete all history lines")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                executeClear(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("trim", NText.ofPlain("trim history lines"),
                new SubCommandHelp("<count>", "trim <count> lines")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                executeTrim(context, cmdLine);
            }
        });
    }

    public void executeList(NaruDirectiveCallContext context, boolean includeAll, NCmdLine cmdLine) {
        NaruTask task = context.task();
        List<NaruMessage> all = context.task().context(includeAll ? NaruSource.values() : new NaruSource[]{NaruSource.USER}).messages();
        Set<Integer> toShow = new HashSet<>();
        int historySize = all.size();
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
                        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid position to drop", range).asError());
                        return;
                    }
                }
            }
        }
        if (toShow.isEmpty()) {
            for (int i = 0; i < all.size(); i++) {
                NaruMessage a = all.get(i);
                logRow(i + 1, all.size(), a, task);
            }
        } else {
            List<Integer> bb = toShow.stream().sorted().collect(Collectors.toList());
            for (int k = 0; k < bb.size(); k++) {
                int i = bb.get(k);
                NaruMessage a = all.get(i);
                logRow(i + 1, all.size(), a, task);
            }
        }
    }

    private void logRow(int rowIndex, int max, NaruMessage a, NaruTask task) {
        int zeros = (int) Math.ceil(Math.log10(max));
        if (zeros <= 0) {
            zeros = 1;
        }
        DecimalFormat zformat = new DecimalFormat(NStringUtils.repeat("0", zeros));

        List<String> lines = NStringUtils.splitLines(a.getContent());
        for (int j = 0; j < lines.size(); j++) {
            String line = lines.get(j);
            if (j == 0) {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s %-9s%s %s",
                        NMsg.ofStyledNumber(zformat.format(rowIndex)),
                        NMsg.ofStyled(agentIcon(a.getRole()), agentStyle(a.getRole())),
                        NMsg.ofStyled(a.getRole().name(), agentStyle(a.getRole())),
                        NMsg.ofStyled(">", agentStyle(a.getRole()))
                        , line));
            } else {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("        %s", line));
            }
        }
        List<NaruToolCall> toolCalls = a.getToolCalls();
        if (toolCalls != null) {
            for (NaruToolCall toolCall : toolCalls) {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s %-9s%s [require] %s",
                        NMsg.ofStyledNumber(zformat.format(rowIndex)),
                        NMsg.ofStyled(agentIcon(a.getRole()), agentStyle(a.getRole())),
                        NMsg.ofStyled(a.getRole().name(), agentStyle(a.getRole())),
                        NMsg.ofStyled(">", agentStyle(a.getRole()))
                        , toolCall.toString()));
            }
        }
    }

    private NTextStyle agentStyle(NaruRole a) {
        switch (a) {
            case assistant:
                return NTextStyle.primary2();
            case user:
                return NTextStyle.primary3();
            case system:
                return NTextStyle.primary4();
            case tool:
                return NTextStyle.primary5();
        }
        return null;
    }

    private String agentIcon(NaruRole a) {
        switch (a) {
            case assistant:
                return "\uD83E\uDDE0";
            case user:
                return "\uD83D\uDE4D";
            case system:
                return "\uD83D\uDE80";
            case tool:
                return "🔧";
        }
        return null;
    }

    public void executeClear(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        int count = task.clearHistory();
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("removed %s history messages", count));
    }

    public void executeDrop(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        if (cmdLine.isEmpty()) {
            return;
        }
        NaruTask task = context.task();
        List<NaruUtils.LineRange> ranges = NaruUtils.parseRanges(cmdLine);
        List<NaruMessage> messages = task.context(NaruSource.USER).messages();
        int historySize = messages.size();
        Set<Integer> toRemove = NaruUtils.resolveIndexes(ranges.toArray(new NaruUtils.LineRange[0]), historySize);

        List<Integer> a = toRemove.stream().sorted(Comparator.<Integer>naturalOrder().reversed()).collect(Collectors.toList());
        List<Integer> b = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            if (context.task().removeHistoryAt(a.get(i))) {
                b.add(a.get(i));
            }
        }
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("removed %s history messages", b.size()));

        // TODO: history drop is not pair-aware — dropping a tool_call or tool_result
        // message individually will produce a malformed conversation history.
        // Fix: group assistant+tool_call+tool_results by tool call id before removal.
    }


    public void executeTrim(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        while (!cmdLine.isEmpty()) {
            String a = cmdLine.next().get().image();
            if (a.matches("[0-9]+")) {
                int deleted = task.trimHistory(Integer.parseInt(a));
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("removed %s history messages", deleted));
                return;
            } else {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid trim").asError());
                return;
            }
        }
        int deleted = task.trimHistory(1024); // default trim
        task.log(NaruLogMode.RAW, NMsg.ofC("removed %s history messages", deleted));
    }
}
