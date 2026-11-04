package net.thevpc.naru.ext.tools.ollama;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruConfigKey;
import net.thevpc.naru.api.registry.NaruConfigKeyProvider;

import java.util.List;

/**
 * The {@code ollama.*} keys the ollama toolset reads through the agent env
 * (see {@link OllamaService}). Declared here, next to the read, so the
 * settings surface offers the key that actually points at the local server.
 */
public class NaruOllamaConfigKeysProvider implements NaruConfigKeyProvider {

    @Override
    public String name() {
        return "ollama";
    }

    @Override
    public List<NaruConfigKey> configKeys(NaruSession session) {
        return List.of(
                NaruConfigKey.of("ollama.url",
                        "base url of the ollama server, e.g. http://127.0.0.1:11434")
        );
    }
}