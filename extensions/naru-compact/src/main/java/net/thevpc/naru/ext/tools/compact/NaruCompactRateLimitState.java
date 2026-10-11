package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.model.NaruProviderRateLimitInfo;
import net.thevpc.naru.api.model.NaruRateLimitBucket;
import net.thevpc.nuts.time.NDuration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The most recent rate-limit report per provider, kept so a compaction can avoid a model that
 * is currently refusing calls.
 *
 * <p>Populated by {@link NaruCompactExtension} registering as a
 * {@link net.thevpc.naru.api.agent.NaruSessionUsageListener}. That is the seam the core
 * already provides for exactly this: it announces what a provider reported, and observers
 * decide what it means. Listening here rather than reaching into {@code naru-budget} keeps
 * the two extensions independent -- {@code naru-budget} may not be installed, and its absence
 * should mean "nothing is limiting us" rather than "compaction is broken".
 *
 * <p>Nothing was rate limited until a provider says so. A provider that sends no report is
 * assumed usable, because assuming otherwise would exclude every provider that does not
 * publish limits -- which includes most local ones.
 */
public class NaruCompactRateLimitState {

    private final Map<String, NaruProviderRateLimitInfo> latest = new LinkedHashMap<>();

    /**
     * Records a provider report, replacing any previous one.
     *
     * <p>Replaces rather than accumulates: a provider that reports twice has re-limited, and
     * the newer report is the one that describes now.
     */
    public void record(NaruProviderRateLimitInfo info) {
        if (info == null || info.providerName() == null) {
            return;
        }
        synchronized (latest) {
            latest.put(info.providerName(), info);
        }
    }

    public NaruProviderRateLimitInfo latest(String provider) {
        if (provider == null) {
            return null;
        }
        synchronized (latest) {
            return latest.get(provider);
        }
    }

    /**
     * Why a provider is currently unusable, or null when it looks usable.
     *
     * <p>A bucket with no reported remaining count is not a limit: providers routinely omit
     * fields they do not track, and treating an absent count as zero would mark every such
     * provider exhausted permanently.
     */
    public String exhaustedReason(String provider) {
        NaruProviderRateLimitInfo info = latest(provider);
        if (info == null) {
            return null;
        }
        Instant now = Instant.now();
        for (NaruRateLimitBucket bucket : buckets(info)) {
            if (bucket == null) {
                continue;
            }
            if (!bucket.getRemaining().isPresent()) {
                continue;
            }
            if (bucket.getRemaining().get() > 0) {
                continue;
            }
            if (bucket.getResetTime().isPresent() && bucket.getResetTime().get().isAfter(now)) {
                return "rate limited until " + bucket.getResetTime().get();
            }
            if (bucket.getResetTime().isPresent()) {
                // The reset time has passed. Providers refill buckets, so a report that said
                // "zero left" an hour ago no longer describes now -- and treating it as still
                // exhausted would block the provider permanently on the strength of a report
                // nobody has refreshed.
                continue;
            }
            // Exhausted with no announced reset time: still limited, but the provider did not
            // say for how long, so the caller cannot schedule a retry and should fall through.
            return "rate limited";
        }
        NOptionalDuration retryAfter = NOptionalDuration.of(info.retryAfter());
        if (retryAfter.present()) {
            return "provider asked to retry after " + retryAfter.value();
        }
        return null;
    }

    private static List<NaruRateLimitBucket> buckets(NaruProviderRateLimitInfo info) {
        return java.util.stream.Stream.of(
                        info.tokenBuckets() == null ? List.<NaruRateLimitBucket>of() : info.tokenBuckets(),
                        info.requestBuckets() == null ? List.<NaruRateLimitBucket>of() : info.requestBuckets())
                .flatMap(List::stream)
                .toList();
    }

    public boolean isEmpty() {
        synchronized (latest) {
            return latest.isEmpty();
        }
    }

    /** Small helper so an absent NOptional reads the same as a present one. */
    private static final class NOptionalDuration {
        private final NDuration value;

        private NOptionalDuration(NDuration value) {
            this.value = value;
        }

        static NOptionalDuration of(net.thevpc.nuts.util.NOptional<NDuration> o) {
            return new NOptionalDuration(o != null && o.isPresent() ? o.get() : null);
        }

        boolean present() {
            return value != null;
        }

        NDuration value() {
            return value;
        }
    }
}