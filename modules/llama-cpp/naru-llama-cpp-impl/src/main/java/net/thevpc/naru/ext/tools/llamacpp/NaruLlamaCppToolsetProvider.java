package net.thevpc.naru.ext.tools.llamacpp;

import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.naru.api.registry.NaruToolsetProvider;
import net.thevpc.nuts.elem.NObjectElement;

import java.util.Collections;
import java.util.List;

public class NaruLlamaCppToolsetProvider implements NaruToolsetProvider {

    @Override
    public String name() {
        return "llamacpp";
    }

    @Override
    public List<String> supportedTypes() {
        return Collections.singletonList("llamacpp");
    }

    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        return new NaruLlamaCppToolset(id);
    }
}
