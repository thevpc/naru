package net.thevpc.naru.api.mode;

import net.thevpc.nuts.util.NOptional;

public interface NaruMode {
    String name();
    String[] aliases();
    String systemPrompt();
    NOptional<NaruStandardMode> asStandardMode();
}
