package net.thevpc.naru.ext.budget;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.ext.budget.store.DefaultNaruBudgetStoreQuery;
import net.thevpc.nuts.time.NDuration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;

/**
 * Guards the money arithmetic in {@link NaruAccumulatorBuilder}: the cache split,
 * the {@code -1} clamp, unpriced and approximate accounting, and the rule that a
 * price set after a call does not rewrite that call's cost.
 *
 * <p>Prices are per 1M tokens, so every expected value below is
 * {@code tokens * price / 1_000_000} computed by hand.
 */
public class NaruCostMathTest {

    private static final NaruModelConfig CONFIG = new NaruModelConfig("anthropic", "claude-sonnet-4");
    private static final NaruModelKey MODEL = CONFIG.key();

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static NaruTokenTransaction tx(long prompt, long completion, long cacheWrite, long cacheRead,
                                           NaruUnitPrice price) {
        return new NaruTokenTransaction(null, "s1", null, CONFIG, prompt, completion,
                cacheWrite, cacheRead, price, Instant.now(), NDuration.ofMillis(1));
    }

    private static NaruModelBudgetStats statsOf(NaruTokenTransaction... txs) {
        NaruAccumulatorBuilder b = new NaruAccumulatorBuilder();
        for (NaruTokenTransaction t : txs) {
            b.accumulate(t);
        }
        return b.build();
    }

    private static NaruUnitPrice priced(String input, String output, String read, String write) {
        return NaruUnitPrice.parse(NaruPriceSource.USER, null, null, input, output, read, write, new ArrayList<>());
    }

    @Test
    public void noCacheSpendingIsPromptTimesInputPlusCompletionTimesOutput() {
        // 1M prompt at 3/1M = 3; 1M completion at 15/1M = 15
        NaruModelBudgetStats stats = statsOf(
                tx(1_000_000, 1_000_000, -1, -1, priced("3", "15", null, null)));
        Assertions.assertEquals(0, bd("3").compareTo(stats.spending().input()));
        Assertions.assertEquals(0, bd("15").compareTo(stats.spending().output()));
        Assertions.assertEquals(0, bd("18").compareTo(stats.spending().total()));
        Assertions.assertEquals(0, stats.unpricedCalls());
        Assertions.assertEquals(0, stats.cacheReportedCalls(), "no cache accounting was reported");
    }

    @Test
    public void cacheReadIsDiscountedAndRemovedFromTheFullRateRemainder() {
        // 1000 prompt, 600 served from cache at 0.3/1M, 400 fresh at 3/1M, 100 output at 15/1M
        // input = (3*400 + 0.3*600)/1e6 = 1380/1e6 = 0.00138 ; output = 1500/1e6 = 0.0015
        NaruModelBudgetStats stats = statsOf(
                tx(1000, 100, 0, 600, priced("3", "15", "0.3", null)));
        Assertions.assertEquals(0, bd("0.00138").compareTo(stats.spending().input()));
        Assertions.assertEquals(0, bd("0.0015").compareTo(stats.spending().output()));
        Assertions.assertEquals(1, stats.cacheReportedCalls());
        Assertions.assertEquals(600, stats.cacheReadTokens());
        Assertions.assertFalse(stats.isApproximate(), "cache-read price was explicit");
    }

    @Test
    public void cacheWriteUsesItsOwnRate() {
        // 1M prompt, 200k written at 3.75/1M, 800k fresh at 3/1M
        // input = (3*800000 + 3.75*200000)/1e6 = (2_400_000 + 750_000)/1e6 = 3.15
        NaruModelBudgetStats stats = statsOf(
                tx(1_000_000, 0, 200_000, 0, priced("3", "15", null, "3.75")));
        Assertions.assertEquals(0, bd("3.15").compareTo(stats.spending().input()));
        Assertions.assertFalse(stats.isApproximate());
    }

    @Test
    public void unreportedCacheSentinelsAreClampedNotSubtracted() {
        // -1 must never reach arithmetic: fresh stays prompt, and no cache is claimed
        NaruModelBudgetStats stats = statsOf(
                tx(1_000_000, 0, -1, -1, priced("3", "15", null, null)));
        Assertions.assertEquals(0, bd("3").compareTo(stats.spending().input()));
        Assertions.assertEquals(0, stats.cacheReadTokens());
        Assertions.assertEquals(0, stats.cacheWriteTokens());
    }

    @Test
    public void aClampProtectsAgainstCacheExceedingPrompt() {
        // a malformed report where the cache split exceeds the prompt must not
        // produce a negative full-rate portion
        NaruModelBudgetStats stats = statsOf(
                tx(1000, 0, 0, 5000, priced("3", "15", "0.3", null)));
        Assertions.assertEquals(0, bd("0.0015").compareTo(stats.spending().input()),
                "fresh clamped to 0, so only the cache read is billed");
    }

