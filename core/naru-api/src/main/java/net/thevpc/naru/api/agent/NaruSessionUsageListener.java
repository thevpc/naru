package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruProviderRateLimitInfo;
import net.thevpc.naru.api.task.NaruTask;
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
     * <h2>Normalization contract</h2>
     * Adapters must normalize before announcing, so that consumers never special-case a
     * provider:
     * <ul>
     *   <li>{@code promptTokens} <b>includes</b> {@code cacheWriteTokens} and
     *       {@code cacheReadTokens}. They are a breakdown of the input total, not an
     *       addition to it. (Anthropic's {@code input_tokens} excludes them, so its adapter
     *       adds them back; OpenAI and Gemini already include them.)</li>
     *   <li>{@code completionTokens} <b>includes</b> reasoning/thinking tokens, which
     *       providers bill as output. (Gemini reports {@code thoughtsTokenCount} outside
     *       {@code candidatesTokenCount}, so its adapter adds it in.)</li>
     *   <li>{@code cacheWriteTokens} is the count billed at a cache-write rate
     *       (Anthropic's cache creation). It is {@code 0} when the provider reports cache
     *       accounting but has no cache-write class (OpenAI, Gemini), and {@code -1} when
     *       the provider reports no cache accounting at all.</li>
     *   <li>{@code cacheReadTokens} is the count billed at the discounted cache-read rate,
     *       or {@code -1} when unreported.</li>
     * </ul>
     * With this contract the full-rate input remainder is always
     * {@code max(0, promptTokens - cacheReadTokens - cacheWriteTokens)}.
     *
     * @param task the task that made the call, so a consumer can stamp task identity and
     *             user-defined labels; never null on the production path
     * @param duration wall-clock time of the call, for latency reporting
     */
    void onModelCall(NaruTask task,
                     NaruModelKey model,
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
