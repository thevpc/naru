package net.thevpc.naru.ext.budget;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruActor;
import net.thevpc.naru.api.access.NaruActorKind;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.DefaultNaruProviderRateLimitInfo;
import net.thevpc.naru.api.model.DefaultNaruRateLimitBucket;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruRateLimitWindow;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.time.NDuration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.thevpc.naru.ext.budget.NaruBudgetDirective.Bucket;
import static net.thevpc.naru.ext.budget.NaruBudgetDirective.CHARS_PER_TOKEN;
import static net.thevpc.naru.ext.budget.NaruBudgetDirective.MSG_OVERHEAD_CHARS;

/**
 * The {@code /budget} estimator and report.
 *
 * <p>The estimate is a guess, so what these pin down is the arithmetic rather than any
 * one number being right: the characters counted must be the ones the request
 * actually carries, the per-bucket shares must add up to the headline, and a model
 * with no reported context size must not break the report.
 */
public class NaruBudgetDirectiveTest {

    private NaruSessionImpl session;

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    @BeforeEach
    public void setUp() {
        NaruWorkspace agent = NaruWorkspace.of();
        NPath dir = NPath.ofTempFolder("naru-budget-directive-" + System.nanoTime());
        agent.projectDirectory(dir);
        session = (NaruSessionImpl) agent.newSession()
                .directory(dir)
                .interaction(null)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
    }

    /**
     * A task with no model behind it.
     *
     * <p>{@code session.newTask} resolves a model eagerly and this module has no
     * provider on its classpath to resolve one from, so the report is driven through a
     * task that only answers what the directive actually asks it: the session, the
     * context it would send, and a sink for its output.
     */
    private NaruTask task(NaruMessage... messages) {
        NaruModelRequest request = request(List.of(messages), List.of());
        return (NaruTask) Proxy.newProxyInstance(
                NaruTask.class.getClassLoader(),
                new Class<?>[]{NaruTask.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "session" -> session;
                    case "context" -> request;
                    case "history", "contextView" -> request.messages();
                    case "findTools" -> request.tools();
                    case "log" -> null;
                    case "toString" -> "stub-task";
                    case "hashCode" -> 0;
                    case "equals" -> proxy == args[0];
                    // the accounting-write gate resolves the actor; a stub has none, which
                    // the policy reads as "no AGENT match" and therefore allows
                    case "currentActor" -> null;
                    default -> throw new AssertionError(
                            "the /budget directive asked the task for something it should not need: "
                                    + method.getName());
                });
    }

    private static NaruBudgetDirective.PromptStats estimate(NaruMessage... messages) {
        return NaruBudgetDirective.estimateTokens(request(List.of(messages), List.of()));
    }

    private static NaruBudgetDirective.PromptStats estimate(List<NaruMessage> messages, List<NaruToolDefinition> tools) {
        return NaruBudgetDirective.estimateTokens(request(messages, tools));
    }

    private static NaruModelRequest request(List<NaruMessage> messages, List<NaruToolDefinition> tools) {
        return new NaruModelRequest(messages, tools, new LinkedHashMap<>());
    }

    /**
     * A message costs its text plus the framing the provider charges for. Counting only
     * the text made a short conversation look like it cost nothing at all.
     */
    @Test
    public void everyMessageIsChargedItsOverheadAsWellAsItsText() {
        NaruBudgetDirective.PromptStats s = estimate(NaruMessage.system("abcd"));

        Assertions.assertEquals(4 + MSG_OVERHEAD_CHARS, s.chars(Bucket.SYSTEM),
                "the per-message framing is part of what the provider tokenizes");
        Assertions.assertEquals(Math.round((4 + MSG_OVERHEAD_CHARS) / CHARS_PER_TOKEN),
                s.tokens(Bucket.SYSTEM));
    }

    @Test
    public void rolesLandInDistinctBuckets() {
        NaruBudgetDirective.PromptStats s = estimate(
                NaruMessage.system("sys"),
                NaruMessage.user("usr"),
                NaruMessage.assistant("asst"),
                NaruMessage.tool("grep", "call-1", "matched"));

        Assertions.assertTrue(s.chars(Bucket.SYSTEM) > 3, "system prompt");
        Assertions.assertTrue(s.chars(Bucket.USER) > 3, "a message from the user");
        Assertions.assertTrue(s.chars(Bucket.ASSISTANT) > 6, "the assistant reply");
        Assertions.assertEquals(0, s.chars(Bucket.AGENT),
                "nothing in this history came from the agent side");
    }

