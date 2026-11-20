package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruConfigKey;
import net.thevpc.naru.api.registry.NaruConfigKeyProvider;

import java.util.List;

/**
 * The settings the permissions feature reads, so {@code /settings} and {@code /set}
 * can complete them.
 */
public class NaruPermConfigKeysProvider implements NaruConfigKeyProvider {

    @Override
    public String name() {
        return "permissions";
    }

    @Override
    public List<NaruConfigKey> configKeys(NaruSession session) {
        return List.of(
                NaruConfigKey.of("naru.perm.enabled",
                        "load and persist access-control rules for this session")
                        .withValues("true", "false"),
                NaruConfigKey.of("naru.perm.defaultEffect",
                        "fallback effect when no rule matches (a project permission file overrides it)")
                        .withValues("allow", "deny"),
                NaruConfigKey.of("naru.perm.builtins",
                        "install the built-in safe defaults (an agent may not edit policy or read secrets)")
                        .withValues("true", "false")
        );
    }
}
