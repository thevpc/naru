package net.thevpc.naru.ext.budget;

import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.DefaultNaruProviderRateLimitInfo;
import net.thevpc.naru.api.model.DefaultNaruRateLimitBucket;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruRateLimitWindow;
import net.thevpc.naru.ext.budget.store.DefaultNaruBudgetStoreQuery;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.time.NDuration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

/**
 * Covers the extension half of budget: that a session gets its own totals, that the
 * listener is registered exactly once, and that provider-reported numbers land in the store.
 */
public class NaruBudgetExtensionTest {

    private static final NaruModelKey MODEL_A = new NaruModelKey("test", "a");
    private static final NaruModelKey MODEL_B = new NaruModelKey("test", "b");

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();    }

    private static NaruSessionImpl newSession(String name) {
        // unique per call: spend is now durable, so a fixed name would accumulate
        // transactions across repeated runs and make every total grow
        return newSessionAt(NPath.ofTempFolder("naru-budget-" + name + "-" + System.nanoTime()));
    }

    private static NaruSessionImpl newSessionAt(NPath projectDir) {
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(projectDir);
        // configureDefaults=true: the extension is found through the same SPI lookup the
        // /budget directive uses, so this also covers registration
        return (NaruSessionImpl) agent.newSession()
                .directory(projectDir)
                .interaction(null)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
    }

    private static void call(NaruSessionImpl session, NaruModelKey model, long prompt, long completion) {
        session.fireModelCallUsage(null, model, prompt, completion, -1, -1, NDuration.ofMillis(10));
    }

    @Test
    public void usageReportedOnTheSessionIsVisibleThroughTheExtension() {
        NaruSessionImpl session = newSession("visible");
        NaruBudgetService svc = NaruBudgetExtension.budget(session);

        call(session, MODEL_A, 100, 10);
        call(session, MODEL_A, 200, 20);

        NaruModelBudgetStats stats = svc.findModelBudgetStats(new DefaultNaruBudgetStoreQuery().whereModel(MODEL_A));
        Assertions.assertEquals(300, stats.promptTokens());
        Assertions.assertEquals(30, stats.completionTokens());
        Assertions.assertEquals(2, stats.calls());
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

        Assertions.assertEquals(500, NaruBudgetExtension.budget(one).findModelBudgetStats(new DefaultNaruBudgetStoreQuery().whereModel(MODEL_A)).promptTokens());
        Assertions.assertEquals(0, NaruBudgetExtension.budget(two).findModelBudgetStats(new DefaultNaruBudgetStoreQuery().whereModel(MODEL_A)).promptTokens(),
                "a second session must start at zero, not inherit the first session's spend");
    }

    @Test
    public void cacheTokensFromTheCoreSeamReachTheTotals() {
        NaruSessionImpl session = newSession("cache");
        NaruBudgetService svc = NaruBudgetExtension.budget(session);

        session.fireModelCallUsage(null, MODEL_A, 1000, 20, 1000, 0, NDuration.ofMillis(10));
        session.fireModelCallUsage(null, MODEL_A, 1000, 20, 100, 900, NDuration.ofMillis(10));

        NaruModelBudgetStats stats = svc.findModelBudgetStats(new DefaultNaruBudgetStoreQuery().whereModel(MODEL_A));
        Assertions.assertEquals(1100, stats.cacheWriteTokens());
        Assertions.assertEquals(900, stats.cacheReadTokens());
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

        NaruModelBudgetStats stats = NaruBudgetExtension.budget(session).findModelBudgetStats(new DefaultNaruBudgetStoreQuery().whereModel(MODEL_A));
        Assertions.assertEquals(400, stats.promptTokens());
        Assertions.assertEquals(1, stats.calls());
    }

    @Test
    public void modelsAreTrackedSeparately() {
        NaruSessionImpl session = newSession("models");
        NaruBudgetService svc = NaruBudgetExtension.budget(session);

        call(session, MODEL_A, 100, 1);
        call(session, MODEL_B, 700, 7);

        Assertions.assertEquals(100, svc.findModelBudgetStats(new DefaultNaruBudgetStoreQuery().whereModel(MODEL_A)).promptTokens());
        Assertions.assertEquals(700, svc.findModelBudgetStats(new DefaultNaruBudgetStoreQuery().whereModel(MODEL_B)).promptTokens());
        Assertions.assertEquals(2, svc.findByModelBudgetStats(null).size());
    }

    @Test
    public void providerRateLimitsAreRecordedAndReplacedPerProvider() {
        NaruSessionImpl session = newSession("ratelimits");
        NaruBudgetService svc = NaruBudgetExtension.budget(session);

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
     * Spend is durable, at the project level. A reloaded session sees what the project has
     * cost rather than starting from zero: {@code /budget} answers "what has this project
     * cost", not "what has this JVM run cost". The session still saves no state of its own —
     * transactions live in the agent's store, and a session persists only labels.
     */
    @Test
    public void spendPersistsAtTheProjectLevelAcrossSessions() {
        NPath projectDir = NPath.ofTempFolder("naru-budget-persist-" + System.nanoTime());
        NaruSessionImpl first = newSessionAt(projectDir);
        call(first, MODEL_A, 800, 80);

        NaruBudgetExtension ext = first.registry().extension(NaruBudgetExtension.NAME, NaruBudgetExtension.class)
                .orElseThrow(() -> new AssertionError("budget extension should be installed"));
        Assertions.assertNull(ext.save(first),
                "the session has no state of its own to save; the agent owns the transactions");

        // a second session over the same project dir is what a reload amounts to
        NaruSessionImpl reloaded = newSessionAt(projectDir);
        Assertions.assertEquals(800,
                NaruBudgetExtension.budget(reloaded).findModelBudgetStats(new DefaultNaruBudgetStoreQuery().whereModel(MODEL_A)).promptTokens(),
                "a reloaded session must see the project's persisted spend");
    }

    @Test
    public void theExtensionIsDiscoverableThroughTheRegistry() {
        NaruSessionImpl session = newSession("registry");
        Assertions.assertTrue(
                session.registry().extension(NaruBudgetExtension.NAME, NaruBudgetExtension.class).isPresent(),
                "the /stats directive resolves the extension this way, so it must be registered");
        Assertions.assertDoesNotThrow(() -> NaruBudgetExtension.budget(session));
    }

    /**
     * Registration moved out of naru-tools-llm and into this module's own provider,
     * and the command was renamed with it: what used to be {@code /stat} reports a
     * budget, so it is {@code /budget} now. This assertion says the directive did not
     * simply disappear in that move -- it used to be checking for a name the code had
     * stopped answering to, and failing on every run since the rename.
     */
    @Test
    public void theBudgetDirectiveIsRegisteredUnderItsCurrentName() {
        NaruSessionImpl session = newSession("directive");

        Assertions.assertTrue(session.registry().directives().containsKey("budget"),
                "/budget is the name the directive answers to and must be registered");
        Assertions.assertTrue(session.registry().findDirective("budget").isPresent(),
                "and it must resolve the way a user reaches it");
    }
}
