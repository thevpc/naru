package net.thevpc.naru.ext.assess;

import net.thevpc.naru.api.registry.NaruDirectiveProviderBase;

public class NaruAssessDirectiveProvider extends NaruDirectiveProviderBase {
    public NaruAssessDirectiveProvider() {
        super("assess");
        this.registerDirective(new NaruAssessDirective());
    }
}
