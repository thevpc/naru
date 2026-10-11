package net.thevpc.naru.ext.tools.sessions;

import net.thevpc.naru.api.registry.NaruDirectiveProviderBase;

public class NaruSessionsDirectiveProvider extends NaruDirectiveProviderBase {
    public NaruSessionsDirectiveProvider() {
        super("sessions");
        this.registerDirective(new NaruSessionDirective());
//        this.registerDirective(new NaruReloadDirective());
//        this.registerDirective(new NaruNewDirective());
//        this.registerDirective(new NaruRestoreDirective());
//        this.registerDirective(new NaruSaveDirective());
//        this.registerDirective(new NaruResetDirective());
    }

}
