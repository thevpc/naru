package net.thevpc.naru.ext.tools.llamacpp;

import net.thevpc.naru.api.registry.NaruDirectiveProviderBase;

public class NaruLlamaCppDirectiveProvider extends NaruDirectiveProviderBase {

    public NaruLlamaCppDirectiveProvider() {
        super("llamacpp");
        this.registerDirective(new NaruLlamaCppDirective());
    }
}
