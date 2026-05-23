package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.budget.NaruModelStats;
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
        super("stat","general", "show and manage stats");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession sessionContext = context.session();
        NCmdLine cmdLine = NCmdLine.parse(context.argument()).get();
        if (cmdLine.isEmpty()) {
            executeList(context, cmdLine);
        }
    }

    public void executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
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
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", modelStat.getModel().toMsg()).asError());
            double userPercent = modelStat.getContextUsage() * 1.0 / modelStat.getContextSize();
            double peakPercent = modelStat.getPeakContextUsage() * 1.0 / modelStat.getContextSize();
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s  | %s %s, %s %s, %s %s",
                            NMsg.ofStyledPrimary1("context"),
                            "used",
                            NMsg.ofStyledNumber(new DecimalFormat("0.00%").format(userPercent)),
                            "peak",
                            NMsg.ofStyledNumber(new DecimalFormat("0.00%").format(peakPercent)),
                            "available",
                            NaruUtils.formattedTokensSize(modelStat.getContextSize())
                    )
            );
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s    | %s",
                    NMsg.ofStyledPrimary1("calls"),
                    modelStat.getCallsCount())
            );
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s | min %s, avg %s, max %s",
                            NMsg.ofStyledPrimary1("duration"),
                            modelStat.getMinDuration(),
                            modelStat.getAvgDuration(),
                            modelStat.getMaxDuration()
                    )
            );
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s   | %s, prompt %s, eval %s",
                            NMsg.ofStyledPrimary1("tokens"),
                            modelStat.getTotalTokens(),
                            modelStat.getPromptTokens(),
                            modelStat.getCompletionTokens()
                    )
            );
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s   | %s",
                            NMsg.ofStyledPrimary1("budget"),
                            modelStat.getTotalTokensBudget()
                    )
            );

        }
    }


}
