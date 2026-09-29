package net.thevpc.naru.ext.tools.plan.modes;

import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.registry.NaruToolTags;

import java.util.Set;

public class ImplementNaruPromptMode implements NaruPromptMode {
    @Override
    public String name() {
        return "implement";
    }

    @Override
    public String[] aliases() {
        return new String[]{"impl", "do"};
    }

    @Override
    public String systemPrompt() {
        return "You are in IMPLEMENT mode.\n" +
                "[Goal]: Systematically implement the provided structural plan.\n" +
                "[Rules]:\n" +
                "1. Change exactly one milestone step at a time.\n" +
                "2. Run test/compile tools immediately after editing any file.\n" +
                "3. If exit code != 0, stop and fix before moving to the next step.";
    }

    @Override
    public boolean acceptToolTags(Set<String> tags) {
        return true;
    }


}
