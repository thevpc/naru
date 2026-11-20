package net.thevpc.naru.ext.tools.ollama;

import net.thevpc.naru.api.registry.NaruDirectiveProviderBase;

public class NaruOllamaDirectiveProvider extends NaruDirectiveProviderBase {

    public NaruOllamaDirectiveProvider() {
        super("ollama");
        this.registerDirective(new NaruOllamaDirective());
    }
}
