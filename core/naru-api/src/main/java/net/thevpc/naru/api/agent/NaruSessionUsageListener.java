package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruProviderRateLimitInfo;
import net.thevpc.nuts.time.NDuration;

/**
 * Notified when a provider reports something the core has no opinion about: how many
 * tokens a call cost, and what the provider's own rate limits currently are.
 * <p>
 * This is a seam, not a feature. The core cannot decide what a token is worth or whether
 * a session is over budget — that is a policy choice, and it lives in an extension
 * ({@code naru-budget} is the one that ships today). What the core owes an extension is
 * that the raw numbers are announced exactly once per call, without every observer having
 * to poll or wrap the provider itself.
 * <p>
 * A listener that throws is ignored: metering must never be able to fail a model call.
 * Listeners are held per session and are not persisted.
 */
public interface NaruSessionUsageListener {

    /**
     * A model call returned. Token counts are whatever the provider reported, which for a
     * provider that does not do cache accounting is -1 rather than 0; consumers decide how
     * to treat an unreported count.
     *
     * @param duration wall-clock time of the call, for latency reporting
     */
    void onModelCall(NaruModelKey model,
                     long promptTokens,
                     long completionTokens,
                     long cacheWriteTokens,
                     long cacheReadTokens,
                     NDuration duration);

    /**
     * A provider volunteered its current rate-limit state. Providers that do not send this
     * simply never trigger the callback.
     */
    void onProviderRateLimits(NaruProviderRateLimitInfo info);
}
