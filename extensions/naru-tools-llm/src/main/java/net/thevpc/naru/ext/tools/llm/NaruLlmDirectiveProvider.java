package net.thevpc.naru.ext.tools.llm;

import net.thevpc.naru.api.registry.NaruDirectiveProviderBase;

public class NaruLlmDirectiveProvider extends NaruDirectiveProviderBase {
    public NaruLlmDirectiveProvider() {
        super("llm");
        this.registerDirective(new NaruToolsDirective());
        this.registerDirective(new NaruTagsDirective());
        this.registerDirective(new NaruStatsDirective());
        this.registerDirective(new NaruModelDirective());
        this.registerDirective(new NaruModeDirective());
        this.registerDirective(new NaruHistoryDirective());
        // /skill moved to the naru-skills extension, which owns the state it reports on
        this.registerDirective(new NaruContextDirective());
    }

}