    /**
     * A tool result and a tool definition are both "tools", and conflating them made a
     * context impossible to read: the definitions are re-sent on every call, the
     * results are not.
     */
    @Test
    public void toolResultsAreNotChargedAsToolDefinitions() {
        NaruBudgetDirective.PromptStats s = estimate(
                NaruMessage.assistantWithToolCalls("", List.of(new NaruToolCall("c1", "grep", Map.of()))),
                NaruMessage.tool("grep", "c1", "matched"));

        Assertions.assertTrue(s.chars(Bucket.TOOL_RESULTS) > 7, "the result text");
        Assertions.assertEquals(0, s.chars(Bucket.TOOL_DEFS),
                "no tool was declared, so no definition can be charged");
    }

    /**
     * A tool call the model made is history the provider re-reads on every later call,
     * and it carries the arguments -- which for a file edit are most of the size.
     */
    @Test
    public void toolCallsAreChargedForNameAndArguments() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", "src/main/java/Foo.java");
        NaruBudgetDirective.PromptStats s = estimate(
                NaruMessage.assistantWithToolCalls("", List.of(new NaruToolCall("c1", "read_file", args))));

        Assertions.assertEquals(1, s.toolCalls);
        Assertions.assertTrue(
                s.chars(Bucket.ASSISTANT) > MSG_OVERHEAD_CHARS + args.toString().length(),
                "the call name has to be counted as well as the arguments");
    }

    /**
     * The name and description of a tool are a fraction of what travels with it: the
     * parameter schema is what the model actually reads.
     */
    @Test
    public void toolDefinitionsAreChargedIncludingTheirParameterSchema() {
        NaruToolDefinition tool = new NaruToolDefinitionFunction(
                "read_file",
                "reads a file from disk",
                NaruToolParameter.string("path", "absolute path of the file to read", true).build());
        NaruBudgetDirective.PromptStats s = estimate(List.of(), List.of(tool));

        Assertions.assertEquals(1, s.toolDefs);
        int nameAndDescription = "read_file".length() + "reads a file from disk".length();
        Assertions.assertTrue(s.chars(Bucket.TOOL_DEFS) > nameAndDescription + 40,
                "the parameters schema must be part of the estimate, got " + s.chars(Bucket.TOOL_DEFS));
    }

    /**
     * The shares are the only way a reader judges the split, so they must sum to the
     * headline.
     */
    @Test
    public void bucketTokensSumToTheTotal() {
        NaruBudgetDirective.PromptStats s = estimate(
                NaruMessage.system("you are a careful assistant with a long brief"),
                NaruMessage.user("please read this file"),
                NaruMessage.assistantWithToolCalls("reading",
                        List.of(new NaruToolCall("c1", "read_file", Map.of("path", "A.java")))),
                NaruMessage.tool("read_file", "c1", "contents"));

        long sum = 0;
        for (Bucket b : Bucket.values()) {
            sum += s.tokens(b);
        }
        Assertions.assertEquals(s.tokens, sum,
                "the total is the sum of the buckets, not a share of some other division");
    }

    /**
     * Rounding happens per bucket on purpose: a bucket with a handful of characters
     * still costs tokens, and dividing one grand total hid that.
     */
    @Test
    public void smallBucketsStillCostWholeTokens() {
        NaruBudgetDirective.PromptStats s = estimate(NaruMessage.user("ok"));

        Assertions.assertTrue(s.tokens(Bucket.USER) > 0,
                "a two-character message is not free once framing is counted");
    }

    @Test
    public void anEmptyRequestCostsNothingRatherThanFailing() {
        NaruBudgetDirective.PromptStats s = estimate(List.of(), List.of());

        Assertions.assertEquals(0, s.tokens);
        for (Bucket b : Bucket.values()) {
            Assertions.assertEquals(0, s.tokens(b));
        }
    }

    private NaruStmtResult report(NaruMessage... messages) {
        NaruDirectiveCallContext ctx = new NaruDirectiveCallContextImpl("budget", null, task(messages));
        return new NaruBudgetDirective().execute(ctx);
    }

    /** The report with an explicit argument list (e.g. {@code --explain} or {@code --group-by=model}). */
    private NaruStmtResult reportWith(String argument, NaruMessage... messages) {
        NaruDirectiveCallContext ctx = new NaruDirectiveCallContextImpl("budget", argument, task(messages));
        return new NaruBudgetDirective().execute(ctx);
    }

    private NaruBudgetService service() {
        return NaruBudgetExtension.budget(session);
    }

    // ── Phase 4: scoping, grouping, prices, labels ──────────────────────────

    @Test
    public void reportGroupsByProviderByDefault() {
        session.fireModelCallUsage(null, new NaruModelKey("alpha", "m1"), 100, 10, -1, -1, NDuration.ofMillis(5));
        session.fireModelCallUsage(null, new NaruModelKey("beta", "m2"), 200, 20, -1, -1, NDuration.ofMillis(5));

        String out = String.valueOf(report().successValue());
        Assertions.assertTrue(out.contains("provider: alpha"), out);
        Assertions.assertTrue(out.contains("provider: beta"), out);
        Assertions.assertTrue(out.contains("priced total"), out);
    }

    /**
     * The grouping key is a flat tuple of label keys, not an enum of preset levels, so
     * {@code --group-by=provider,model} names both dimensions in one row label.
     */
    @Test
    public void groupByTakesAFlatLabelTuple() {
        session.fireModelCallUsage(null, new NaruModelKey("alpha", "m1"), 100, 10, -1, -1, NDuration.ofMillis(5));

        String out = String.valueOf(reportWith("--group-by=provider,model").successValue());
        Assertions.assertTrue(out.contains("provider,model: alpha, m1"), out);
    }

    @Test
    public void providerFilterNarrowsTheReport() {
        session.fireModelCallUsage(null, new NaruModelKey("alpha", "m1"), 100, 10, -1, -1, NDuration.ofMillis(5));
        session.fireModelCallUsage(null, new NaruModelKey("beta", "m2"), 200, 20, -1, -1, NDuration.ofMillis(5));

        String out = String.valueOf(reportWith("--provider=alpha").successValue());
        Assertions.assertTrue(out.contains("provider: alpha"), out);
        Assertions.assertFalse(out.contains("provider: beta"), out);
    }

    /**
     * A level is a filter on the one shared store. Another session's spend is invisible
     * at the default (session) level and appears once the filter is dropped.
     */
    @Test
    public void sessionLevelHidesAnotherSessionsSpendButProjectLevelSeesIt() {
        Labels other = Labels.builder().add("session", "other-session").build();
        service().trackTransaction(new NaruTokenTransaction(other, "other-session", "u",
                new NaruModelConfig(new NaruModelKey("alpha", "m1")),
                500, 50, -1, -1, Instant.now(), NDuration.ofMillis(5)));
        session.fireModelCallUsage(null, new NaruModelKey("alpha", "m1"), 100, 10, -1, -1, NDuration.ofMillis(5));

        String scoped = String.valueOf(report().successValue());
        Assertions.assertTrue(scoped.contains("calls 1"),
                "the default level is the current session:\n" + scoped);
        String project = String.valueOf(reportWith("--level=project").successValue());
        Assertions.assertTrue(project.contains("calls 2"),
                "--level=project drops the session filter:\n" + project);
    }

    @Test
    public void priceSetListUnsetRoundTrips() {
        Assertions.assertEquals(0, reportWith("price set alpha input=1.5 output=6").exitCode());
        Assertions.assertEquals(new BigDecimal("1.5"), service().priceTable().get("alpha").input());

        String list = String.valueOf(reportWith("price list").successValue());
        Assertions.assertTrue(list.contains("alpha"), list);

        Assertions.assertEquals(0, reportWith("price unset alpha").exitCode());
        Assertions.assertNull(service().priceTable().get("alpha"));
    }

    @Test
    public void priceSetTakesACacheReadPrice() {
        Assertions.assertEquals(0,
                reportWith("price set anthropic/claude-x input=3 output=15 cache-read=0.3").exitCode());
        NaruUnitPrice p = service().priceTable().get("anthropic/claude-x");
        Assertions.assertNotNull(p);
        Assertions.assertEquals(new BigDecimal("0.3"), p.cacheReadPrice());
        Assertions.assertTrue(p.hasExplicitCacheRead());
    }

    @Test
    public void priceSetRejectsAMissingOutputAndLeavesNothingBehind() {
        Assertions.assertNotEquals(0, reportWith("price set alpha input=1.5").exitCode());
        Assertions.assertNull(service().priceTable().get("alpha"));
    }

    @Test
    public void priceSetRejectsAnImplausibleValue() {
        Assertions.assertNotEquals(0, reportWith("price set alpha input=1.5 output=2000000").exitCode());
        Assertions.assertNull(service().priceTable().get("alpha"));
    }

    /**
     * The cost is the price <em>snapshotted at track time</em>: a price set before the
     * call applies to it, and (the other half, in the store test) a later change does not
     * rewrite it.
     */
    @Test
    public void aPricedCallCarriesItsCostIntoTheReport() {
        reportWith("price set alpha input=1 output=2");
        session.fireModelCallUsage(null, new NaruModelKey("alpha", "m1"),
                1_000_000, 1_000_000, -1, -1, NDuration.ofMillis(5));

        String out = String.valueOf(report().successValue());
        // 1M input at 1.0/1M plus 1M output at 2.0/1M is exactly 3.0
        Assertions.assertTrue(out.contains("cost 3.0000"), out);
    }

    @Test
    public void anUnpricedCallIsAnnouncedNotSilentlyZero() {
        session.fireModelCallUsage(null, new NaruModelKey("alpha", "m1"), 100, 10, -1, -1, NDuration.ofMillis(5));

        String out = String.valueOf(report().successValue());
        Assertions.assertTrue(out.contains("unpriced"), out);
        Assertions.assertTrue(out.contains("alpha/m1"), out);
    }

    /**
     * A key a transaction does not carry groups under {@code (none)} rather than
     * vanishing; a null-task call has no {@code task-name}.
     */
    @Test
    public void anAbsentGroupingKeyBecomesTheNoneBucket() {
        session.fireModelCallUsage(null, new NaruModelKey("alpha", "m1"), 100, 10, -1, -1, NDuration.ofMillis(5));

        String out = String.valueOf(reportWith("--group-by=task-name").successValue());
        Assertions.assertTrue(out.contains("task-name: (none)"), out);
    }

    @Test
    public void aPeriodExcludesCallsOutsideIt() {
        session.fireModelCallUsage(null, new NaruModelKey("alpha", "m1"), 100, 10, -1, -1, NDuration.ofMillis(5));

        String future = String.valueOf(reportWith("--from=2999-01-01T00:00:00Z").successValue());
        Assertions.assertTrue(future.contains("no model calls in scope"), future);

        String past = String.valueOf(reportWith("--from=2000-01-01T00:00:00Z").successValue());
        Assertions.assertTrue(past.contains("provider: alpha"), past);
    }

    @Test
    public void cacheHitIsNaWhenNoCallReportedCache() {
        session.fireModelCallUsage(null, new NaruModelKey("alpha", "m1"), 100, 10, -1, -1, NDuration.ofMillis(5));

        String out = String.valueOf(report().successValue());
        Assertions.assertTrue(out.contains("cache-hit n/a"), out);
    }

    @Test
    public void cacheHitIsMarkedPartialWhenOnlySomeCallsReportedCache() {
        session.fireModelCallUsage(null, new NaruModelKey("alpha", "m1"), 100, 10, 0, 0, NDuration.ofMillis(5));
        session.fireModelCallUsage(null, new NaruModelKey("alpha", "m1"), 100, 10, -1, -1, NDuration.ofMillis(5));

        String out = String.valueOf(report().successValue());
        Assertions.assertTrue(out.contains("partial"),
                "a mixed group must say the ratio covers only some calls:\n" + out);
    }

    /**
     * The subtree match is on path <em>components</em>: {@code 4} selects {@code 1/4/9}
     * while {@code 44} and the raw prefix {@code 1/4} do not, so a rollup cannot be
     * fooled by an id that merely shares a prefix.
     */
    @Test
    public void subtreeMatchIsOnPathComponentsNotPrefix() {
        NaruTokenTransaction t = new NaruTokenTransaction(
                Labels.builder().add("task-path", "1/4/9").build(),
                "s", "u", new NaruModelConfig(new NaruModelKey("alpha", "m1")),
                1, 1, -1, -1, Instant.now(), NDuration.ofMillis(1));

        Assertions.assertTrue(NaruBudgetDirective.inSubtree(t, "4"));
        Assertions.assertTrue(NaruBudgetDirective.inSubtree(t, "9"));
        Assertions.assertFalse(NaruBudgetDirective.inSubtree(t, "44"),
                "a component match must not be fooled by a shared prefix");
        Assertions.assertFalse(NaruBudgetDirective.inSubtree(t, "1/4"));
    }

    @Test
    public void aLaterPriceChangeDoesNotRewritePastCost() {
        session.fireModelCallUsage(null, new NaruModelKey("alpha", "m1"),
                1_000_000, 0, -1, -1, NDuration.ofMillis(5));

        reportWith("price set alpha input=5 output=5");

        String out = String.valueOf(report().successValue());
        Assertions.assertTrue(out.contains("cost 0.0000"),
                "the price snapshot keeps a past call priced as it was made:\n" + out);
        Assertions.assertTrue(out.contains("unpriced"), out);
    }

    @Test
    public void anUnknownOptionFailsWithAMessage() {
        NaruStmtResult r = reportWith("--nope");
        Assertions.assertNotEquals(0, r.exitCode());
        Assertions.assertTrue(String.valueOf(r.errorValue()).contains("unknown option"),
                String.valueOf(r.errorValue()));
    }

    @Test
    public void aBadLevelValueFailsWithAMessage() {
        NaruStmtResult r = reportWith("--level=galaxy");
        Assertions.assertNotEquals(0, r.exitCode());
        Assertions.assertTrue(String.valueOf(r.errorValue()).contains("--level"),
                String.valueOf(r.errorValue()));
    }

    /**
     * The gating path in the directive: an AGENT actor is denied a price write, so the
     * table is untouched and the command fails. This pins the directive end of the
     * built-in rule that lives in the permissions extension.
     */
    @Test
    public void anAgentActorMayNotSetAPrice() {
        session.accessPolicy().putRule(new NaruPermissionRule()
                .id("test:no-agent-budget")
                .resourceType(NaruResourceType.of("budget"))
                .patterns("**")
                .actions(NaruAction.of("update"))
                .actorKind(NaruActorKind.AGENT)
                .effect(NaruAccessEffect.DENY));

        NaruStmtResult r = reportAs(NaruActor.agent("a1", null), "price set alpha input=1 output=2");
        Assertions.assertNotEquals(0, r.exitCode(), "an agent must not set its own prices");
        Assertions.assertNull(service().priceTable().get("alpha"));
    }

    @Test
    public void aUserActorMaySetAPrice() {
        session.accessPolicy().putRule(new NaruPermissionRule()
                .id("test:no-agent-budget")
                .resourceType(NaruResourceType.of("budget"))
                .patterns("**")
                .actions(NaruAction.of("update"))
                .actorKind(NaruActorKind.AGENT)
                .effect(NaruAccessEffect.DENY));

        NaruStmtResult r = reportAs(NaruActor.user("alice"), "price set alpha input=1 output=2");
        Assertions.assertEquals(0, r.exitCode(), String.valueOf(r.errorValue()));
        Assertions.assertNotNull(service().priceTable().get("alpha"));
    }

    /** A stub task that reports {@code actor} from {@code currentActor()}. */
    private NaruStmtResult reportAs(NaruActor actor, String argument) {
        NaruModelRequest request = request(List.of(), List.of());
        NaruTask t = (NaruTask) Proxy.newProxyInstance(
                NaruTask.class.getClassLoader(),
                new Class<?>[]{NaruTask.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "session" -> session;
                    case "currentActor" -> actor;
                    case "log" -> null;
                    case "toString" -> "stub-task";
                    case "hashCode" -> 0;
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError(
                            "the /budget directive asked the task for something it should not need: "
                                    + method.getName());
                });
        NaruDirectiveCallContext ctx = new NaruDirectiveCallContextImpl("budget", argument, t);
        return new NaruBudgetDirective().execute(ctx);
    }

    /**
     * A task whose labels are a real mutable map, so a test can see {@code /budget label}
     * mutate them in place.
     */
    private static NaruTask labelTask(Map<String, String> labels) {
        return (NaruTask) Proxy.newProxyInstance(
                NaruTask.class.getClassLoader(),
                new Class<?>[]{NaruTask.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setLabel" -> {
                        labels.put((String) args[0], (String) args[1]);
                        yield proxy;
                    }
                    case "removeLabel" -> {
                        labels.remove((String) args[0]);
                        yield proxy;
                    }
                    case "clearLabels" -> {
                        labels.clear();
                        yield proxy;
                    }
                    case "labels" -> java.util.Collections.unmodifiableMap(labels);
                    case "session" -> null;
                    case "log" -> null;
                    case "toString" -> "label-task";
                    case "hashCode" -> 0;
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError(
                            "/budget label asked the task for something it should not need: "
                                    + method.getName());
                });
    }

    private static NaruStmtResult label(NaruTask task, String argument) {
        NaruDirectiveCallContext ctx = new NaruDirectiveCallContextImpl("budget", argument, task);
        return new NaruBudgetDirective().execute(ctx);
    }

    @Test
    public void labelSubcommandSetsListsAndUnsetsLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        NaruTask task = labelTask(labels);

        NaruStmtResult set = label(task, "label set module=foo tier=pro");
        Assertions.assertEquals(0, set.exitCode(), String.valueOf(set.errorValue()));
        Assertions.assertEquals("foo", labels.get("module"));
        Assertions.assertEquals("pro", labels.get("tier"));
        Assertions.assertTrue(String.valueOf(set.successValue()).contains("module=foo"),
                "the command should echo the resulting labels:\n" + set.successValue());

        NaruStmtResult unset = label(task, "label unset module");
        Assertions.assertEquals(0, unset.exitCode(), String.valueOf(unset.errorValue()));
        Assertions.assertFalse(labels.containsKey("module"));
        Assertions.assertEquals("pro", labels.get("tier"), "unset must remove only the named key");

        NaruStmtResult list = label(task, "label list");
        Assertions.assertEquals(0, list.exitCode(), String.valueOf(list.errorValue()));
        Assertions.assertTrue(String.valueOf(list.successValue()).contains("tier=pro"));

        NaruStmtResult clear = label(task, "label clear");
        Assertions.assertEquals(0, clear.exitCode(), String.valueOf(clear.errorValue()));
        Assertions.assertTrue(labels.isEmpty());
    }

    @Test
    public void labelSetWithoutAKeyValuePairIsRejected() {
        Map<String, String> labels = new LinkedHashMap<>();
        NaruTask task = labelTask(labels);

        NaruStmtResult bad = label(task, "label set noequals");
        Assertions.assertNotEquals(0, bad.exitCode(),
                "a bare word is not a key=value and must be reported, not silently dropped");
        Assertions.assertTrue(labels.isEmpty());
    }

    /**
     * A model whose context size nobody reported divides by zero in the report, which
     * used to print {@code Infinity%}.
     */
    @Test
    public void reportSurvivesAModelWithNoContextSize() {
        session.fireModelCallUsage(null, new NaruModelKey("test", "sizeless"),
                100, 10, -1, -1, NDuration.ofMillis(5));

        NaruStmtResult result = report(NaruMessage.system("hello"));

        Assertions.assertEquals(0, result.exitCode(),
                "/budget must not fail on a model with unknown context size: " + result.errorValue());
        String out = String.valueOf(result.successValue());
        Assertions.assertFalse(out.contains("NaN"), "no NaN in the report:\n" + out);
        Assertions.assertFalse(out.contains("Infinity"), "no Infinity in the report:\n" + out);
    }

    /**
     * A provider that reports no numbers at all is normal, not a gap in accounting, and
     * printing {@code limit null, used null, remaining null} said otherwise.
     */
    @Test
    public void emptyRateLimitBucketsAreNotPrinted() {
        session.reportProviderRateLimits(new DefaultNaruProviderRateLimitInfo(
                session.uuid(), null, "quiet", Instant.now(),
                List.of(new DefaultNaruRateLimitBucket(NaruRateLimitWindow.MINUTE, null, null, null)),
                List.of(),
                null, null,
                NElement.ofObjectBuilder().build()));

        String out = String.valueOf(report().successValue());
        Assertions.assertFalse(out.contains("limit null"),
                "a bucket with neither limit nor remaining says nothing and should be skipped:\n" + out);
    }

    /**
     * The estimate is opt-in: {@code --explain} must still state what it is measured
     * against, or it is a number with nothing to calibrate it against.
     */
    @Test
    public void explainShowsTheEstimateAgainstWhatWasActuallyReported() {
        session.fireModelCallUsage(null, new NaruModelKey("test", "calibrated"),
                400, 40, -1, -1, NDuration.ofMillis(5));

        String out = String.valueOf(reportWith("--explain").successValue());
        Assertions.assertTrue(out.contains("estimated tokens"),
                "the headline has to say it is an estimate:\n" + out);
        Assertions.assertTrue(out.contains("reported last call"),
                "an estimate with nothing to compare against cannot be trusted:\n" + out);
    }

    /**
     * The estimate is guesswork, so it must not appear unless asked for: the default
     * report is numbers the provider actually reported.
     */
    @Test
    public void theDefaultReportDoesNotGuess() {
        session.fireModelCallUsage(null, new NaruModelKey("test", "calibrated"),
                400, 40, -1, -1, NDuration.ofMillis(5));

        String out = String.valueOf(report().successValue());
        Assertions.assertFalse(out.contains("estimated tokens"),
                "the estimate is opt-in, not part of the default report:\n" + out);
    }

    // ── /budget --explain: catalog vs loaded skill attribution ───────────

    /**
     * The AGENT bucket lumps every non-user source together, which hides the thing that
     * actually grows with skill count: catalog advertisements vs spliced skill bodies.
     */
    @Test
    public void agentBucketSplitsCatalogFromLoadedSkillBodies() {
        NaruBudgetDirective.PromptStats s = estimate(
                NaruMessage.user("advertised skill named home")
                        .setSource(NaruSource.SKILL).setSourceName("catalog:home"),
                NaruMessage.user("the spliced body of a loaded skill with plenty of text")
                        .setSource(NaruSource.SKILL).setSourceName("/x/.naru/skills/loaded/SKILL.md"),
                NaruMessage.user("a skill name nobody resolved")
                        .setSource(NaruSource.SKILL).setSourceName("skills:missing"),
                NaruMessage.user("something the routine extension contributed")
                        .setSource(NaruSource.AGENT).setSourceName("routine"));

        Assertions.assertEquals(1, s.skillCatalogMessages);
        Assertions.assertEquals(2, s.skillLoadedMessages,
                "a loaded body and a missing skill both belong to the file side");
        Assertions.assertEquals(1, s.agentOtherMessages);
        Assertions.assertTrue(s.skillCatalogTokens() > 0);
        Assertions.assertTrue(s.skillLoadedTokens() > 0);
        Assertions.assertTrue(s.agentOtherTokens() > 0);
    }

    @Test
    public void aRequestWithoutSkillSourceHasNothingToSplit() {
        NaruBudgetDirective.PromptStats s = estimate(
                NaruMessage.system("brief"),
                NaruMessage.user("plain user text"));

        Assertions.assertEquals(0, s.skillCatalogMessages);
        Assertions.assertEquals(0, s.skillLoadedMessages);
        Assertions.assertEquals(0, s.agentOtherMessages);
    }

    @Test
    public void explainReportsTheSplitAndRunsClean() {
        NaruDirectiveCallContext ctx = new NaruDirectiveCallContextImpl("budget", "--explain", task(
                NaruMessage.user("catalog row")
                        .setSource(NaruSource.SKILL).setSourceName("catalog:readme"),
                NaruMessage.user("loaded body text")
                        .setSource(NaruSource.SKILL).setSourceName("/x/.naru/skills/readme/SKILL.md"),
                NaruMessage.system("brief")));
        NaruStmtResult result = new NaruBudgetDirective().execute(ctx);

        Assertions.assertEquals(0, result.exitCode(),
                "/budget --explain must not fail on a request with skill messages: " + result.errorValue());
        String out = String.valueOf(result.successValue());
        Assertions.assertTrue(out.contains("skill contribution"),
                "the split is the point of --explain:\n" + out);
        Assertions.assertTrue(out.contains("catalog"),
                "advertised skills must be a named row:\n" + out);
        Assertions.assertTrue(out.contains("loaded bodies"),
                "spliced skill bodies must be a named row:\n" + out);
        Assertions.assertTrue(out.contains("per model"),
                "the per-model spend is part of --explain:\n" + out);
    }
}
