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

    @Override
    default NElement serialize(NaruModelRequest request, NaruModelConfig model, NaruSession session) {
        return serialize(request, model, session, NaruCachePlanView.none());
    }
}
