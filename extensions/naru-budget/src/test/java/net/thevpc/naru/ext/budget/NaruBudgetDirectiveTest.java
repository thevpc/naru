package net.thevpc.naru.ext.budget;

import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.model.DefaultNaruProviderRateLimitInfo;
import net.thevpc.naru.api.model.DefaultNaruRateLimitBucket;
import net.thevpc.naru.api.model.NaruMessage;
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
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.time.NDuration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
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
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Throwable e) {
            try {
                NWorkspace ws = Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    @BeforeEach
    public void setUp() {
        NaruAgentImpl agent = new NaruAgentImpl();
        NPath dir = NPath.ofTempFolder("naru-budget-directive-" + System.nanoTime());
        agent.projectDirectory(dir);
        session = new NaruSessionImpl(agent, dir, null, true, null, null, null, null);
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

    /**
     * A model whose context size nobody reported divides by zero in the report, which
     * used to print {@code Infinity%}.
     */
    @Test
    public void reportSurvivesAModelWithNoContextSize() {
        session.fireModelCallUsage(new NaruModelKey("test", "sizeless"),
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
     * The report must state what the estimate is measured against, or it is a number
     * with nothing to calibrate it against.
     */
    @Test
    public void reportShowsTheEstimateAgainstWhatWasActuallyReported() {
        session.fireModelCallUsage(new NaruModelKey("test", "calibrated"),
                400, 40, -1, -1, NDuration.ofMillis(5));

        String out = String.valueOf(report().successValue());
        Assertions.assertTrue(out.contains("estimated tokens"),
                "the headline has to say it is an estimate:\n" + out);
        Assertions.assertTrue(out.contains("reported last call"),
                "an estimate with nothing to compare against cannot be trusted:\n" + out);
    }

    // ── /stats: catalog vs loaded skill attribution (WP5) ────────────────

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
    public void statsDirectiveReportsTheSplitAndRunsClean() {
        NaruDirectiveCallContext ctx = new NaruDirectiveCallContextImpl("stats", null, task(
                NaruMessage.user("catalog row")
                        .setSource(NaruSource.SKILL).setSourceName("catalog:readme"),
                NaruMessage.user("loaded body text")
                        .setSource(NaruSource.SKILL).setSourceName("/x/.naru/skills/readme/SKILL.md"),
                NaruMessage.system("brief")));
        NaruStmtResult result = new NaruStatsDirective().execute(ctx);

        Assertions.assertEquals(0, result.exitCode(),
                "/stats must not fail on a request with skill messages: " + result.errorValue());
        String out = String.valueOf(result.successValue());
        Assertions.assertTrue(out.contains("skill contribution"),
                "the split is the point of /stats:\n" + out);
        Assertions.assertTrue(out.contains("catalog"),
                "advertised skills must be a named row:\n" + out);
        Assertions.assertTrue(out.contains("loaded bodies"),
                "spliced skill bodies must be a named row:\n" + out);
        Assertions.assertTrue(out.contains("per model"),
                "the per-model spend is part of /stats:\n" + out);
    }
}