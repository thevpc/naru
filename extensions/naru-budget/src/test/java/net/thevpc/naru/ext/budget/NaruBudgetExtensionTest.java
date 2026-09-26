package net.thevpc.naru.ext.budget;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.model.DefaultNaruProviderRateLimitInfo;
import net.thevpc.naru.api.model.DefaultNaruRateLimitBucket;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruRateLimitWindow;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.time.NDuration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

/**
 * Covers the extension half of metering: that a session gets its own totals, that the
 * listener is registered exactly once, and that provider-reported numbers land in the store.
 */
public class NaruBudgetExtensionTest {

    private static final NaruModelKey MODEL_A = new NaruModelKey("test", "a");
    private static final NaruModelKey MODEL_B = new NaruModelKey("test", "b");

    @BeforeAll
    public static void setUpWorkspace() {
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            try {
                NWorkspace ws = Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static NaruSessionImpl newSession(String name) {
        NaruAgent agent = new NaruAgentImpl();
        agent.setProjectDirectory(NPath.ofTempFolder("naru-budget-" + name));
        // configureDefaults=true: the extension is found through the same SPI lookup the
        // /stats directive uses, so this also covers registration
        return new NaruSessionImpl(agent, agent.getProjectDirectory(), true, null, null, null, null);
    }

    private static void call(NaruSessionImpl session, NaruModelKey model, long prompt, long completion) {
        session.fireModelCallUsage(model, prompt, completion, -1, -1, NDuration.ofMillis(10));
    }

    @Test
    public void usageReportedOnTheSessionIsVisibleThroughTheExtension() {
        NaruSessionImpl session = newSession("visible");
        NaruMeteringService svc = NaruBudgetExtension.metering(session);

        call(session, MODEL_A, 100, 10);
        call(session, MODEL_A, 200, 20);

        NaruModelStats stats = svc.findModelStats(MODEL_A, null);
        Assertions.assertEquals(300, stats.getPromptTokens());
        Assertions.assertEquals(30, stats.getCompletionTokens());
        Assertions.assertEquals(2, stats.getCallsCount());
    }

    /**
     * The bug that motivated scoping this per session. Metering used to live on the agent,
     * keyed only by model and user, so one session's /stats reported another's spend.
     */
    @Test
    public void totalsDoNotLeakBetweenSessions() {
        NaruSessionImpl one = newSession("leak-one");
        NaruSessionImpl two = newSession("leak-two");

        call(one, MODEL_A, 500, 50);

        Assertions.assertEquals(500, NaruBudgetExtension.metering(one).findModelStats(MODEL_A, null).getPromptTokens());
        Assertions.assertEquals(0, NaruBudgetExtension.metering(two).findModelStats(MODEL_A, null).getPromptTokens(),
                "a second session must start at zero, not inherit the first session's spend");
    }

    @Test
    public void cacheTokensFromTheCoreSeamReachTheTotals() {
        NaruSessionImpl session = newSession("cache");
        NaruMeteringService svc = NaruBudgetExtension.metering(session);

        session.fireModelCallUsage(MODEL_A, 1000, 20, 1000, 0, NDuration.ofMillis(10));
        session.fireModelCallUsage(MODEL_A, 1000, 20, 100, 900, NDuration.ofMillis(10));

        NaruModelStats stats = svc.findModelStats(MODEL_A, null);
        Assertions.assertEquals(1100, stats.getCacheWriteTokens());
        Assertions.assertEquals(900, stats.getCacheReadTokens());
    }

    /**
     * A second open() would register a second listener, and every call would then be
     * counted twice. Doubling a spend figure is worse than losing it.
     */
    @Test
    public void reopeningTheSameSessionDoesNotDoubleCount() {
        NaruSessionImpl session = newSession("double");
        NaruBudgetExtension ext = session.registry()
                .extension(NaruBudgetExtension.NAME, NaruBudgetExtension.class)
                .orElseThrow(() -> new AssertionError("budget extension should be installed"));

        ext.open(session);
        ext.open(session);

        call(session, MODEL_A, 400, 40);

        NaruModelStats stats = NaruBudgetExtension.metering(session).findModelStats(MODEL_A, null);
        Assertions.assertEquals(400, stats.getPromptTokens());
        Assertions.assertEquals(1, stats.getCallsCount());
    }

    @Test
    public void modelsAreTrackedSeparately() {
        NaruSessionImpl session = newSession("models");
        NaruMeteringService svc = NaruBudgetExtension.metering(session);

        call(session, MODEL_A, 100, 1);
        call(session, MODEL_B, 700, 7);

        Assertions.assertEquals(100, svc.findModelStats(MODEL_A, null).getPromptTokens());
        Assertions.assertEquals(700, svc.findModelStats(MODEL_B, null).getPromptTokens());
        Assertions.assertEquals(2, svc.findModelStats().size());
    }

    @Test
    public void providerRateLimitsAreRecordedAndReplacedPerProvider() {
        NaruSessionImpl session = newSession("ratelimits");
        NaruMeteringService svc = NaruBudgetExtension.metering(session);

        session.reportProviderRateLimits(limits(session, 100, "corr-1"));
        Assertions.assertEquals(1, svc.findProviderRateLimitInfos().size());

        // a later report from the same provider supersedes the earlier one rather than
        // piling up a list of stale snapshots
        session.reportProviderRateLimits(limits(session, 40, "corr-2"));
        List<net.thevpc.naru.api.model.NaruProviderRateLimitInfo> infos = svc.findProviderRateLimitInfos();
        Assertions.assertEquals(1, infos.size());
        Assertions.assertEquals("corr-2", infos.get(0).correlationId().orElse(null));
    }

    private static DefaultNaruProviderRateLimitInfo limits(NaruSessionImpl session, int limit, String corr) {
        return new DefaultNaruProviderRateLimitInfo(
                session.uuid(), null, "test", Instant.now(),
                List.of(new DefaultNaruRateLimitBucket(NaruRateLimitWindow.MINUTE, limit, limit, null)),
                List.of(), corr, null, NElement.ofObjectBuilder().build());
    }

    /**
     * Spend is intentionally not durable. Restoring yesterday's totals into today's report
     * would overstate current cost, so a reloaded session starts clean and no state file is
     * written.
     */
    @Test
    public void stateIsNotPersistedAndAReloadedSessionStartsClean() {
        NaruSessionImpl first = newSession("persist");
        call(first, MODEL_A, 800, 80);

        NaruBudgetExtension ext = first.registry().extension(NaruBudgetExtension.NAME, NaruBudgetExtension.class)
                .orElseThrow(() -> new AssertionError("budget extension should be installed"));
        Assertions.assertNull(ext.save(first),
                "returning null must keep the budget extension out of the persistence path");

        // a second session over the same project dir is what a reload amounts to
        NaruSessionImpl reloaded = newSession("persist");
        Assertions.assertEquals(0,
                NaruBudgetExtension.metering(reloaded).findModelStats(MODEL_A, null).getPromptTokens(),
                "a reloaded session must not inherit the previous session's spend");
    }

    @Test
    public void theExtensionIsDiscoverableThroughTheRegistry() {
        NaruSessionImpl session = newSession("registry");
        Assertions.assertTrue(
                session.registry().extension(NaruBudgetExtension.NAME, NaruBudgetExtension.class).isPresent(),
                "the /stats directive resolves the extension this way, so it must be registered");
        Assertions.assertDoesNotThrow(() -> NaruBudgetExtension.metering(session));
    }

    /**
     * Registration for /stats moved out of naru-tools-llm and into this module's own
     * provider, so this is the assertion that the command did not simply disappear.
     * Aliases resolve through findDirective rather than the directives() map, so both
     * spellings are checked the way a user would actually reach them.
     */
    @Test
    public void theStatsDirectiveIsStillRegisteredUnderBothNames() {
        NaruSessionImpl session = newSession("directive");

        Assertions.assertTrue(session.registry().directives().containsKey("stat"),
                "/stat is the primary name and must be registered");
        Assertions.assertTrue(session.registry().findDirective("stats").isPresent(),
                "/stats is the alias and must still resolve after the move");
        Assertions.assertTrue(session.registry().findDirective("stat").isPresent());
    }
}
