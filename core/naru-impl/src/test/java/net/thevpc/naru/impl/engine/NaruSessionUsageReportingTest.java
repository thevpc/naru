package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionUsageListener;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruProviderRateLimitInfo;
import net.thevpc.naru.api.model.NaruRateLimitWindow;
import net.thevpc.naru.api.model.DefaultNaruProviderRateLimitInfo;
import net.thevpc.naru.api.model.DefaultNaruRateLimitBucket;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.time.NDuration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Covers the usage seam the metering extension hangs off. The core's whole obligation here
 * is to announce provider-reported numbers faithfully, to every listener, without letting an
 * observer's failure reach the call it is watching.
 */
public class NaruSessionUsageReportingTest {

    private static final NaruModelKey MODEL = new NaruModelKey("test", "m1");

    @BeforeAll
    public static void setUpWorkspace() {
        // NPath.ofTempFolder resolves through the shared workspace
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

    private static NaruSessionImpl newSession() {
        NaruAgent agent = new NaruAgentImpl();
        agent.setProjectDirectory(NPath.ofTempFolder("naru-usage-reporting"));
        // configureDefaults=false keeps SPI discovery and the network out of it
        return new NaruSessionImpl(agent, agent.getProjectDirectory(), false, null, null, null, null);
    }

    /** Records every callback so a test can assert on the values, not just the count. */
    private static class Recorder implements NaruSessionUsageListener {
        final List<long[]> calls = new ArrayList<>();
        final List<NaruProviderRateLimitInfo> limits = new ArrayList<>();

        @Override
        public void onModelCall(NaruModelKey model, long promptTokens, long completionTokens,
                                long cacheWriteTokens, long cacheReadTokens, NDuration duration) {
            calls.add(new long[]{promptTokens, completionTokens, cacheWriteTokens, cacheReadTokens,
                    duration.toMillis()});
        }

        @Override
        public void onProviderRateLimits(NaruProviderRateLimitInfo info) {
            limits.add(info);
        }
    }

    @Test
    public void reportsAllTokenCountsIncludingCache() {
        NaruSessionImpl session = newSession();
        Recorder r = new Recorder();
        session.addUsageListener(r);

        session.fireModelCallUsage(MODEL, 1200, 80, 300, 900, NDuration.ofMillis(1500));

        Assertions.assertEquals(1, r.calls.size());
        long[] got = r.calls.get(0);
        Assertions.assertArrayEquals(new long[]{1200, 80, 300, 900, 1500}, got,
                "cache-write and cache-read counts must survive the trip, since a provider "
                        + "that reports them is the only way they are ever known");
    }

    /**
     * A provider with no cache accounting sends -1. That is information, not a number to
     * add, so it must arrive unchanged and let the consumer decide.
     */
    @Test
    public void passesUnreportedCacheCountsThroughAsMinusOne() {
        NaruSessionImpl session = newSession();
        Recorder r = new Recorder();
        session.addUsageListener(r);

        session.fireModelCallUsage(MODEL, 50, 5, -1, -1, NDuration.ofMillis(10));

        Assertions.assertArrayEquals(new long[]{50, 5, -1, -1, 10}, r.calls.get(0));
    }

    @Test
    public void everyRegisteredListenerIsNotified() {
        NaruSessionImpl session = newSession();
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        session.addUsageListener(a);
        session.addUsageListener(b);

        session.fireModelCallUsage(MODEL, 10, 1, 0, 0, NDuration.ofMillis(5));

        Assertions.assertEquals(1, a.calls.size());
        Assertions.assertEquals(1, b.calls.size());
    }

    /**
     * Metering is a side channel. A listener that throws must not cost the user their
     * answer, and must not starve the listeners registered after it.
     */
    @Test
    public void aThrowingListenerDoesNotBreakTheCallOrTheOtherListeners() {
        NaruSessionImpl session = newSession();
        Recorder after = new Recorder();
        session.addUsageListener(new NaruSessionUsageListener() {
            @Override
            public void onModelCall(NaruModelKey model, long promptTokens, long completionTokens,
                                    long cacheWriteTokens, long cacheReadTokens, NDuration duration) {
                throw new IllegalStateException("listener is broken");
            }

            @Override
            public void onProviderRateLimits(NaruProviderRateLimitInfo info) {
                throw new IllegalStateException("listener is broken");
            }
        });
        session.addUsageListener(after);

        Assertions.assertDoesNotThrow(() ->
                        session.fireModelCallUsage(MODEL, 10, 1, 0, 0, NDuration.ofMillis(5)),
                "a broken observer must not propagate out of a model call");
        Assertions.assertEquals(1, after.calls.size(),
                "a broken listener must not swallow the notification for later ones");
    }

    @Test
    public void removedListenerStopsReceiving() {
        NaruSessionImpl session = newSession();
        Recorder r = new Recorder();
        session.addUsageListener(r);

        session.fireModelCallUsage(MODEL, 10, 1, 0, 0, NDuration.ofMillis(5));
        session.removeUsageListener(r);
        session.fireModelCallUsage(MODEL, 10, 1, 0, 0, NDuration.ofMillis(5));

        Assertions.assertEquals(1, r.calls.size());
    }

    @Test
    public void rateLimitsReachListenersAndNullIsIgnored() {
        NaruSessionImpl session = newSession();
        Recorder r = new Recorder();
        session.addUsageListener(r);

        session.reportProviderRateLimits(new DefaultNaruProviderRateLimitInfo(
                session.uuid(), null, "test", Instant.now(),
                List.of(new DefaultNaruRateLimitBucket(NaruRateLimitWindow.MINUTE, 100, 90, null)),
                List.of(), "corr-1", null, NElement.ofObjectBuilder().build()));
        session.reportProviderRateLimits(null);

        Assertions.assertEquals(1, r.limits.size());
        Assertions.assertEquals("corr-1", r.limits.get(0).correlationId().orElse(null));
    }

    /** A null listener is a no-op rather than a delayed NPE on the first model call. */
    @Test
    public void nullListenerIsIgnored() {
        NaruSessionImpl session = newSession();
        session.addUsageListener(null);
        Assertions.assertDoesNotThrow(() ->
                session.fireModelCallUsage(MODEL, 1, 1, 0, 0, NDuration.ofMillis(1)));
    }
}
