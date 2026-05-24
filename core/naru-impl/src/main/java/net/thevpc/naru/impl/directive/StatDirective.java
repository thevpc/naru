package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.budget.NaruModelStats;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.api.tool.NaruTool;
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
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  tokens : %s | tools : %s | messages : %s",
                        promptStats.tokens,
                        promptStats.tools,
                        promptStats.messages
                )
        );
    }

    private static class PromptStats {
        int tokens;
        int tools;
        int messages;
    }

    private PromptStats estimateTokens(NaruSession session) {
        PromptStats s = new PromptStats();
        List<NaruToolDefinition> defs = new ArrayList<>();
        s.tools = session.registry().tools().size();
        for (NaruTool t : session.registry().tools().values()) {
            defs.add(t.getDefinition(session));
        }
        NaruModelRequest estimatedMessage = new NaruModelRequest(session.history(true), defs);
        int chars = 0;
        s.messages = estimatedMessage.getMessages().size();
        for (NaruMessage msg : estimatedMessage.getMessages()) {
            if (msg.getContent() != null) {
                chars += msg.getContent().length();
            }
        }
        for (NaruTool t : session.registry().tools().values()) {
            NaruToolDefinition tool = t.getDefinition(session);
            if (tool.getName() != null) chars += tool.getName().length();
            if (tool.getDescription() != null) chars += tool.getDescription().length();
        }
        s.tokens = chars / 4;
        return s;
    }


}
