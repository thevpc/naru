package net.thevpc.naru.api.mode;

import net.thevpc.nuts.util.NOptional;

import java.util.Set;

public interface NaruPromptMode {
    String name();

    String[] aliases();

    String systemPrompt();

    boolean acceptToolTags(Set<String> tags);

    NOptional<NaruStandardMode> asStandardMode();
}
