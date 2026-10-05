package net.thevpc.naru.ext.budget;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;

/**
 * Guards the arithmetic in {@link NaruBudgetServiceImpl#accumulate}, now that the
 * feature lives in the naru-budget extension rather than the core.
 *
 * <p>This path used to assign rather than add, so a model that had served ten
 * calls reported the token counts of the tenth alone. Nothing about that was
 * visible in a single-call test, which is why it survived; the assertions here
 * all use two or more calls for that reason.
 */
public class NaruBudgetAccumulationTest {

    private static final NaruModelConfig CONFIG = new NaruModelConfig("openrouter", "some-model");
    private static final NaruModelKey MODEL = CONFIG.key();

    /**
     * Minimal session. Only {@code registry().protocol(...)} is ever consulted,
     * and only to discover a context size; answering "no such protocol" skips
     * that defaulting without needing a real agent.
     */
    private static NaruSession stubSession() {
        return (NaruSession) Proxy.newProxyInstance(
                NaruSession.class.getClassLoader(),
                new Class<?>[]{NaruSession.class},
                (proxy, method, args) -> {
                    if ("registry".equals(method.getName())) {
                        return Proxy.newProxyInstance(
                                NaruSession.class.getClassLoader(),
                                new Class<?>[]{net.thevpc.naru.api.registry.NaruRegistry.class},
                                (p2, m2, a2) -> "protocol".equals(m2.getName())
                                        ? NOptional.ofEmpty()
                                        : null);
                    }
                    if ("toString".equals(method.getName())) {
                        return "stub-session";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return 0;
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    return null;
                });
    }

    private static NaruTokenTransaction tx(long prompt, long completion) {
        return new NaruTokenTransaction("s1", null, CONFIG, prompt, completion,
                Instant.now(), NDuration.ofMillis(10));
    }

    private static NaruTokenTransaction cacheTx(long prompt, long completion, long write, long read) {
        return new NaruTokenTransaction("s1", null, CONFIG, prompt, completion,
                write, read, Instant.now(), NDuration.ofMillis(10));
    }

    @Test
    public void promptAndCompletionTokensAccumulateAcrossCalls() {
        NaruSession session = stubSession();
        NaruBudgetServiceImpl svc = new NaruBudgetServiceImpl(session);

        svc.trackTransaction(tx(100, 10));
        svc.trackTransaction(tx(200, 20));
        svc.trackTransaction(tx(300, 30));

        NaruModelBudgetStats stats = svc.findModelBudgetStats(MODEL, null);
        Assertions.assertEquals(600, stats.getPromptTokens(),
                "prompt tokens must be the sum across calls, not the last call");
        Assertions.assertEquals(60, stats.getCompletionTokens());
        Assertions.assertEquals(3, stats.getCallsCount());
    }

    /**
     * The exact numbers a three-call run must produce.
     *
     * <p>{@code contextUsage} used to be computed as cumulative-completion plus this call's
     * prompt, so each call added the running total back into the request size. A 9-call
     * session reported 81974 tokens against 75029 actually spent. Both fields are now
     * per-call values: {@code contextUsage} is the last call's size, {@code peakContextUsage}
     * the largest one seen, and only {@code totalTokens} accumulates.
     */
    @Test
    public void contextUsageIsPerCallWhileTotalTokensAccumulates() {
        NaruSession session = stubSession();
        NaruBudgetServiceImpl svc = new NaruBudgetServiceImpl(session);

        svc.trackTransaction(tx(100, 10));
        svc.trackTransaction(tx(200, 20));
        svc.trackTransaction(tx(300, 30));

        NaruModelBudgetStats stats = svc.findModelBudgetStats(MODEL, null);
        Assertions.assertEquals(600, stats.getPromptTokens());
        Assertions.assertEquals(60, stats.getCompletionTokens());
        Assertions.assertEquals(660, stats.getTotalTokens(),
                "total is the running sum of prompt plus completion");
        Assertions.assertEquals(330, stats.getContextUsage(),
                "context usage is the size of the last call, not a total that absorbs history");
        Assertions.assertEquals(330, stats.getPeakContextUsage());
        Assertions.assertEquals(3, stats.getCallsCount());
    }

    /**
     * A later, smaller call must not pull the peak down: 400 then 150.
     */
    @Test
    public void peakContextUsageKeepsTheLargestCall() {
        NaruSession session = stubSession();
        NaruBudgetServiceImpl svc = new NaruBudgetServiceImpl(session);

        svc.trackTransaction(tx(300, 100));
        svc.trackTransaction(tx(100, 50));

        NaruModelBudgetStats stats = svc.findModelBudgetStats(MODEL, null);
        Assertions.assertEquals(400, stats.getPeakContextUsage(),
                "the peak is the largest single call and must not shrink");
        Assertions.assertEquals(150, stats.getContextUsage(),
                "context usage follows the most recent call, larger or smaller");
        Assertions.assertEquals(550, stats.getTotalTokens());
    }

    @Test
    public void totalTokensGrowsWithEveryCall() {
        NaruSession session = stubSession();
        NaruBudgetServiceImpl svc = new NaruBudgetServiceImpl(session);

        svc.trackTransaction(tx(100, 10));
        long afterFirst = svc.findModelBudgetStats(MODEL, null).getTotalTokens();
        svc.trackTransaction(tx(100, 10));
        long afterSecond = svc.findModelBudgetStats(MODEL, null).getTotalTokens();

        Assertions.assertTrue(afterSecond > afterFirst,
                "a second identical call must add spend, not replace it");
    }

    @Test
    public void cacheTokensAccumulateAndDoNotGoBackwards() {
        NaruSession session = stubSession();
        NaruBudgetServiceImpl svc = new NaruBudgetServiceImpl(session);

        // a cold turn: everything is a cache write
        svc.trackTransaction(cacheTx(1000, 20, 1000, 0));
        // a warm turn: most of the prefix is a cache read
        svc.trackTransaction(cacheTx(1000, 20, 100, 900));

        NaruModelBudgetStats stats = svc.findModelBudgetStats(MODEL, null);
        Assertions.assertEquals(1100, stats.getCacheWriteTokens());
        Assertions.assertEquals(900, stats.getCacheReadTokens());
        Assertions.assertTrue(stats.getCacheHitRatio() > 0 && stats.getCacheHitRatio() < 1,
                "a mixed workload should report a partial hit ratio, got " + stats.getCacheHitRatio());
    }

    /**
     * Providers that do not report cache accounting send -1. That must not
     * subtract from a running total established by providers that do.
     */
    @Test
    public void unreportedCacheTokensAreTreatedAsZero() {
        NaruSession session = stubSession();
        NaruBudgetServiceImpl svc = new NaruBudgetServiceImpl(session);

        svc.trackTransaction(cacheTx(1000, 20, 1000, 0));
        svc.trackTransaction(tx(50, 5));

        NaruModelBudgetStats stats = svc.findModelBudgetStats(MODEL, null);
        Assertions.assertEquals(1000, stats.getCacheWriteTokens(),
                "a -1 from a non-reporting provider must not wipe the running total");
        Assertions.assertEquals(0, stats.getCacheReadTokens());
    }
}
