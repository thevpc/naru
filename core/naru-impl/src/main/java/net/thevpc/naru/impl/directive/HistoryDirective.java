package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.util.NStringUtils;

import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class HistoryDirective extends AbstractDirective {
    public HistoryDirective() {
        super("history","context", "print or manipulate history");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession sessionContext = context.session();
        NCmdLine cmdLine = NCmdLine.parse(context.argument()).get();
        if (cmdLine.isEmpty()) {
            executeList(context, false, cmdLine);
        } else {
            NArg a = cmdLine.next().get();
            switch (a.image()) {
                case "list": {
                    executeList(context, false, cmdLine);
                    break;
                }
                case "all": {
                    executeList(context, true, cmdLine);
                    break;
                }
                case "drop": {
                    executeDrop(context, cmdLine);
                    break;
                }
                case "clear": {
                    executeClear(context, cmdLine);
                    break;
                }
                case "trim": {
                    executeTrim(context, cmdLine);
                    break;
                }
                case "--help":
                case "help": {
                    executeHelp(context, cmdLine);
                    break;
                }
                default: {
                    sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command /%s %s", name(), context.argument()));
                }
            }
        }
    }

    public void executeList(NaruDirectiveCallContext context, boolean includeAll, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        List<NaruMessage> all = context.session().history(includeAll);
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
                        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid position to drop", range).asError());
                        return;
                    }
                }
            }
        }
        if (toShow.isEmpty()) {
            for (int i = 0; i < all.size(); i++) {
                NaruMessage a = all.get(i);
                logRow(i + 1, all.size(), a, sessionContext);
            }
        } else {
            List<Integer> bb = toShow.stream().sorted().collect(Collectors.toList());
            for (int k = 0; k < bb.size(); k++) {
                int i = bb.get(k);
                NaruMessage a = all.get(i);
                logRow(i + 1, all.size(), a, sessionContext);
            }
        }
    }

    private void logRow(int rowIndex, int max, NaruMessage a, NaruSession sessionContext) {
        int zeros = (int) Math.ceil(Math.log10(max));
        if (zeros <= 0) {
            zeros = 1;
        }
        DecimalFormat zformat = new DecimalFormat(NStringUtils.repeat("0", zeros));

        List<String> lines = NStringUtils.splitLines(a.getContent());
        for (int j = 0; j < lines.size(); j++) {
            String line = lines.get(j);
            if (j == 0) {
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s %-9s%s %s",
                        NMsg.ofStyledNumber(zformat.format(rowIndex)),
                        NMsg.ofStyled(agentIcon(a.getRole()), agentStyle(a.getRole())),
                        NMsg.ofStyled(a.getRole().name(), agentStyle(a.getRole())),
                        NMsg.ofStyled(">", agentStyle(a.getRole()))
                        , line));
            } else {
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("        %s", line));
            }
        }
        List<NaruToolCall> toolCalls = a.getToolCalls();
        if (toolCalls != null) {
            for (NaruToolCall toolCall : toolCalls) {
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s %-9s%s [require] %s",
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
        NaruSession sessionContext = context.session();
        int count = sessionContext.clearHistory();
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("removed %s history messages", count));
    }

    public void executeDrop(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        if (cmdLine.isEmpty()) {
            return;
        }
        NaruSession sessionContext = context.session();
        Set<Integer> toRemove = new HashSet<>();
        int historySize = sessionContext.history().size();
        while (!cmdLine.isEmpty()) {
            String a = cmdLine.next().get().image();
            for (String range : a.split(",;")) {
                range = range.trim();
                if (!range.isEmpty()) {
                    if (range.matches("[0-9]+")) {
                        toRemove.add(Integer.parseInt(range) - 1);
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
                            toRemove.add(i);
                        }
                    } else {
                        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid position to drop", range).asError());
                        return;
                    }
                }
            }
        }
        List<Integer> a = toRemove.stream().sorted(Comparator.<Integer>naturalOrder().reversed()).collect(Collectors.toList());
        List<Integer> b = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            if (context.session().removeHistoryAt(a.get(i))) {
                b.add(a.get(i));
            }
        }
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("removed %s history messages", b.size()));

        // TODO: history drop is not pair-aware — dropping a tool_call or tool_result
        // message individually will produce a malformed conversation history.
        // Fix: group assistant+tool_call+tool_results by tool call id before removal.
    }

    public void executeHelp(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        NMsg kk = NMsg.ofC("%s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1("history"));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, kk);
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s list", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s list <n1>-<p1>,<n2>-<p2>", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           list intervals"));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s trim", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           remove empty items intervals"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s drop <n1>-<p1>,<n2>-<p2>", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           drop intervals"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s clear", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           clear history"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s all", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show all history (including agents, skills, tool calls and results)"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s help", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show this help"));
    }

    public void executeTrim(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        while (!cmdLine.isEmpty()) {
            String a = cmdLine.next().get().image();
            if (a.matches("[0-9]+")) {
                int deleted = sessionContext.trimHistory(Integer.parseInt(a));
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("removed %s history messages", deleted));
                return;
            } else {
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid trim").asError());
                return;
            }
        }
        int deleted = sessionContext.trimHistory(1024); // default trim
        sessionContext.log(NaruLogMode.RAW, NMsg.ofC("removed %s history messages", deleted));
    }
}
