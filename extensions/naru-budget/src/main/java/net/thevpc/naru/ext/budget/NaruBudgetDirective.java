package net.thevpc.naru.ext.budget;

import net.thevpc.naru.api.access.NaruAccessGuard;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruResource;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.util.NaruUtils;
import net.thevpc.naru.ext.budget.store.DefaultNaruBudgetStoreQuery;
import net.thevpc.naru.ext.budget.store.NaruBudgetStoreQuery;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NStringBuilder;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * {@code /budget} — spend and unit prices, in one command.
 *
 * <p>The report answers "what has this cost" for a scope and grouping the caller
 * chooses: a level ({@code task}/{@code session}/{@code project}) is a filter on the
 * shared project store, and {@code --group-by} is a flat tuple of label keys. The
 * unique content of the retired {@code /stats} command (the request estimate, the
 * bucket shares, the skill attribution and the calibration line) lives behind
 * {@code --explain}, strictly opt-in: the default report is numbers that were
 * reported, not numbers that were guessed.
 *
 * <p>Price mutation ({@code price set|unset|refresh}) and label mutation
 * ({@code label set|unset|clear}) are accounting writes and are gated by
 * {@link NaruAccessGuard} with {@link NaruAction#UPDATE}; the built-in policy denies
 * them to an AGENT actor, because an agent that can price its own calls can rewrite
 * its own bill.
 */
public class NaruBudgetDirective extends NaruDirectiveBase {

    /** The resource an accounting write touches; the built-in AGENT deny matches on it. */
    static final NaruResourceType BUDGET_RESOURCE = NaruResourceType.of("budget");

    /** Changing a price or a label is an update of the accounting config. */
    static final NaruAction BUDGET_UPDATE = NaruAction.of("update");

    /**
     * Level names. Kept as constants so a rename (the alternative considered was
     * {@code workspace} for {@code project}) is one edit.
     */
    static final String LEVEL_TASK = "task";
    static final String LEVEL_SESSION = "session";
    static final String LEVEL_PROJECT = "project";

    public NaruBudgetDirective() {
        super("budget", "ai", "show and manage budget");
        // The report is the empty-named default, so /budget with no words runs it; with
        // named subcommands registered as well, execute() no longer takes the
        // single-subcommand shortcut and needs the default named explicitly for the
        // empty-argument case.
        noCommand("");
        register(new AbstractSubCommand("", NText.ofPlain("report spend"),
                new SubCommandHelp("[--level=task|session|project]",
                        "scope; default is the current session, project drops the session filter"),
                new SubCommandHelp("[--group-by=<k>[,<k>...]]",
                        "one row per distinct tuple of label keys; default provider"),
                new SubCommandHelp("[--user=<name|all>] [--session=<uuid>] [--provider=<p>] [--label k=v]...",
                        "narrow the scope; --label may repeat"),
                new SubCommandHelp("[--task=<id>]", "one task and everything it spawned"),
                new SubCommandHelp("[--since=<dur>] [--from=<ts>] [--to=<ts>]", "narrow by time"),
                new SubCommandHelp("[--explain]", "add the request estimate, bucket shares and skill attribution (opt-in)")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeList(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("price", NText.ofPlain("list, set or refresh unit prices"),
                new SubCommandHelp("list", "list the prices in effect"),
                new SubCommandHelp("set <provider>[/<model>] input=<x> output=<y> [cache-read=<x>] [cache-write=<y>]",
                        "set a price, per 1M tokens; the bare provider key applies to every model of that provider"),
                new SubCommandHelp("unset <provider>[/<model>]", "remove a price"),
                new SubCommandHelp("refresh", "fetch prices from a public source (network call; agent-denied)")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executePrice(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("label", NText.ofPlain("manage the user-defined labels stamped on this task's model calls"),
                new SubCommandHelp("set <key>=<value>...", "add or replace labels on the current task"),
                new SubCommandHelp("unset <key>...", "remove labels from the current task"),
                new SubCommandHelp("list", "list the current task's labels"),
                new SubCommandHelp("clear", "remove every label from the current task")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeLabel(context, cmdLine);
            }
        });
    }

    // ── /budget label ... ──────────────────────────────────────────────────────

    /**
     * {@code /budget label ...} — manages the free-form labels the budget extension
     * stamps on every model call this task makes. Labels are stored on the task (and
     * persisted with it); a label never overrides a built-in dimension.
     */
    public NaruStmtResult executeLabel(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        String sub = cmdLine.isEmpty() ? "list" : cmdLine.next().get().image();
        switch (sub) {
            case "set": {
                List<String> kvs = new ArrayList<>();
                while (cmdLine.hasNext()) {
                    kvs.add(cmdLine.next().get().image());
                }
                // validate before mutating so a bad pair does not leave a half-applied set
                LinkedHashMap<String, String> pending = new LinkedHashMap<>();
                for (String kv : kvs) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) {
                        return fail(context, NMsg.ofC("Error: label set expects key=value, got '%s'", kv));
                    }
                    pending.put(kv.substring(0, eq), kv.substring(eq + 1));
                }
                if (pending.isEmpty()) {
                    return fail(context, NMsg.ofC("%s", "Error: label set expects at least one key=value"));
                }
                NaruStmtResult denied = requireAccountingWrite(context, "label");
                if (denied != null) {
                    return denied;
                }
                pending.forEach(task::setLabel);
                return NaruStmtResult.ofSuccess(formatLabels(task.labels()));
            }
            case "unset": {
                List<String> keys = new ArrayList<>();
                while (cmdLine.hasNext()) {
                    keys.add(cmdLine.next().get().image());
                }
                if (keys.isEmpty()) {
                    return fail(context, NMsg.ofC("%s", "Error: label unset expects at least one key"));
                }
                NaruStmtResult denied = requireAccountingWrite(context, "label");
                if (denied != null) {
                    return denied;
                }
                keys.forEach(task::removeLabel);
                return NaruStmtResult.ofSuccess(formatLabels(task.labels()));
            }
            case "clear": {
                NaruStmtResult denied = requireAccountingWrite(context, "label");
                if (denied != null) {
                    return denied;
                }
                task.clearLabels();
                return NaruStmtResult.ofSuccess("labels cleared");
            }
            case "list":
                return NaruStmtResult.ofSuccess(formatLabels(task.labels()));
            default:
                return fail(context, NMsg.ofC("Error: unknown label command '%s' (expected set|unset|list|clear)", sub));
        }
    }

    private static String formatLabels(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "no labels";
        }
        return labels.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    // ── /budget price ... ──────────────────────────────────────────────────────

    public NaruStmtResult executePrice(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        String sub = cmdLine.isEmpty() ? "list" : cmdLine.next().get().image();
        NaruBudgetService svc = NaruBudgetExtension.budget(task.session());
        switch (sub) {
            case "list":
                return executePriceList(context, svc);
            case "set":
                return executePriceSet(context, svc, cmdLine);
            case "unset":
                return executePriceUnset(context, svc, cmdLine);
            case "refresh":
                return executePriceRefresh(context, svc);
            default:
                return fail(context, NMsg.ofC("Error: unknown price command '%s' (expected list|set|unset|refresh)", sub));
        }
    }

    private NaruStmtResult executePriceList(NaruDirectiveCallContext context, NaruBudgetService svc) {
        NaruTask task = context.task();
        Map<String, NaruUnitPrice> entries = svc.priceTable().entries();
        NStringBuilder sb = NStringBuilder.of();
        if (entries.isEmpty()) {
            String line = "no prices set — spent tokens are counted but unpriced (use /budget price set, or /budget price refresh)";
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", line));
            sb.println(line);
            return NaruStmtResult.ofSuccess(sb.toString());
        }
        NMsg header = NMsg.ofC("prices (per 1M tokens): %s", entries.size());
        task.log(NaruLogMode.AGENT_RESPONSE, header);
        sb.println(header.toString());
        for (Map.Entry<String, NaruUnitPrice> e : entries.entrySet()) {
            NaruUnitPrice p = e.getValue();
            NMsg row = NMsg.ofC("  %s  input %s, output %s%s%s  [%s%s]",
                    NMsg.ofStyledPrimary1(e.getKey()),
                    money(p.input()), money(p.output()),
                    p.hasExplicitCacheRead() ? ", cache-read " + money(p.cacheReadPrice()) : "",
                    p.hasExplicitCacheWrite() ? ", cache-write " + money(p.cacheWritePrice()) : "",
                    p.source() == NaruPriceSource.USER ? "user" : "fetched",
                    p.source() == NaruPriceSource.FETCHED && p.origin() != null ? ", " + p.origin() : "");
            task.log(NaruLogMode.AGENT_RESPONSE, row);
            sb.println(row.toString());
        }
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    private NaruStmtResult executePriceSet(NaruDirectiveCallContext context, NaruBudgetService svc, NCmdLine cmdLine) {
        String key = null;
        Map<String, String> fields = new LinkedHashMap<>();
        while (cmdLine.hasNext()) {
            String tok = cmdLine.next().get().image();
            int eq = tok.indexOf('=');
            if (eq > 0) {
                String k = tok.substring(0, eq);
                while (k.startsWith("-")) {
                    k = k.substring(1);
                }
                fields.put(k.toLowerCase(), tok.substring(eq + 1));
            } else if (key == null) {
                key = tok;
            } else {
                return fail(context, NMsg.ofC(
                        "Error: unexpected argument '%s' (usage: /budget price set <provider>[/<model>] input=<x> output=<y> [cache-read=<x>] [cache-write=<y>])",
                        tok));
            }
        }
        if (NBlankable.isBlank(key)) {
            return fail(context, NMsg.ofC("%s", "Error: missing price key (usage: /budget price set <provider>[/<model>] input=<x> output=<y>)"));
        }
        String input = fields.get("input");
        String output = fields.get("output");
        if (input == null || output == null) {
            return fail(context, NMsg.ofC("%s", "Error: price set needs both input=<x> and output=<y> (per 1M tokens)"));
        }
        List<String> warnings = new ArrayList<>();
        NaruUnitPrice price;
        try {
            price = NaruUnitPrice.parse(NaruPriceSource.USER, null, null,
                    input, output, fields.get("cache-read"), fields.get("cache-write"), warnings);
        } catch (IllegalArgumentException ex) {
            return fail(context, NMsg.ofC("Error: %s", ex.getMessage()));
        }
        NaruStmtResult denied = requireAccountingWrite(context, "price");
        if (denied != null) {
            return denied;
        }
        svc.priceTable().set(key, price);

        NStringBuilder sb = NStringBuilder.of();
        NMsg ok = NMsg.ofC("price set: %s  input %s, output %s",
                NMsg.ofStyledPrimary1(key), price.input().toPlainString(), price.output().toPlainString());
        context.task().log(NaruLogMode.AGENT_RESPONSE, ok);
        sb.println(ok.toString());
        for (String w : warnings) {
            NMsg warn = NMsg.ofC("warning: %s", w);
            context.task().log(NaruLogMode.AGENT_RESPONSE, warn);
            sb.println(warn.toString());
        }
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    private NaruStmtResult executePriceUnset(NaruDirectiveCallContext context, NaruBudgetService svc, NCmdLine cmdLine) {
        if (!cmdLine.hasNext()) {
            return fail(context, NMsg.ofC("%s", "Error: missing price key (usage: /budget price unset <provider>[/<model>])"));
        }
        String key = cmdLine.next().get().image();
        if (NBlankable.isBlank(key)) {
            return fail(context, NMsg.ofC("%s", "Error: missing price key (usage: /budget price unset <provider>[/<model>])"));
        }
        NaruStmtResult denied = requireAccountingWrite(context, "price");
        if (denied != null) {
            return denied;
        }
        NaruUnitPrice removed = svc.priceTable().unset(key);
        NMsg msg = removed == null
                ? NMsg.ofC("no price was set for %s", key)
                : NMsg.ofC("price removed: %s", key);
        context.task().log(NaruLogMode.AGENT_RESPONSE, msg);
        return NaruStmtResult.ofSuccess(msg.toString());
    }

    private NaruStmtResult executePriceRefresh(NaruDirectiveCallContext context, NaruBudgetService svc) {
        NaruStmtResult denied = requireAccountingWrite(context, "price");
        if (denied != null) {
            return denied;
        }
        String report;
        try {
            report = NaruPriceRefresher.refresh(svc.priceTable());
        } catch (RuntimeException ex) {
            return fail(context, NMsg.ofC("Error: price refresh failed: %s", ex.getMessage()));
        }
        NStringBuilder sb = NStringBuilder.of();
        for (String line : report.split("\\n")) {
            context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", line));
            sb.println(line);
        }
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    /**
     * {@code requireAccountingWrite} — a price or label write is an accounting change, so
     * it is gated with {@link NaruAction#UPDATE} on the {@code budget} resource. The
     * built-in policy denies it to an AGENT; a human acting directly is allowed. Returns
     * null when allowed.
     */
    private NaruStmtResult requireAccountingWrite(NaruDirectiveCallContext context, String locator) {
        NaruTask task = context.task();
        if (NaruAccessGuard.isAllowed(task, NaruResource.of(BUDGET_RESOURCE, locator), BUDGET_UPDATE)) {
            return null;
        }
        return fail(context, NMsg.ofC(
                "Error: permission denied: this actor may not change budget %s (an AGENT may not rewrite its own bill)", locator));
    }

    // ── /budget report (default) ───────────────────────────────────────────────

    /**
     * The report options, parsed from the command line. Kept as a small mutable holder
     * because the report is one pass over them.
     */
    static final class ReportOptions {
        String level = LEVEL_SESSION;
        String groupBy = "provider";
        String user;
        String sessionId;
        String provider;
        String taskId;
        final List<String[]> labels = new ArrayList<>();
        Instant from;
        Instant to;
        boolean explain;
    }

    public NaruStmtResult executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruSession session = task.session();
        ReportOptions opts = new ReportOptions();
        NaruStmtResult parseError = parseReportOptions(context, cmdLine, opts);
        if (parseError != null) {
            return parseError;
        }

        NaruBudgetService svc = NaruBudgetExtension.budget(session);
        NaruBudgetStoreQuery query = buildQuery(session, opts);
        List<NaruTokenTransaction> txs = svc.findTransactions(query);
        if (opts.taskId != null) {
            String id = opts.taskId;
            txs = txs.stream().filter(t -> inSubtree(t, id)).collect(Collectors.toList());
        } else if (LEVEL_TASK.equals(opts.level)) {
            String id = String.valueOf(task.id());
            txs = txs.stream().filter(t -> inSubtree(t, id)).collect(Collectors.toList());
        }

        String[] groupKeys = opts.groupBy.split(",");
        for (int i = 0; i < groupKeys.length; i++) {
            groupKeys[i] = groupKeys[i].trim();
        }

        // flat tuple grouping, in a stable order
        Map<String, List<NaruTokenTransaction>> groups = new TreeMap<>();
        for (NaruTokenTransaction t : txs) {
            groups.computeIfAbsent(tupleKey(t, groupKeys), k -> new ArrayList<>()).add(t);
        }

        NStringBuilder sb = NStringBuilder.of();
        List<Group> rows = new ArrayList<>();
        for (Map.Entry<String, List<NaruTokenTransaction>> e : groups.entrySet()) {
            NaruAccumulatorBuilder b = new NaruAccumulatorBuilder();
            e.getValue().forEach(b::accumulate);
            rows.add(new Group(e.getKey(), b.build()));
        }
        rows.sort(Comparator
                .<Group, BigDecimal>comparing(g -> g.stats.spending().total()).reversed()
                .thenComparing(g -> g.key));

        log(task, sb, NMsg.ofC("%s  (%s)", NMsg.ofStyledPrimary1("/budget"), describeScope(opts, groupKeys)));
        if (rows.isEmpty()) {
            log(task, sb, NMsg.ofC("%s", "  no model calls in scope"));
        }
        for (Group g : rows) {
            log(task, sb, NMsg.ofC("%s: %s", NMsg.ofStyledPrimary1(String.join(",", groupKeys)),
                    NMsg.ofStyledPrimary2(g.key)));
            log(task, sb, summaryLine(g.stats));
            if (groupKeys.length == 1 && "model".equals(groupKeys[0])) {
                printModelDetail(task, sb, session, g.stats);
            }
        }

        // totals
        NaruAccumulatorBuilder totalB = new NaruAccumulatorBuilder();
        txs.forEach(totalB::accumulate);
        NaruModelBudgetStats total = totalB.build();
        if (!rows.isEmpty()) {
            log(task, sb, NMsg.ofC("%s", "-----------------------------------------------"));
            log(task, sb, NMsg.ofC("priced total  %s calls | in %s | out %s | cost %s%s",
                    total.calls(),
                    NaruUtils.formattedTokensSize(total.promptTokens()),
                    NaruUtils.formattedTokensSize(total.completionTokens()),
                    money(total.spending().total()),
                    total.unpricedCalls() > 0 ? "   (" + total.unpricedCalls() + " calls unpriced)" : ""));
            for (Map.Entry<String, String> e : unpricedNotices(txs, svc.priceTable()).entrySet()) {
                log(task, sb, NMsg.ofC("unpriced: %s (%s)", e.getKey(), e.getValue()));
            }
        }

        if (!opts.explain) {
            printTokenContributions(task, sb);
        }

        // rate limits (the same section the report always had)
        for (NaruProviderRateLimitInfo s : svc.findProviderRateLimitInfos()) {
            NMsg msg = NMsg.ofC("%s (%s)%s%s",
                    NMsg.ofStyledPrimary9(s.providerName()),
                    s.serverTime(),
                    s.retryAfter().isPresent() ? ", retry after  : " : "",
                    s.retryAfter().orNull());
            log(task, sb, msg);
            for (NaruRateLimitBucket requestBucket : s.requestBuckets()) {
                NMsg bmsg = bucketMsg("request", requestBucket);
                if (bmsg != null) {
                    log(task, sb, bmsg);
                }
            }
            for (NaruRateLimitBucket tokenBucket : s.tokenBuckets()) {
                NMsg tmsg = bucketMsg("token  ", tokenBucket);
                if (tmsg != null) {
                    log(task, sb, tmsg);
                }
            }
        }

        if (opts.explain) {
            printExplain(task, sb, session, svc, query);
        }
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    private NaruStmtResult parseReportOptions(NaruDirectiveCallContext context, NCmdLine cmdLine, ReportOptions opts) {
        while (cmdLine.hasNext()) {
            NArg a = cmdLine.next().get();
            if (!a.isOption()) {
                return fail(context, NMsg.ofC("Error: unexpected argument '%s' (see /budget help)", a.image()));
            }
            String key = a.key();
            switch (key) {
                case "--level": {
                    String value = optionValue(a, cmdLine);
                    if (value == null || !(value.equals(LEVEL_TASK) || value.equals(LEVEL_SESSION) || value.equals(LEVEL_PROJECT))) {
                        return fail(context, NMsg.ofC("%s", "Error: --level must be task, session or project"));
                    }
                    opts.level = value;
                    break;
                }
                case "--group-by": {
                    String value = optionValue(a, cmdLine);
                    if (NBlankable.isBlank(value)) {
                        return fail(context, NMsg.ofC("%s", "Error: --group-by needs a label key (e.g. provider, provider,task-name)"));
                    }
                    opts.groupBy = value;
                    break;
                }
                case "--user":
                    opts.user = optionValue(a, cmdLine);
                    break;
                case "--session":
                    opts.sessionId = optionValue(a, cmdLine);
                    break;
                case "--provider":
                    opts.provider = optionValue(a, cmdLine);
                    break;
                case "--label": {
                    String value = optionValue(a, cmdLine);
                    if (value == null || value.indexOf('=') <= 0) {
                        return fail(context, NMsg.ofC("%s", "Error: --label expects key=value"));
                    }
                    int eq = value.indexOf('=');
                    opts.labels.add(new String[]{value.substring(0, eq), value.substring(eq + 1)});
                    break;
                }
                case "--task":
                    opts.taskId = optionValue(a, cmdLine);
                    break;
                case "--since": {
                    String value = optionValue(a, cmdLine);
                    if (NBlankable.isBlank(value)) {
                        return fail(context, NMsg.ofC("%s", "Error: --since expects a duration (e.g. 1h, 30m)"));
                    }
                    NDuration d = NDuration.of(value).orNull();
                    if (d == null) {
                        return fail(context, NMsg.ofC("Error: cannot read '%s' as a duration", value));
                    }
                    opts.from = Instant.now().minusMillis(d.toMillis());
                    break;
                }
                case "--from": {
                    String value = optionValue(a, cmdLine);
                    Instant i = parseInstant(value);
                    if (i == null) {
                        return fail(context, NMsg.ofC("Error: --from expects an ISO-8601 instant, got '%s'", value));
                    }
                    opts.from = i;
                    break;
                }
                case "--to": {
                    String value = optionValue(a, cmdLine);
                    Instant i = parseInstant(value);
                    if (i == null) {
                        return fail(context, NMsg.ofC("Error: --to expects an ISO-8601 instant, got '%s'", value));
                    }
                    opts.to = i;
                    break;
                }
                case "--explain":
                    opts.explain = true;
                    break;
                default:
                    return fail(context, NMsg.ofC("Error: unknown option '%s'", a.image()));
            }
        }
        return null;
    }

    /**
     * The value of a valued option, accepting both {@code --opt=value} and
     * {@code --opt value}. The space form only consumes the next word when it is not
     * itself an option, so a missing value does not swallow the following flag.
     */
    private static String optionValue(NArg a, NCmdLine cmdLine) {
        String v = a.getStringValue().orNull();
        if (NBlankable.isBlank(v)) {
            var next = cmdLine.peek();
            if (next.isPresent() && !next.get().isOption()) {
                v = cmdLine.next().get().image();
            }
        }
        return v;
    }

    private static Instant parseInstant(String value) {
        if (NBlankable.isBlank(value)) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * A level is a filter: project = no session filter, session = the current (or given)
     * session, task = the current task and its subtree (applied in memory, see
     * {@link #inSubtree}).
     */
    private static NaruBudgetStoreQuery buildQuery(NaruSession session, ReportOptions opts) {
        NaruBudgetStoreQuery q = new DefaultNaruBudgetStoreQuery();
        if (!LEVEL_PROJECT.equals(opts.level)) {
            String sid = opts.sessionId != null ? opts.sessionId : (session == null ? null : session.uuid());
            if (sid != null) {
                q.whereLabel("session", sid);
            }
        } else if (opts.sessionId != null) {
            q.whereLabel("session", opts.sessionId);
        }
        if (opts.user != null && !"all".equalsIgnoreCase(opts.user)) {
            q.whereLabel("user", opts.user);
        }
        if (opts.provider != null) {
            q.whereLabel("provider", opts.provider);
        }
        for (String[] kv : opts.labels) {
            q.whereLabel(kv[0], kv[1]);
        }
        if (opts.from != null || opts.to != null) {
            q.wherePeriod(opts.from, opts.to);
        }
        return q;
    }

    /**
     * Whether the transaction's {@code task-path} has {@code id} as one of its components.
     * This is the subtree rollup: the task itself carries its own id in the path, and
     * every descendant carries it too. A plain prefix cannot do this for an id whose
     * ancestors are unknown to the caller, so the match is on components.
     */
    static boolean inSubtree(NaruTokenTransaction t, String id) {
        String path = t.getLabels().asMap().get("task-path");
        if (path == null) {
            return false;
        }
        for (String c : path.split("/")) {
            if (c.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static String tupleKey(NaruTokenTransaction t, String[] groupKeys) {
        List<String> vals = new ArrayList<>(groupKeys.length);
        for (String k : groupKeys) {
            String v = labelValue(t, k);
            vals.add(v == null ? "(none)" : v);
        }
        return String.join(", ", vals);
    }

    /**
     * The value of a label key on a transaction, falling back to the model for the
     * built-in dimensions the accumulator also derives from the model. A transaction
     * built straight from a {@link NaruTokenTransaction} constructor may carry no
     * {@code provider}/{@code model} label, and grouping it under {@code (none)} would
     * split a provider's spend in two.
     */
    private static String labelValue(NaruTokenTransaction t, String key) {
        String v = t.getLabels().asMap().get(key);
        if (v != null) {
            return v;
        }
        NaruModelConfig model = t.getModel();
        if (model != null) {
            switch (key) {
                case "provider":
                    return model.provider();
                case "model":
                    return model.model();
                case "fullModel":
                    return model.name();
                default:
                    break;
            }
        }
        return null;
    }

    static final class Group {
        final String key;
        final NaruModelBudgetStats stats;

        Group(String key, NaruModelBudgetStats stats) {
            this.key = key;
            this.stats = stats;
        }
    }

    private NMsg summaryLine(NaruModelBudgetStats s) {
        String hit;
        double ratio = s.cacheHitRatio();
        if (ratio < 0) {
            hit = "n/a";
        } else {
            hit = new DecimalFormat("#.#").format(ratio * 100.0) + "%" + (s.cacheRatioPartial() ? " partial" : "");
        }
        String cost = money(s.spending().total()) + (s.isApproximate() ? " (approx.)" : "");
        return NMsg.ofC("  calls %s | in %s | out %s | cache-hit %s | cost %s",
                s.calls(),
                NaruUtils.formattedTokensSize(s.promptTokens()),
                NaruUtils.formattedTokensSize(s.completionTokens()),
                hit,
                cost);
    }

    private String describeScope(ReportOptions opts, String[] groupKeys) {
        StringBuilder sb = new StringBuilder();
        sb.append("level ").append(opts.level);
        sb.append(", group-by ").append(String.join(",", groupKeys));
        if (opts.user != null) {
            sb.append(", user ").append(opts.user);
        }
        if (opts.provider != null) {
            sb.append(", provider ").append(opts.provider);
        }
        if (opts.taskId != null) {
            sb.append(", task ").append(opts.taskId);
        }
        return sb.toString();
    }

    private static String money(BigDecimal v) {
        return new DecimalFormat("0.0000").format(v);
    }

    /**
     * The distinct unpriced {@code provider/model} keys with a short reason. A model that
     * is unpriced because its provider has no price reads differently from one whose
     * provider is priced but the model is not.
     */
    private static Map<String, String> unpricedNotices(List<NaruTokenTransaction> txs, NaruBudgetPriceTable table) {
        Map<String, String> out = new TreeMap<>();
        for (NaruTokenTransaction t : txs) {
            if (t.getPrice() != null || t.getModel() == null) {
                continue;
            }
            String provider = t.getModel().provider();
            String model = t.getModel().model();
            String k = provider + "/" + model;
            if (out.containsKey(k)) {
                continue;
            }
            boolean providerPriced = provider != null && table.get(provider) != null;
            out.put(k, providerPriced
                    ? "no price for model '" + k + "'"
                    : "no price for provider '" + provider + "'");
        }
        return out;
    }

    private void printModelDetail(NaruTask task, NStringBuilder sb, NaruSession session, NaruModelBudgetStats modelStat) {
        NaruModelConfig model = session.findModel(modelStat.fullModelKey()).orNull();
        long contextSize = model != null ? model.contextLength() : -1;
        log(task, sb, NMsg.ofC("  %s  | %s %s, %s %s, %s %s",
                NMsg.ofStyledPrimary1("context"),
                "used", contextShare(modelStat.contextUsage(), contextSize),
                "peak", contextShare(modelStat.peakContextUsage(), contextSize),
                "available", contextSize > 0 ? NaruUtils.formattedTokensSize(contextSize) : NMsg.ofC("%s", "unknown")));
        log(task, sb, NMsg.ofC("  %s    | %s", NMsg.ofStyledPrimary1("calls"), modelStat.calls()));
        log(task, sb, NMsg.ofC("  %s | min %s, max %s",
                NMsg.ofStyledPrimary1("duration"),
                NDuration.ofMillis(modelStat.minDuration()),
                NDuration.ofMillis(modelStat.maxDuration())));
        log(task, sb, NMsg.ofC("  %s   | %s, prompt %s, eval %s",
                NMsg.ofStyledPrimary1("tokens"),
                modelStat.totalTokens(),
                modelStat.promptTokens(),
                modelStat.completionTokens()));
    }

    /**
     * The opt-in section: the request estimate, the bucket shares, the skill attribution
     * and the calibration line, ported from the retired {@code /stats}.
     */
    private void printExplain(NaruTask task, NStringBuilder sb, NaruSession session,
                              NaruBudgetService svc, NaruBudgetStoreQuery query) {
        List<NaruModelBudgetStats> modelStats = svc.findByModelBudgetStats(query)
                .stream()
                .filter(a -> a.calls() > 0)
                .sorted(Comparator
                        .<NaruModelBudgetStats, BigDecimal>comparing(a -> a.spending().total()).reversed()
                        .thenComparing(a -> String.valueOf(a.providerKey()))
                        .thenComparing(a -> String.valueOf(a.modelKey())))
                .collect(Collectors.toList());

        PromptStats s = estimateTokens(task);
        log(task, sb, NMsg.ofC("%s", ""));
        log(task, sb, NMsg.ofC("%s", "explain:"));
        log(task, sb, NMsg.ofC("  %s",
                NMsg.ofC("tool defs : %s | messages : %s | tool calls : %s",
                        s.toolDefs, s.messages, s.toolCalls)));
        log(task, sb, NMsg.ofC("  estimated tokens : ~%s (rough text estimate)", s.tokens));
        log(task, sb, contributionLine("  messages", s, Bucket.SYSTEM, Bucket.USER, Bucket.AGENT, Bucket.ASSISTANT, Bucket.THINKING));
        log(task, sb, contributionLine("  tools", s, Bucket.TOOL_CALLS, Bucket.TOOL_DEFS, Bucket.TOOL_RESULTS));
        if (s.skillCatalogMessages + s.skillLoadedMessages + s.agentOtherChars > 0) {
            log(task, sb, NMsg.ofC("%s", "  skill contribution (agent source) :"));
            log(task, sb, NMsg.ofC("    %s : %s / ~%s", "catalog",
                    plural(s.skillCatalogMessages, "message"), s.skillCatalogTokens()));
            log(task, sb, NMsg.ofC("    %s : %s / ~%s", "loaded bodies",
                    plural(s.skillLoadedMessages, "message"), s.skillLoadedTokens()));
            log(task, sb, NMsg.ofC("    %s : %s / ~%s", "other agent",
                    plural(s.agentOtherMessages, "message"), s.agentOtherTokens()));
        } else {
            log(task, sb, NMsg.ofC("  skill contribution (agent source) : %s", "none in the agent bucket"));
        }
        log(task, sb, NMsg.ofC("%s", "  per model (this scope) :"));
        if (modelStats.isEmpty()) {
            log(task, sb, NMsg.ofC("    %s", "no model spent tokens yet"));
        }
        for (NaruModelBudgetStats m : modelStats) {
            log(task, sb, NMsg.ofC("    %s | %s %s, %s %s, %s %s, %s %s",
                    NMsg.ofStyledPrimary1(String.valueOf(m.fullModelKey())),
                    "calls", m.calls(), "prompt", m.promptTokens(),
                    "eval", m.completionTokens(), "cost", money(m.spending().total())));
        }
        NaruModelBudgetStats lastCall = lastCalledModel(modelStats);
        if (lastCall != null && lastCall.contextUsage() > 0) {
            long reported = lastCall.contextUsage();
            log(task, sb, NMsg.ofC("  reported last call : %s prompt tokens for %s (estimate / reported : %s)",
                    reported,
                    lastCall.fullModelKey(),
                    new DecimalFormat("#.##").format((double) s.tokens / reported)));
        }
    }

    /** Shows a rough token split of the current request context in the normal report. */
    private void printTokenContributions(NaruTask task, NStringBuilder sb) {
        PromptStats s = estimateTokens(task);
        log(task, sb, NMsg.ofC("%s", "approximate current prompt context (character-based token estimate):"));
        log(task, sb, NMsg.ofC("  estimated ~%s tokens; contributions:", s.tokens));
        log(task, sb, contributionLine("    messages", s, Bucket.SYSTEM, Bucket.USER, Bucket.AGENT, Bucket.ASSISTANT, Bucket.THINKING));
        log(task, sb, contributionLine("    tools", s, Bucket.TOOL_CALLS, Bucket.TOOL_DEFS, Bucket.TOOL_RESULTS));
    }

    private NMsg contributionLine(String label, PromptStats s, Bucket... buckets) {
        StringBuilder line = new StringBuilder(label).append(": ");
        for (int i = 0; i < buckets.length; i++) {
            if (i > 0) line.append(", ");
            Bucket bucket = buckets[i];
            String share = s.tokens <= 0 ? "n/a" : percent(s.tokens(bucket), s.tokens) + "%";
            line.append(bucketLabel(bucket)).append(" ~").append(s.tokens(bucket))
                    .append(" (").append(share).append(")");
        }
        return NMsg.ofC("%s", line.toString());
    }

    private String bucketLabel(Bucket bucket) {
        switch (bucket) {
            case TOOL_CALLS: return "tool calls";
            case TOOL_DEFS: return "tool schemas";
            case TOOL_RESULTS: return "tool results";
            default: return bucket.name().toLowerCase().replace("_", " ");
        }
    }

    private void log(NaruTask task, NStringBuilder sb, NMsg msg) {
        task.log(NaruLogMode.AGENT_RESPONSE, msg);
        sb.println(msg.toString());
    }

    private NaruStmtResult fail(NaruDirectiveCallContext context, NMsg msg) {
        NaruTask task = context.task();
        if (task != null) {
            task.log(NaruLogMode.AGENT_RESPONSE, msg.asError());
        }
        return NaruStmtResult.ofError(msg.toString());
    }

    private static String plural(long n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    // ── estimator (feeds --explain) ────────────────────────────────────────────

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
            if (best == null || s.contextUsage() > best.contextUsage()) {
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
                "limit", limit,
                "used", (limit != null && remaining != null) ? limit - remaining : null,
                "remaining", remaining,
                "resetTime", bucket.getResetTime().orNull());
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
        THINKING,
        TOOL_CALLS,
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

        // what the AGENT bucket is made of: skill messages split by whether the
        // source-name is a "catalog:" advertisement or a loaded skill body, everything
        // else (routines, session defaults, ...) in "other agent"
        long skillCatalogChars;
        long skillLoadedChars;
        long agentOtherChars;
        int skillCatalogMessages;
        int skillLoadedMessages;
        int agentOtherMessages;

        long chars(Bucket b) {
            return chars[b.ordinal()];
        }

        long tokens(Bucket b) {
            return bucketTokens[b.ordinal()];
        }

        void add(Bucket b, long n) {
            chars[b.ordinal()] += n;
        }

        long skillCatalogTokens() {
            return Math.round(skillCatalogChars / CHARS_PER_TOKEN);
        }

        long skillLoadedTokens() {
            return Math.round(skillLoadedChars / CHARS_PER_TOKEN);
        }

        long agentOtherTokens() {
            return Math.round(agentOtherChars / CHARS_PER_TOKEN);
        }

        /**
         * Routes an AGENT-bucket message to the skill split. The {@code catalog:} prefix is
         * the skills extension's mark on advert rows; anything else with a SKILL source is a
         * loaded body (a real file path, or {@code skills:<name>} for a missing skill).
         */
        void attributeAgent(NaruMessage msg, long msgChars) {
            if (msg.getSource() == NaruSource.SKILL) {
                String sourceName = msg.getSourceName();
                if (sourceName != null && sourceName.startsWith("catalog:")) {
                    skillCatalogChars += msgChars;
                    skillCatalogMessages++;
                } else {
                    skillLoadedChars += msgChars;
                    skillLoadedMessages++;
                }
            } else {
                agentOtherChars += msgChars;
                agentOtherMessages++;
            }
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
                long msgChars = MSG_OVERHEAD_CHARS;
                if (msg.getContent() != null) {
                    msgChars += msg.getContent().length();
                }
                if (msg.getToolCalls() != null) {
                    long toolCallChars = 0;
                    for (NaruToolCall call : msg.getToolCalls()) {
                        s.toolCalls++;
                        toolCallChars += chars(call.getName()) + chars(call.getArguments());
                    }
                    s.add(Bucket.TOOL_CALLS, toolCallChars);
                }
                s.add(b, msgChars);
                if (msg.getThinking() != null) {
                    s.add(Bucket.THINKING, msg.getThinking().length());
                }
                if (b == Bucket.AGENT) {
                    s.attributeAgent(msg, msgChars);
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
