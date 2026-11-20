package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.registry.NaruDirectiveProviderBase;

public class NaruCompactDirectiveProvider extends NaruDirectiveProviderBase {
    public NaruCompactDirectiveProvider() {
        super(NaruCompactDirective.NAME);
        this.registerDirective(new NaruCompactDirective());
    }
}