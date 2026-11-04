package net.thevpc.naru.ext.models.cache;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruCachePlanView;
import net.thevpc.naru.api.model.NaruCacheableContext;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruModelRequest;

/**
 * Runtime entry point for prompt caching: given a request and a provider's
 * declared support, decide what prefix (if any) can be reused, and remember what
 * was actually sent.
 *
 * <p>Every step is defensive. A missing session extension, an unrecognised
 * mode, a model that changed under us, an expired resource — all of them
 * resolve to "send the whole thing", which is always correct and merely
 * slower. Nothing in here is allowed to fail a model call.
 */
public final class NaruModelCaching {

    private NaruModelCaching() {
    }

    /**
     * Milliseconds before a stateful resource's stated expiry at which we stop
     * trusting it. Expiry is reported by the provider and a request can take
     * seconds; starting a turn on a resource that dies mid-flight wastes the
     * whole turn.
     */
    public static final long RESOURCE_SAFETY_MARGIN_MILLIS = 60_000L;

    /**
     * Plan a turn.
     *
     * @param providerName provider identity, so one session can talk to several models
     * @param modelName    concrete model, so switching models cannot reuse a prefix
     * @param mode         provider's declared support; {@code NONE} short-circuits
     */
    public static NaruCachePlanView plan(NaruSession session, String providerName, String modelName,
                                         NaruCachingMode mode, NaruModelRequest request) {
        if (mode == null || mode == NaruCachingMode.NONE) {
            return NaruCachePlanView.none();
        }
        NaruCacheableContext context = request == null ? null : request.cacheableContext();
        if (context == null || context.isEmpty()) {
            return NaruCachePlanView.none();
        }
        NaruModelCacheExtension cache = NaruModelCacheExtension.of(session);
        NaruCacheBaseline baseline = cache == null
                ? null
                : cache.baseline(providerName, modelName, mode);

        // A stateful resource that is gone or about to expire cannot back the
        // prefix. Forget the id so the provider re-creates it on this turn; the
        // prefix itself is still worth re-sending as an ordinary cacheable span.
        if (cache != null && baseline != null && baseline.getCachedResourceId() != null
                && !baseline.isResourceUsable(System.currentTimeMillis(), RESOURCE_SAFETY_MARGIN_MILLIS)) {
            cache.clearResource(providerName, modelName, mode);
        }

        return NaruCachePlan.of(context, baseline, providerName, modelName, mode);
    }

    /**
     * Record the prefix actually sent, so the next turn can detect a hit.
     *
     * <p>Called after a <em>successful</em> response only. Committing on failure
     * would record a prefix the provider never actually stored, and the next turn
     * would then claim a hit that turns out to cost a full-price miss.
     */
    public static void commit(NaruSession session, String providerName, String modelName,
                              NaruCachingMode mode, NaruCacheableContext context) {
        if (mode == null || mode == NaruCachingMode.NONE || context == null || context.isEmpty()) {
            return;
        }
        NaruModelCacheExtension cache = NaruModelCacheExtension.of(session);
        if (cache == null) {
            return;
        }
        cache.commit(providerName, modelName, mode,
                NaruCacheKeyChain.chain(context.segments()), System.currentTimeMillis());
    }

    /**
     * Plan, and remember the chain in the same turn.
     *
     * <p>For providers whose cache is purely prefix-based, where the chain is
     * knowable before the call. Providers with a stateful resource commit only
     * after they have the server's confirmation.
     */
    public static NaruCachePlanView apply(NaruSession session, String providerName, String modelName,
                                          NaruCachingMode mode, NaruModelRequest request) {
        NaruCachePlanView plan = plan(session, providerName, modelName, mode, request);
        if (plan instanceof NaruCachePlan) {
            commit(session, providerName, modelName, mode, request.cacheableContext());
        }
        return plan;
    }
}
