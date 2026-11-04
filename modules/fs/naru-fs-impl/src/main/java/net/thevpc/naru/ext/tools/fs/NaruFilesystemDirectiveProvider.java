package net.thevpc.naru.ext.tools.fs;

import net.thevpc.naru.api.registry.NaruDirectiveProviderBase;

public class NaruFilesystemDirectiveProvider extends NaruDirectiveProviderBase {

    public NaruFilesystemDirectiveProvider() {
        super("fs");
        this.registerDirective(new NaruPwdDirective());
        this.registerDirective(new NaruCdDirective());
        this.registerDirective(new NaruCatDirective());
        this.registerDirective(new NaruLsDirective());
        this.registerDirective(new NaruFileDirective());
    }
}