package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.registry.NaruDirectiveProviderBase;

public class NaruSkillsDirectiveProvider extends NaruDirectiveProviderBase {
    public NaruSkillsDirectiveProvider() {
        super("skills");
        this.registerDirective(new NaruSkillDirective());
    }
}
