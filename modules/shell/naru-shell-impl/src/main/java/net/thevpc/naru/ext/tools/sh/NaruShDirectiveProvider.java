package net.thevpc.naru.ext.tools.sh;

import net.thevpc.naru.api.registry.NaruDirectiveProviderBase;

public class NaruShDirectiveProvider extends NaruDirectiveProviderBase {

    public NaruShDirectiveProvider() {
        super("sh");
        this.registerDirective(new NaruShDirective());
        this.registerDirective(new NaruSystemDirective());
    }

}