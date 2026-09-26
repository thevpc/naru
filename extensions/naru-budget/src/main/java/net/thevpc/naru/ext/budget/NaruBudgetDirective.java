package net.thevpc.naru.ext.budget;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.util.NaruUtils;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NStringBuilder;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class NaruBudgetDirective extends NaruDirectiveBase {
    public NaruBudgetDirective() {
        super("stat", "ai", "show and manage stats", "stats");
        register(new AbstractSubCommand() {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeList(context, cmdLine);
            }
        });
    }


    public NaruStmtResult executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        List<NaruModelStats> modelStats = NaruBudgetExtension.metering(context.task().session()).findModelStats()
                .stream()
                .filter(a -> a.getCallsCount() > 0)
                .sorted(Comparator
                        .<NaruModelStats, BigDecimal>comparing(a -> a.getTotalTokensBudget()).reversed()
                        .thenComparing(a -> a.getModel().provider())
                        .thenComparing(a -> a.getModel().model())
                )
                .collect(Collectors.toList());

        NStringBuilder sb = NStringBuilder.of();
        for (NaruProviderRateLimitInfo s : NaruBudgetExtension.metering(context.task().session()).findProviderRateLimitInfos()) {
            NMsg msg = NMsg.ofC("%s (%s)%s%s",
                            NMsg.ofStyledPrimary9(s.providerName()),
                            s.serverTime(),
                            s.retryAfter().isPresent() ? ", retry after  : " : "",
                            s.retryAfter().orNull()
                    )
            ;
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            sb.println(msg.toString());
            for (NaruRateLimitBucket requestBucket : s.requestBuckets()) {
                NMsg bmsg = NMsg.ofC("      request limits / %s : %s %s, %s %s, %s %s, %s %s",
                                NMsg.ofStyledPrimary2(requestBucket.getWindow().name().toLowerCase()),
                                "limit",
                                requestBucket.getLimit().orNull(),
                                "used",
                                (requestBucket.getLimit().orNull() != null && requestBucket.getRemaining().orNull() != null) ? (requestBucket.getLimit().orNull() - requestBucket.getRemaining().orNull()) : null,
                                "remaining",
                                requestBucket.getRemaining().orNull(),
                                requestBucket.getResetTime().orNull() == null ? "" : "resetTime",
                                requestBucket.getResetTime().orNull()
                        )
                ;
                task.log(NaruLogMode.AGENT_RESPONSE, bmsg);
                sb.println(bmsg.toString());
            }
            for (NaruRateLimitBucket requestBucket : s.tokenBuckets()) {
                NMsg tmsg = NMsg.ofC("      token   limits / %s : %s %s, %s %s, %s %s, %s %s",
                                NMsg.ofStyledPrimary2(requestBucket.getWindow().name().toLowerCase()),
                                "limit",
                                requestBucket.getLimit().orNull(),
                                "used",
                                (requestBucket.getLimit().orNull() != null && requestBucket.getRemaining().orNull() != null) ? (requestBucket.getLimit().orNull() - requestBucket.getRemaining().orNull()) : null,
                                "remaining",
                                requestBucket.getRemaining().orNull(),
                                requestBucket.getResetTime().orNull() == null ? "" : "resetTime",
                                requestBucket.getResetTime().orNull()
                        )
                ;
                task.log(NaruLogMode.AGENT_RESPONSE, tmsg);
                sb.println(tmsg.toString());
            }
        }

        for (NaruModelStats modelStat : modelStats) {
            NMsg msg = NMsg.ofC("%s", modelStat.getModel().toMsg()).asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            sb.println(msg.toString());
            double userPercent = modelStat.getContextUsage() * 1.0 / modelStat.getContextSize();
            double peakPercent = modelStat.getPeakContextUsage() * 1.0 / modelStat.getContextSize();
            NMsg ctxMsg = NMsg.ofC("  %s  | %s %s, %s %s, %s %s",
                            NMsg.ofStyledPrimary1("context"),
                            "used",
                            NMsg.ofStyledNumber(new DecimalFormat("0.00%").format(userPercent)),
                            "peak",
                            NMsg.ofStyledNumber(new DecimalFormat("0.00%").format(peakPercent)),
                            "available",
                            NaruUtils.formattedTokensSize(modelStat.getContextSize())
                    )
            ;
            task.log(NaruLogMode.AGENT_RESPONSE, ctxMsg);
            sb.println(ctxMsg.toString());
            NMsg callsMsg = NMsg.ofC("  %s    | %s",
                    NMsg.ofStyledPrimary1("calls"),
                    modelStat.getCallsCount()
            );
            task.log(NaruLogMode.AGENT_RESPONSE, callsMsg);
            sb.println(callsMsg.toString());
            NMsg durMsg = NMsg.ofC("  %s | min %s, avg %s, max %s",
                            NMsg.ofStyledPrimary1("duration"),
                            modelStat.getMinDuration(),
                            modelStat.getAvgDuration(),
                            modelStat.getMaxDuration()
                    )
            ;
            task.log(NaruLogMode.AGENT_RESPONSE, durMsg);
            sb.println(durMsg.toString());
            NMsg tokMsg = NMsg.ofC("  %s   | %s, prompt %s, eval %s",
                            NMsg.ofStyledPrimary1("tokens"),
                            modelStat.getTotalTokens(),
                            modelStat.getPromptTokens(),
                            modelStat.getCompletionTokens()
                    )
            ;
            task.log(NaruLogMode.AGENT_RESPONSE, tokMsg);
            sb.println(tokMsg.toString());
            NMsg budgMsg = NMsg.ofC("  %s   | %s",
                            NMsg.ofStyledPrimary1("budget"),
                            modelStat.getTotalTokensBudget()
                    )
            ;
            task.log(NaruLogMode.AGENT_RESPONSE, budgMsg);
            sb.println(budgMsg.toString());

        }
        PromptStats promptStats = estimateTokens(task);
        NMsg toolsMsg = NMsg.ofC("  tools : %s | messages : %s",
                        promptStats.tools,
                        promptStats.messages
                )
        ;
        task.log(NaruLogMode.AGENT_RESPONSE, toolsMsg);
        sb.println(toolsMsg.toString());
        NMsg tokensMsg = NMsg.ofC("  tokens : %s | system : %s | user : %s | tools : %s | assistant : %s | agent : %s",
                        promptStats.tokens,
                        NMsg.ofStyledNumber(percent(promptStats.systemTokens, promptStats.tokens)),
                        NMsg.ofStyledNumber(percent(promptStats.userTokens, promptStats.tokens)),
                        NMsg.ofStyledNumber(percent(promptStats.toolsTokens, promptStats.tokens)),
                        NMsg.ofStyledNumber(percent(promptStats.assistantTokens, promptStats.tokens)),
                        NMsg.ofStyledNumber(percent(promptStats.agentTokens, promptStats.tokens))
                )
        ;
        task.log(NaruLogMode.AGENT_RESPONSE, tokensMsg);
        sb.println(tokensMsg.toString());
        return NaruStmtResult.ofSuccess(sb.toString());
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

    private PromptStats estimateTokens(NaruTask session) {
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