package net.thevpc.naru.impl.config;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruConfigKey;
import net.thevpc.naru.api.registry.NaruConfigKeyProvider;
import net.thevpc.naru.api.spawn.NaruSpawnPolicy;

import java.util.List;

/**
 * The configuration keys the core runtime itself reads: the task loop, the
 * session store and the hooks. Every other module declares its own keys next to
 * the code that consumes them (see the {@code Naru*ConfigKeysProvider} classes
 * of the extensions); this one is the baseline every build understands.
 */
public class NaruCoreConfigKeyProvider implements NaruConfigKeyProvider {

    @Override
    public String name() {
        return "core";
    }

    @Override
    public List<NaruConfigKey> configKeys(NaruSession session) {
        return List.of(
                NaruConfigKey.of("maxSteps",
                        "maximum steps (model turns) a task runs before it must stop"),
                NaruConfigKey.of("trace",
                        "enable per-statement trace logging for this session").withValues("true", "false"),
                NaruConfigKey.of(NaruSpawnPolicy.ENV_CONFIG_POLICY,
                        "default spawn policy applied when a new task is spawned"),
                NaruConfigKey.of("session.store",
                        "session store backend; also settable with -Dnaru.session.store=..."),
                NaruConfigKey.of("session.defaultScope",
                        "visibility used when saving session state").withValues("private", "public"),
                NaruConfigKey.of("naru.hooks.initOnCd",
                        "run the init hooks when the project directory changes").withValues("true", "false"),
                NaruConfigKey.of("naru.tags.effectAnd",
                        "how 'effect' tags combine on a task").withValues("on", "warn", "off")
        );
    }
}