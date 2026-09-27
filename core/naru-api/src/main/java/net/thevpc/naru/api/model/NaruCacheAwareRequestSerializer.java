package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.elem.NElement;

/**
 * Implemented by serializers whose provider can express prompt caching
 * explicitly, so the placement decision survives into the wire format.
 *
 * <p>Extending {@link NaruModelRequestSerializer} rather than replacing it keeps
 * every existing serializer compiling untouched: providers that cache
 * automatically need no implementation at all, and a
 * {@code NONE}-capability provider keeps the exact body it produced before.
 */
public interface NaruCacheAwareRequestSerializer extends NaruModelRequestSerializer {

    /**
     * Serialize with a resolved caching plan.
     *
     * <p>A plan is always passed, including for a full miss — "no markers
     * anywhere" is a meaningful plan, and representing it as {@code null} would
     * make it indistinguishable from a provider that forgot to opt in.
     */
    NElement serialize(NaruModelRequest request, NaruModelConfig model, NaruSession session,
                       NaruCachePlanView plan);

    /**
     * Serialise a streamed request with a resolved caching plan.
     *
     * <p>The two concerns are independent -- where a cache breakpoint lands has
     * nothing to do with whether the response arrives progressively -- so a
     * serializer that cares about one can keep ignoring the other. The default
     * drops the flag, which is right for a provider whose caching markers and
     * streaming flag never interact.
     */
    default NElement serialize(NaruModelRequest request, NaruModelConfig model, NaruSession session,
                               NaruCachePlanView plan, boolean stream) {
        return serialize(request, model, session, plan);
    }

    @Override
    default NElement serialize(NaruModelRequest request, NaruModelConfig model, NaruSession session) {
        return serialize(request, model, session, NaruCachePlanView.none());
    }
}
