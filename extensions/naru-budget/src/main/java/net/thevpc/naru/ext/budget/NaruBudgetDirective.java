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
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NStringBuilder;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class NaruBudgetDirective extends NaruDirectiveBase {
    public NaruBudgetDirective() {
        super("budget", "ai", "show and manage budget");
        register(new AbstractSubCommand() {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeList(context, cmdLine);
            }
        });
    }


    public NaruStmtResult executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        List<NaruModelBudgetStats> modelStats = NaruBudgetExtension.budget(context.task().session()).findModelBudgetStats()
                .stream()
                .filter(a -> a.getCallsCount() > 0)
                .sorted(Comparator
                        .<NaruModelBudgetStats, BigDecimal>comparing(a -> a.getTotalTokensBudget()).reversed()
                        .thenComparing(a -> a.getModel().provider())
                        .thenComparing(a -> a.getModel().model())
                )
                .collect(Collectors.toList());

        NStringBuilder sb = NStringBuilder.of();
        for (NaruProviderRateLimitInfo s : NaruBudgetExtension.budget(context.task().session()).findProviderRateLimitInfos()) {
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
                NMsg bmsg = bucketMsg("request", requestBucket);
                if (bmsg == null) {
                    continue;
                }
                task.log(NaruLogMode.AGENT_RESPONSE, bmsg);
                sb.println(bmsg.toString());
            }
            for (NaruRateLimitBucket tokenBucket : s.tokenBuckets()) {
                NMsg tmsg = bucketMsg("token  ", tokenBucket);
                if (tmsg == null) {
                    continue;
                }
                task.log(NaruLogMode.AGENT_RESPONSE, tmsg);
                sb.println(tmsg.toString());
            }
        }

        for (NaruModelBudgetStats modelStat : modelStats) {
            NMsg msg = NMsg.ofC("%s", modelStat.getModel().toMsg()).asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            sb.println(msg.toString());
            NMsg ctxMsg = NMsg.ofC("  %s  | %s %s, %s %s, %s %s",
                            NMsg.ofStyledPrimary1("context"),
                            "used",
                            contextShare(modelStat.getContextUsage(), modelStat.getContextSize()),
                            "peak",
                            contextShare(modelStat.getPeakContextUsage(), modelStat.getContextSize()),
                            "available",
                            modelStat.getContextSize() > 0
                                    ? NaruUtils.formattedTokensSize(modelStat.getContextSize())
                                    : NMsg.ofC("%s", "unknown")
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
            // "budget" next to a spend figure read as an allowance; what this is is the
            // money already spent, unit price times tokens
            NMsg budgMsg = NMsg.ofC("  %s   | %s",
                            NMsg.ofStyledPrimary1("cost"),
                            modelStat.getTotalTokensBudget()
                    )
            ;
            task.log(NaruLogMode.AGENT_RESPONSE, budgMsg);
            sb.println(budgMsg.toString());

        }
        PromptStats promptStats = estimateTokens(task);
        NMsg toolsMsg = NMsg.ofC("  tool defs : %s | messages : %s | tool calls : %s",
                        promptStats.toolDefs,
                        promptStats.messages,
                        promptStats.toolCalls
                )
        ;
        task.log(NaruLogMode.AGENT_RESPONSE, toolsMsg);
        sb.println(toolsMsg.toString());
        NMsg tokensMsg = NMsg.ofC("  estimated tokens : %s | system : %s, user : %s, agent : %s, assistant : %s, tool defs : %s, tool results : %s",
                        promptStats.tokens,
                        share(promptStats.tokens(Bucket.SYSTEM), promptStats.tokens),
                        share(promptStats.tokens(Bucket.USER), promptStats.tokens),
                        share(promptStats.tokens(Bucket.AGENT), promptStats.tokens),
                        share(promptStats.tokens(Bucket.ASSISTANT), promptStats.tokens),
                        share(promptStats.tokens(Bucket.TOOL_DEFS), promptStats.tokens),
                        share(promptStats.tokens(Bucket.TOOL_RESULTS), promptStats.tokens)
                )
        ;
        task.log(NaruLogMode.AGENT_RESPONSE, tokensMsg);
        sb.println(tokensMsg.toString());
        // The estimate is only as good as its calibration, and there is no other way
        // to see that: the provider reported this many prompt tokens for the last call
        // it billed, against the request we would send now.
        NaruModelBudgetStats lastCall = lastCalledModel(modelStats);
        if (lastCall != null && lastCall.getContextUsage() > 0) {
            long reported = lastCall.getContextUsage();
            NMsg calibMsg = NMsg.ofC("  reported last call : %s prompt tokens for %s (estimate / reported : %s)",
                            reported,
                            lastCall.getModel().toMsg(),
                            new DecimalFormat("#.##").format((double) promptStats.tokens / reported)
                    )
            ;
            task.log(NaruLogMode.AGENT_RESPONSE, calibMsg);
            sb.println(calibMsg.toString());
        }
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    /**
     * The share of a bucket as a number plus a literal {@code %}.
     *
     * <p>The sign used to be inside the styled run, so the percent marker took the
     * numeric style; and a zero total reported {@code 0%} for every bucket rather
     * than admitting there is nothing to divide.
     */
    private NMsg share(long part, long total) {
        if (total <= 0) {
            return NMsg.ofC("%s", "n/a");
        }
        return NMsg.ofC("%s%s", NMsg.ofStyledNumber(percent(part, total)), "%");
    }

    private NMsg contextShare(long used, long size) {
        return share(used, size);
    }

    private static NaruModelBudgetStats lastCalledModel(List<NaruModelBudgetStats> modelStats) {
        NaruModelBudgetStats best = null;
        for (NaruModelBudgetStats s : modelStats) {
            if (best == null || s.getContextUsage() > best.getContextUsage()) {
                best = s;
            }
        }
        return best;
    }

    /**
     * A rate-limit bucket with neither a limit nor a remaining count carries no
     * information, and printing it anyway filled the report with
     * {@code limit null, used null, remaining null} lines that read like missing
     * accounting rather than like "this provider told us nothing".
     */
    private NMsg bucketMsg(String kind, NaruRateLimitBucket bucket) {
        Integer limit = bucket.getLimit().orNull();
        Integer remaining = bucket.getRemaining().orNull();
        if (limit == null && remaining == null) {
            return null;
        }
        return NMsg.ofC("      %s limits / %s : %s %s, %s %s, %s %s, %s %s",
                        kind,
                        NMsg.ofStyledPrimary2(bucket.getWindow().name().toLowerCase()),
                        "limit",
                        limit,
                        "used",
                        (limit != null && remaining != null) ? limit - remaining : null,
                        "remaining",
                        remaining,
                        "resetTime",
                        bucket.getResetTime().orNull()
                );
    }

    private String percent(long q, long max) {
        if (max == 0) {
            return "n/a";
        }
        return new DecimalFormat("#.###").format(100.0 * q / (double) max);
    }

    /**
     * Where the characters in an outgoing request are attributed.
     *
     * <p>The split has to be decided here rather than left to the reader: "tools" used
     * to mean two different things at once -- the schemas of the tools being offered
     * (sent on every call) and the results tools handed back (sent once) -- so a
     * context that looked tool-heavy could not be told which half was growing.
     */
    enum Bucket {
        SYSTEM,
        USER,
        AGENT,
        ASSISTANT,
        TOOL_RESULTS,
        TOOL_DEFS
    }

    static final class PromptStats {
        final long[] chars = new long[Bucket.values().length];
        final long[] bucketTokens = new long[Bucket.values().length];

        long toolDefs;
        long messages;
        long tokens;
        long toolResults;
        long toolCalls;

        long chars(Bucket b) {
            return chars[b.ordinal()];
        }

        long tokens(Bucket b) {
            return bucketTokens[b.ordinal()];
        }

        void add(Bucket b, long n) {
            chars[b.ordinal()] += n;
        }
    }

    /**
     * Rough per-message framing the provider charges for on top of the text: the role
     * marker, the turn separators, the structural keys. Ignored before, which made
     * short conversations look far cheaper than they are billed.
     */
    static final int MSG_OVERHEAD_CHARS = 16;

    /**
     * Characters per token. A coarse average over prose and code; the reported
     * figure is what the provider billed, so this exists to be calibrated against,
     * not to replace it.
     */
    static final double CHARS_PER_TOKEN = 4.0;

    /**
     * The character cost of {@code toString()} on the arguments, so a tool call with a
     * large object argument is not priced as if it were empty.
     */
    private static int chars(Object o) {
        return o == null ? 0 : o.toString().length();
    }

    /**
     * Bucketed character estimate of the request {@code context(...)} would produce.
     */
    static PromptStats estimateTokens(NaruTask session) {
        return estimateTokens(session.context(NaruSource.values()));
    }

    static PromptStats estimateTokens(NaruModelRequest r) {
        PromptStats s = new PromptStats();
        s.toolDefs = r.tools() == null ? 0 : r.tools().size();
        List<NaruMessage> messages = r.messages();
        if (messages != null) {
            s.messages = messages.size();
            for (NaruMessage msg : messages) {
                Bucket b = bucketOf(msg);
                s.add(b, MSG_OVERHEAD_CHARS);
                if (msg.getContent() != null) {
                    s.add(b, msg.getContent().length());
                }
                // a tool call is assistant text the provider still tokenizes: the name
                // plus the serialized arguments, and it stays in the history on every
                // later call
                if (msg.getToolCalls() != null) {
                    for (NaruToolCall call : msg.getToolCalls()) {
                        s.toolCalls++;
                        s.add(b, chars(call.getName()) + chars(call.getArguments()));
                    }
                }
            }
        }
        if (r.tools() != null) {
            for (NaruToolDefinition tool : r.tools()) {
                s.add(Bucket.TOOL_DEFS, chars(tool.getName()) + chars(tool.getDescription()));
                if (tool instanceof NaruToolDefinitionFunction f) {
                    s.add(Bucket.TOOL_DEFS, schemaChars(f.getParams()));
                }
            }
        }
        for (Bucket b : Bucket.values()) {
            // rounded per bucket, not on the grand total: a system prompt of 6 chars is
            // 2 tokens of overhead to the provider, and hiding that inside a large sum
            // is what let the shares stop adding up to the headline
            s.bucketTokens[b.ordinal()] = Math.round(s.chars(b) / CHARS_PER_TOKEN);
            s.tokens += s.bucketTokens[b.ordinal()];
        }
        s.toolResults = s.tokens(Bucket.TOOL_RESULTS);
        return s;
    }

    private static Bucket bucketOf(NaruMessage msg) {
        switch (msg.getRole()) {
            case tool:
                return Bucket.TOOL_RESULTS;
            case system:
                return Bucket.SYSTEM;
            case assistant:
                return Bucket.ASSISTANT;
            case user:
                return msg.getSource() == NaruSource.USER ? Bucket.USER : Bucket.AGENT;
            default:
                // summary blocks stand in for conversation, and they are replayed on
                // every call like any other context
                return Bucket.SYSTEM;
        }
    }

    /**
     * The size of the {@code parameters} block the protocol will actually send,
     * rendered by the same writer the serializers use. Counting the parameter names
     * instead would miss the descriptions and constraints, which are most of it.
     */
    private static int schemaChars(List<net.thevpc.naru.api.registry.NaruToolParameter> params) {
        if (params == null || params.isEmpty()) {
            return 0;
        }
        return NElementWriter.ofJson()
                .formatPlain(net.thevpc.naru.api.registry.NaruToolSchema.functionSchema(params))
                .length();
    }
}
