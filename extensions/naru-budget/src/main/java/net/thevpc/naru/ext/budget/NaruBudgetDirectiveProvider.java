package net.thevpc.naru.ext.budget;

import net.thevpc.naru.api.registry.NaruDirectiveProviderBase;

public class NaruBudgetDirectiveProvider extends NaruDirectiveProviderBase {
    public NaruBudgetDirectiveProvider() {
        super("budget");
        this.registerDirective(new NaruBudgetDirective());
    }
}
