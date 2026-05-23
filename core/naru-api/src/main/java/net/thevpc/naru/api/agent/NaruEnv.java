package net.thevpc.naru.api.agent;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;

public interface NaruEnv {
    NOptional<NElement> get(String key);

    void put(String key, NElement value, NAruVisibility visibility);

}
