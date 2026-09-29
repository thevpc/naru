package net.thevpc.naru.api.mode;

import net.thevpc.nuts.spi.NComponent;
import net.thevpc.nuts.util.NOptional;

import java.util.Set;

public interface NaruPromptMode extends NComponent {
    String DEFAULT = "default";
    String name();

    String[] aliases();

    String systemPrompt();

    boolean acceptToolTags(Set<String> tags);

}
