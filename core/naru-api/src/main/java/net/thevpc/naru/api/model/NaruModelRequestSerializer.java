package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.elem.NElement;

public interface NaruModelRequestSerializer {
    NElement serialize(NaruModelRequest request, NaruModelConfig model,NaruSession session);
}
