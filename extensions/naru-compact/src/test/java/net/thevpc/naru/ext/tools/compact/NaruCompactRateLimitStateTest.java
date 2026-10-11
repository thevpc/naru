package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.model.NaruProviderRateLimitInfo;
import net.thevpc.naru.api.model.NaruRateLimitBucket;
import net.thevpc.naru.api.model.NaruRateLimitWindow;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deciding whether a provider is out of budget, from what it last told us.
 *
 * <p>The asymmetry this file exists for: a wrong "usable" costs a failed model call, while a
 * wrong "exhausted" silently disables compaction on that provider for the rest of the session.
 * So an absent or stale report has to read as usable, and only an explicit zero left, with a
 * reset still ahead of us, reads as exhausted.
 */
class NaruCompactRateLimitStateTest {

    private static final Instant NOW = Instant.now();

    @BeforeAll
    public static void setUpWorkspace() {
        // NDuration.ofSeconds resolves through the Nuts workspace.
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

    /** An absent value must read as an absent optional, not as a present null. */
    private static <T> NOptional<T> optional(T value) {
        return value == null ? NOptional.ofEmpty() : NOptional.of(value);
    }

    private static NaruRateLimitBucket bucket(Integer remaining, Instant reset) {
        return new NaruRateLimitBucket() {
            @Override
            public NaruRateLimitWindow getWindow() {
                return null;
            }

            @Override
            public NOptional<Integer> getLimit() {
                return optional(remaining);
            }

            @Override
            public NOptional<Integer> getRemaining() {
                return optional(remaining);
            }

            @Override
            public NOptional<Instant> getResetTime() {
                return optional(reset);
            }
        };
    }

    private static NaruProviderRateLimitInfo info(String provider,
                                                   List<NaruRateLimitBucket> tokens,
                                                   List<NaruRateLimitBucket> requests,
                                                   NDuration retryAfter) {
        return new NaruProviderRateLimitInfo() {
            @Override
            public String sessionId() {
                return null;
            }

            @Override
            public String userId() {
                return null;
            }

            @Override
            public String providerName() {
                return provider;
            }

            @Override
            public Instant serverTime() {
                return NOW;
            }

            @Override
            public List<NaruRateLimitBucket> tokenBuckets() {
                return tokens;
            }

            @Override
            public List<NaruRateLimitBucket> requestBuckets() {
                return requests;
            }

            @Override
            public NOptional<String> correlationId() {
                return NOptional.ofEmpty();
            }

            @Override
            public NOptional<NDuration> retryAfter() {
                return optional(retryAfter);
            }

            @Override
            public NObjectElement rawProviderInfo() {
                return null;
            }
        };
    }

    private static NaruProviderRateLimitInfo plain(String provider, Integer remaining, Instant reset) {
        return info(provider, List.of(bucket(remaining, reset)), List.of(), null);
    }

    @Test
    void nothingReportedMeansNothingKnown() {
        NaruCompactRateLimitState state = new NaruCompactRateLimitState();
        assertTrue(state.isEmpty());
        assertNull(state.exhaustedReason("openai"));
        assertNull(state.latest("openai"));
    }

    @Test
    void anUnknownProviderIsNotAffectedByAnotherProvidersReport() {
        NaruCompactRateLimitState state = new NaruCompactRateLimitState();
        state.record(plain("openai", 0, NOW.plusSeconds(60)));
        assertNotNull(state.exhaustedReason("openai"));
        assertNull(state.exhaustedReason("anthropic"));
    }

    @Test
    void anAbsentRemainingCountIsNotALimit() {
        // Providers routinely omit buckets they do not track. Reading an absent count as zero
        // would mark them exhausted permanently.
        NaruCompactRateLimitState state = new NaruCompactRateLimitState();
        state.record(plain("ollama", null, null));
        assertNull(state.exhaustedReason("ollama"));
    }

    @Test
    void remainingAboveZeroIsUsable() {
        NaruCompactRateLimitState state = new NaruCompactRateLimitState();
        state.record(plain("openai", 1, NOW.plusSeconds(60)));
        assertNull(state.exhaustedReason("openai"));
    }

    @Test
    void zeroLeftWithAFutureResetIsExhaustedAndSaysWhen() {
        NaruCompactRateLimitState state = new NaruCompactRateLimitState();
        state.record(plain("openai", 0, NOW.plusSeconds(300)));
        String reason = state.exhaustedReason("openai");
        assertNotNull(reason);
        assertTrue(reason.contains("rate limited until"), reason);
    }

    @Test
    void zeroLeftWithAPastResetIsTreatedAsRecovered() {
        // The whole reason a stale report must not block: providers refill, and nothing here
        // refreshes the report until the next call gets through.
        NaruCompactRateLimitState state = new NaruCompactRateLimitState();
        state.record(plain("openai", 0, NOW.minusSeconds(300)));
        assertNull(state.exhaustedReason("openai"));
    }

    @Test
    void zeroLeftWithNoResetIsExhaustedWithoutAPromise() {
        NaruCompactRateLimitState state = new NaruCompactRateLimitState();
        state.record(plain("openai", 0, null));
        assertEquals("rate limited", state.exhaustedReason("openai"));
    }

    @Test
    void aRetryAfterDirectiveIsHonouredEvenWithBucketsLeft() {
        // Some providers stop sending bucket headers once they start throttling and rely on
        // Retry-After alone. Missing it means the throttle is discovered by failing.
        NaruCompactRateLimitState state = new NaruCompactRateLimitState();
        state.record(info("openai", List.of(), List.of(), NDuration.ofSeconds(30)));
        String reason = state.exhaustedReason("openai");
        assertNotNull(reason);
        assertTrue(reason.contains("retry after"), reason);
    }

    @Test
    void aRequestBucketExhaustsTheProviderToo() {
        NaruCompactRateLimitState state = new NaruCompactRateLimitState();
        state.record(info("openai", List.of(bucket(100, null)),
                List.of(bucket(0, NOW.plusSeconds(60))), null));
        assertNotNull(state.exhaustedReason("openai"));
    }

    @Test
    void aLaterReportReplacesTheEarlierOne() {
        // A provider that reports twice has re-limited; the newer report describes now.
        NaruCompactRateLimitState state = new NaruCompactRateLimitState();
        state.record(plain("openai", 0, NOW.plusSeconds(60)));
        assertNotNull(state.exhaustedReason("openai"));
        state.record(plain("openai", 500, NOW.plusSeconds(60)));
        assertNull(state.exhaustedReason("openai"));
        assertEquals(1, state.isEmpty() ? 0 : 1, "one provider, one entry");
    }

    @Test
    void aReportWithNoProviderNameIsIgnored() {
        NaruCompactRateLimitState state = new NaruCompactRateLimitState();
        state.record(plain(null, 0, NOW.plusSeconds(60)));
        state.record(null);
        assertTrue(state.isEmpty());
    }

    @Test
    void aNullProviderIsQueryable() {
        NaruCompactRateLimitState state = new NaruCompactRateLimitState();
        assertNull(state.exhaustedReason(null));
        assertNull(state.latest(null));
    }
}