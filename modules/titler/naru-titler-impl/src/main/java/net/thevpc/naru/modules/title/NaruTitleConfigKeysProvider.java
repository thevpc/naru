package net.thevpc.naru.modules.title;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruConfigKey;
import net.thevpc.naru.api.registry.NaruConfigKeyProvider;
import net.thevpc.naru.api.title.NaruSessionTitler;

import java.util.List;

/**
 * The {@code session.title.*} settings, declared next to the code that reads them: a build
 * without the titler jar does not offer keys nothing consumes.
 */
public class NaruTitleConfigKeysProvider implements NaruConfigKeyProvider {

    @Override
    public String name() {
        return "title";
    }

    @Override
    public List<NaruConfigKey> configKeys(NaruSession session) {
        return List.of(
                NaruConfigKey.of(NaruSessionTitler.MODELS_CONFIG_KEY,
                        "ordered provider/model keys used to title a session; the first that answers wins"),
                NaruConfigKey.of(NaruSessionTitler.AUTO_CONFIG_KEY,
                        "give a fresh session an automatic title before its first model request")
                        .withValues("true", "false"),
                NaruConfigKey.of(NaruSessionTitler.MAX_ATTEMPTS_CONFIG_KEY,
                        "how many times the titler may try a session before it stops")
        );
    }
}
