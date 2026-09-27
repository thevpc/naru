package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.elem.NElement;

public interface NaruModelRequestSerializer {
    NElement serialize(NaruModelRequest request, NaruModelConfig model,NaruSession session);

    /**
     * Serialise a request that is about to be read incrementally.
     *
     * <p>The flag is a parameter rather than a field because a request body for
     * the same conversation is not otherwise different: almost every protocol
     * signals streaming with one boolean in the same payload. Carrying it on the
     * request object instead would have made the body depend on hidden mutable
     * state, which is exactly the kind of thing that makes a retry silently send
     * a different request than the one that failed.
     *
     * <p>The default ignores the flag and produces the ordinary body, so a
     * protocol that has no streaming transport stays correct: the caller reads a
     * complete response and delivers it as one batch of chunks.
     */
    default NElement serialize(NaruModelRequest request, NaruModelConfig model, NaruSession session, boolean stream) {
        return serialize(request, model, session);
    }
}
