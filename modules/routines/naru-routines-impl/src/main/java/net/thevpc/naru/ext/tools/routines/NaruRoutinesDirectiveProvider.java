package net.thevpc.naru.ext.tools.routines;

import net.thevpc.naru.api.registry.NaruDirectiveProviderBase;

public class NaruRoutinesDirectiveProvider extends NaruDirectiveProviderBase {
    public NaruRoutinesDirectiveProvider() {
        super("routines");
        this.registerDirective(new NaruRoutineDirective());
        this.registerDirective(new NaruSetDirective());
        this.registerDirective(new NaruUseDirective());
    }

}