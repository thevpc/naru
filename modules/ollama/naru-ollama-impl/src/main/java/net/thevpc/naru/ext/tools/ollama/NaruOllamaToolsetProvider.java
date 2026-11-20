package net.thevpc.naru.ext.tools.ollama;

import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.naru.api.registry.NaruToolsetProvider;
import net.thevpc.nuts.elem.NObjectElement;

import java.util.Collections;
import java.util.List;

public class NaruOllamaToolsetProvider implements NaruToolsetProvider {

    @Override
    public String name() {
        return "ollama";
    }

    @Override
    public List<String> supportedTypes() {
        return Collections.singletonList("ollama");
    }

    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        return new NaruOllamaToolset(id);
    }
}
