package net.thevpc.naru.ext.tools.tasks;

import net.thevpc.naru.api.registry.NaruDirectiveProviderBase;

public class NaruTasksDirectiveProvider extends NaruDirectiveProviderBase {
    public NaruTasksDirectiveProvider() {
        super("tasks");
        this.registerDirective(new NaruOnDirective());
        this.registerDirective(new NaruFireDirective());
        this.registerDirective(new NaruSourceDirective());
        this.registerDirective(new NaruSpawnPolicyDirective());
        this.registerDirective(new NaruStartDirective());
        this.registerDirective(new NaruTaskDirective());
        this.registerDirective(new NaruSleepDirective());
        this.registerDirective(new NaruWaitDirective());
    }

}