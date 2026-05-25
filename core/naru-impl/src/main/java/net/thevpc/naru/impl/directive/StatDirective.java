package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.budget.NaruModelStats;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class StatDirective extends AbstractDirective {
    public StatDirective() {
        super("stat", "general", "show and manage stats", "stats");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NCmdLine cmdLine = NCmdLine.parse(context.argument()).get();
        if (cmdLine.isEmpty()) {
            executeList(context, cmdLine);
        }
    }

    public void executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        List<NaruModelStats> modelStats = context.session().meteringService().findModelStats(context.session())
                .stream()
                .filter(a -> a.getCallsCount() > 0)
                .sorted(Comparator
                        .<NaruModelStats, BigDecimal>comparing(a -> a.getTotalTokensBudget()).reversed()
                        .thenComparing(a -> a.getModel().provider())
                        .thenComparing(a -> a.getModel().model())
                )
                .collect(Collectors.toList());
        for (NaruModelStats modelStat : modelStats) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", modelStat.getModel().toMsg()).asError());
            double userPercent = modelStat.getContextUsage() * 1.0 / modelStat.getContextSize();
            double peakPercent = modelStat.getPeakContextUsage() * 1.0 / modelStat.getContextSize();
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s  | %s %s, %s %s, %s %s",
                            NMsg.ofStyledPrimary1("context"),
                            "used",
                            NMsg.ofStyledNumber(new DecimalFormat("0.00%").format(userPercent)),
                            "peak",
                            NMsg.ofStyledNumber(new DecimalFormat("0.00%").format(peakPercent)),
                            "available",
                            NaruUtils.formattedTokensSize(modelStat.getContextSize())
                    )
            );
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s    | %s",
                    NMsg.ofStyledPrimary1("calls"),
                    modelStat.getCallsCount())
            );
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s | min %s, avg %s, max %s",
                            NMsg.ofStyledPrimary1("duration"),
                            modelStat.getMinDuration(),
                            modelStat.getAvgDuration(),
                            modelStat.getMaxDuration()
                    )
            );
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s   | %s, prompt %s, eval %s",
                            NMsg.ofStyledPrimary1("tokens"),
                            modelStat.getTotalTokens(),
                            modelStat.getPromptTokens(),
                            modelStat.getCompletionTokens()
                    )
            );
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s   | %s",
                            NMsg.ofStyledPrimary1("budget"),
                            modelStat.getTotalTokensBudget()
                    )
            );

        }
        PromptStats promptStats = estimateTokens(session);
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  tools : %s | messages : %s",
                        promptStats.tools,
                        promptStats.messages
                )
        );
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  tokens : %s | system : %s | user : %s | tools : %s | assistant : %s | agent : %s",
                promptStats.tokens,
                NMsg.ofStyledNumber(percent(promptStats.systemTokens, promptStats.tokens)),
                NMsg.ofStyledNumber(percent(promptStats.userTokens, promptStats.tokens)),
                NMsg.ofStyledNumber(percent(promptStats.toolsTokens, promptStats.tokens)),
                NMsg.ofStyledNumber(percent(promptStats.assistantTokens, promptStats.tokens)),
                NMsg.ofStyledNumber(percent(promptStats.agentTokens, promptStats.tokens))
                )
        );
    }

    private String percent(long q, long max) {
        if (q == 0 || max == 0) {
            return "0.00%";
        }
        return new DecimalFormat("#.###").format(100.0 * q / (double) max);
    }

    private static class PromptStats {
        long toolsTokens;
        long systemTokens;
        long assistantTokens;
        long userTokens;
        long agentTokens;
        long tokens;
        long tools;
        long messages;
    }

    private PromptStats estimateTokens(NaruSession session) {
        PromptStats s = new PromptStats();
        NaruModelRequest r = session.context(NaruSource.values());
        s.tools = r.tools().size();
        NaruModelRequest estimatedMessage = new NaruModelRequest(
                r.messages(),
                r.tools(), new LinkedHashMap<>());
        s.messages = estimatedMessage.messages().size();
        for (NaruMessage msg : estimatedMessage.messages()) {
            if (msg.getContent() != null) {
                int c = msg.getContent().length();
                s.tokens += c;
                switch (msg.getRole()) {
                    case tool: {
                        s.toolsTokens += c;
                        break;
                    }
                    case system: {
                        s.systemTokens += c;
                        break;
                    }
                    case assistant: {
                        s.assistantTokens += c;
                        break;
                    }
                    case user: {
                        switch (msg.getSource()) {
                            case USER: {
                                s.userTokens += c;
                                break;
                            }
                            default: {
                                s.agentTokens += c;
                                break;
                            }
                        }
                        break;
                    }
                }
            }
        }
        for (NaruToolDefinition tool : r.tools()) {
            if (tool.getName() != null) {
                s.toolsTokens += tool.getName().length();
                s.tokens += tool.getName().length();
            }
            if (tool.getDescription() != null) {
                s.toolsTokens += tool.getDescription().length();
                s.tokens += tool.getDescription().length();
            }
        }
        s.tokens = s.tokens / 4;
        s.systemTokens = s.systemTokens / 4;
        s.toolsTokens = s.toolsTokens / 4;
        s.userTokens = s.userTokens / 4;
        s.assistantTokens = s.assistantTokens / 4;
        s.agentTokens = s.agentTokens / 4;
        return s;
    }


}