    @Test
    public void unpricedCallsContributeNoMoneyButAreCounted() {
        NaruModelBudgetStats stats = statsOf(
                tx(1000, 100, -1, -1, null));
        Assertions.assertEquals(1, stats.unpricedCalls());
        Assertions.assertEquals(0, bd("0").compareTo(stats.spending().total()));
    }

    @Test
    public void defaultingCachePricesToInputFlagsTheTotalsApproximate() {
        // cache read reported but no explicit read price: billed at input, flagged
        NaruModelBudgetStats stats = statsOf(
                tx(1000, 0, 0, 600, priced("3", "15", null, null)));
        Assertions.assertEquals(1, stats.approximateCalls());
        Assertions.assertTrue(stats.isApproximate());
    }

    @Test
    public void explicitZeroCachePriceIsNotApproximate() {
        // a user who says the cache read is free has stated a price, not defaulted one
        NaruModelBudgetStats stats = statsOf(
                tx(1000, 0, 0, 600, priced("3", "15", "0", null)));
        Assertions.assertEquals(0, stats.approximateCalls());
        Assertions.assertEquals(0, bd("0.0012").compareTo(stats.spending().input()),
                "(3*400 + 0*600)/1e6");
    }

    @Test
    public void moneyIsExactNotFloatingPoint() {
        // 3 * 1 token = 0.000003 exactly; a double would drift
        NaruModelBudgetStats stats = statsOf(
                tx(1, 0, -1, -1, priced("3", "15", null, null)));
        Assertions.assertEquals(new BigDecimal("0.000003"), stats.spending().input());
    }

    @Test
    public void cacheRatioIsComputedOverOnlyReportingCalls() {
        // one call reports cache (600/1000), one reports nothing; the ratio must
        // reflect only the reporting call, and be marked partial
        NaruModelBudgetStats stats = statsOf(
                tx(1000, 0, 0, 600, priced("3", "15", "0.3", null)),
                tx(1000, 0, -1, -1, priced("3", "15", "0.3", null)));
        Assertions.assertEquals(2, stats.calls());
        Assertions.assertEquals(1, stats.cacheReportedCalls());
        Assertions.assertTrue(stats.cacheRatioPartial());
        Assertions.assertEquals(0.6, stats.cacheHitRatio(), 1e-9);
    }

    // ── snapshot semantics through the service ───────────────────────────────

    @Test
    public void aPriceSetAfterACallDoesNotRewriteItsCost() {
        NaruSession session = stubSession();
        NaruBudgetServiceImpl svc = new NaruBudgetServiceImpl(session, null);

        // tracked while unpriced
        svc.trackTransaction(new NaruTokenTransaction("s1", null, CONFIG, 1_000_000, 0,
                Instant.now(), NDuration.ofMillis(1)));

        // price appears afterwards
        svc.priceTable().set("anthropic", NaruUnitPrice.user(bd("3"), bd("15"), null, null));

        NaruModelBudgetStats past = svc.findModelBudgetStats(new DefaultNaruBudgetStoreQuery().whereModel(MODEL));
        Assertions.assertEquals(1, past.unpricedCalls());
        Assertions.assertEquals(0, bd("0").compareTo(past.spending().total()),
                "the earlier call was made with no price; it must stay unpriced");

        // a call made now is priced
        svc.trackTransaction(new NaruTokenTransaction("s1", null, CONFIG, 1_000_000, 0,
                Instant.now(), NDuration.ofMillis(1)));
        NaruModelBudgetStats now = svc.findModelBudgetStats(new DefaultNaruBudgetStoreQuery().whereModel(MODEL));
        Assertions.assertEquals(2, now.calls());
        Assertions.assertEquals(1, now.unpricedCalls());
        Assertions.assertEquals(0, bd("3").compareTo(now.spending().total()));
    }

    /**
     * Minimal session: the service only consults {@code registry().protocol(...)}
     * and {@code findModel(...)} when it defaults a context size, and answering
     * "no such protocol" skips that without needing a real agent.
     */
    private static NaruSession stubSession() {
        return (NaruSession) java.lang.reflect.Proxy.newProxyInstance(
                NaruSession.class.getClassLoader(),
                new Class<?>[]{NaruSession.class},
                (proxy, method, args) -> {
                    if ("registry".equals(method.getName())) {
                        return java.lang.reflect.Proxy.newProxyInstance(
                                NaruSession.class.getClassLoader(),
                                new Class<?>[]{net.thevpc.naru.api.registry.NaruRegistry.class},
                                (p2, m2, a2) -> "protocol".equals(m2.getName())
                                        ? net.thevpc.nuts.util.NOptional.ofEmpty()
                                        : null);
                    }
                    if ("findModel".equals(method.getName())) {
                        return net.thevpc.nuts.util.NOptional.of(new NaruModelConfig(
                                "some-model", "anthropic", "claude-sonnet-4", 0L,
                                null, null, null, null, null));
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
}
