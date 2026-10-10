package net.thevpc.naru.ext.budget;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NStringBuilder;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code /stats} — who pays for the current request.
 *
 * <p>{@code /budget} prices the whole request and bills per model, but it cannot say what
 * the AGENT bucket is made of, so a context that grows adds skill after skill is invisible:
 * catalog advertisements and loaded skill bodies are both {@code user}/{@code SKILL}
 * messages and both land in the same bucket. This directive attributes that bucket (WP5):
 *
 * <pre>
 * estimated request  : 12 340 tokens
 *   system : 28%, user : 9%, agent : 41%, assistant : 12%, tool defs : 7%, tool results : 3%
 * skill contribution (agent source) :
 *   catalog       : 14 messages / ~2480 tokens (advertised skills, "catalog:*")
 *   loaded bodies : 3 messages / ~2310 tokens (spliced skill bodies)
 *   other agent   : 7 messages / ~313 tokens
 * per model (this session) :
 *   ollama/llama3.1:8b | calls 2, prompt 300, eval 30, cost 0.0001234
 * </pre>
 *
 * The split rides on the skills extension's {@code catalog:} source-name prefix: a
 * {@code SKILL} message whose source-name starts with it is an advertisement, anything
 * else is a loaded body. Neither {@code /stats} nor any other command acts on the split —
 * it exists to be read.
 */
public class NaruStatsDirective extends NaruDirectiveBase {

    public NaruStatsDirective() {
        super("stats", "ai", "attribute the current request: catalog vs loaded skills, buckets, per-model spend");
        register(new AbstractSubCommand() {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeStats(context, cmdLine);
            }
        });
    }

    protected NaruStmtResult executeStats(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruSession session = context.task().session();
        List<NaruModelBudgetStats> modelStats = NaruBudgetExtension.budget(session).findByModelBudgetStats(null)
                .stream()
                .filter(a -> a.calls() > 0)
                .sorted(Comparator
                        .<NaruModelBudgetStats, BigDecimal>comparing(a -> a.spending().total()).reversed()
                        .thenComparing(a -> a.providerKey())
                        .thenComparing(a -> a.modelKey())
                )
                .collect(Collectors.toList());

        NaruBudgetDirective.PromptStats s = NaruBudgetDirective.estimateTokens(task);

        NStringBuilder sb = NStringBuilder.of();
        log(task, sb, NMsg.ofC("=== stats ==="));
        log(task, sb, NMsg.ofC("estimated request  : %s tokens", s.tokens));
        log(task, sb, NMsg.ofC("  %s : %s, %s : %s, %s : %s, %s : %s, %s : %s, %s : %s",
                "system", share(s.tokens(NaruBudgetDirective.Bucket.SYSTEM), s.tokens),
                "user", share(s.tokens(NaruBudgetDirective.Bucket.USER), s.tokens),
                "agent", share(s.tokens(NaruBudgetDirective.Bucket.AGENT), s.tokens),
                "assistant", share(s.tokens(NaruBudgetDirective.Bucket.ASSISTANT), s.tokens),
                "tool defs", share(s.tokens(NaruBudgetDirective.Bucket.TOOL_DEFS), s.tokens),
                "tool results", share(s.tokens(NaruBudgetDirective.Bucket.TOOL_RESULTS), s.tokens)));
        // nothing to split: a request with no SKILL agent messages cannot lie about the split
        if (s.skillCatalogMessages + s.skillLoadedMessages + s.agentOtherChars > 0) {
            log(task, sb, NMsg.ofC("skill contribution (agent source) :"));
            log(task, sb, NMsg.ofC("  %s : %s / ~%s (%s)",
                    "catalog", plural(s.skillCatalogMessages, "message"), s.skillCatalogTokens(),
                    "advertised skills, \"catalog:*\""));
            log(task, sb, NMsg.ofC("  %s : %s / ~%s (%s)",
                    "loaded bodies", plural(s.skillLoadedMessages, "message"), s.skillLoadedTokens(),
                    "spliced skill bodies"));
            log(task, sb, NMsg.ofC("  %s : %s / ~%s",
                    "other agent", plural(s.agentOtherMessages, "message"), s.agentOtherTokens()));
        } else {
            log(task, sb, NMsg.ofC("skill contribution (agent source) : %s",
                    "none in the agent bucket"));
        }
        log(task, sb, NMsg.ofC("per model (this session) :"));
        if (modelStats.isEmpty()) {
            log(task, sb, NMsg.ofC("  %s", "no model spent tokens yet"));
        }
        for (NaruModelBudgetStats m : modelStats) {
            log(task, sb, NMsg.ofC("  %s | %s %s, %s %s, %s %s, %s %s",
                    NMsg.ofStyledPrimary1(m.fullModelKey()),
                    "calls", m.calls(),
                    "prompt", m.promptTokens(),
                    "eval", m.completionTokens(),
                    "cost", m.spending().total()));
        }
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    private static void log(NaruTask task, NStringBuilder sb, NMsg msg) {
        task.log(NaruLogMode.AGENT_RESPONSE, msg);
        sb.println(msg.toString());
    }

    private static String plural(long n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String share(long part, long total) {
        if (total <= 0) {
            return "n/a";
        }
        return new DecimalFormat("#.###").format(100.0 * part / (double) total) + "%";
    }
}