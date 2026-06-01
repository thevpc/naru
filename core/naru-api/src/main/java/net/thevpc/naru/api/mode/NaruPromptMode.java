package net.thevpc.naru.api.mode;

import net.thevpc.nuts.util.NOptional;

public interface NaruPromptMode {
    String name();
    String[] aliases();
    String systemPrompt();
    NOptional<NaruStandardMode> asStandardMode();
}
