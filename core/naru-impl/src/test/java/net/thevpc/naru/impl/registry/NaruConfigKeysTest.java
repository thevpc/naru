package net.thevpc.naru.impl.registry;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruConfigKey;
import net.thevpc.naru.api.registry.NaruConfigKeyProvider;
import net.thevpc.naru.api.registry.NaruRegistry;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The registry side of the settings surface: {@code configKeys()} aggregates the
 * declared keys into one sorted, de-duplicated vocabulary, and a single broken
 * provider may not break it. The core provider is the baseline every session has.
 */
public class NaruConfigKeysTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private NaruSession newSession() {
        NaruAgentImpl agent = new NaruAgentImpl();
        NPath dir = NPath.ofTempFolder("naru-configkeys-" + System.nanoTime());
        agent.projectDirectory(dir);
        return new NaruSessionImpl(agent, dir, new NaruStreamInteraction(o -> {
        }), true, null, null, null, null);
    }

    @Test
    public void coreKeysAreDeclaredSortedAndDescribed() {
        NaruSession session = newSession();
        List<NaruConfigKey> keys = session.registry().configKeys();

        Assertions.assertFalse(keys.isEmpty(), "a default session must know its own keys");
        List<String> names = keys.stream().map(NaruConfigKey::key).collect(Collectors.toList());
        Assertions.assertEquals(names.stream().sorted().collect(Collectors.toList()), names,
                "configKeys() must be sorted by key, the order the listing prints");
        Assertions.assertEquals(names.stream().distinct().collect(Collectors.toList()), names,
                "configKeys() must not repeat a key");

        for (String core : new String[]{"maxSteps", "trace", "spawn.policy", "session.store",
                "session.defaultScope", "naru.hooks.initOnCd", "naru.tags.effectAnd"}) {
            Assertions.assertTrue(names.contains(core), "core key '" + core + "' missing from " + names);
        }

        NaruConfigKey trace = keys.stream().filter(k -> k.key().equals("trace")).findFirst().orElse(null);
        Assertions.assertNotNull(trace);
        Assertions.assertFalse(trace.description().isBlank(), "a declared key must carry a description");
        Assertions.assertEquals(List.of("true", "false"), trace.values(),
                "a fixed-vocabulary key must declare its values for completion");
        NaruConfigKey effect = keys.stream().filter(k -> k.key().equals("naru.tags.effectAnd")).findFirst().orElse(null);
        Assertions.assertEquals(List.of("on", "warn", "off"), effect.values());
    }

    @Test
    public void duplicateProviderNamesAreRejected() {
        NaruSession session = newSession();
        NaruRegistry registry = session.registry();
        registry.registerConfigKeyProvider(new StaticProvider("dup", "key.one", "first"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> registry.registerConfigKeyProvider(new StaticProvider("dup", "key.two", "second")),
                "a second provider under the same name must be rejected, not silently replace the first");
    }

    @Test
    public void duplicateKeysKeepTheFirstDeclaration() {
        NaruSession session = newSession();
        NaruRegistry registry = session.registry();
        registry.registerConfigKeyProvider(new StaticProvider("alpha", "shared.z", "first wins"));
        registry.registerConfigKeyProvider(new StaticProvider("beta", "shared.z", "second loses"));

        List<NaruConfigKey> keys = registry.configKeys();
        NaruConfigKey shared = keys.stream().filter(k -> k.key().equals("shared.z")).findFirst().orElse(null);
        Assertions.assertNotNull(shared);
        Assertions.assertEquals("first wins", shared.description(),
                "the first declaration of a key must win the listing");
        Assertions.assertEquals(1, keys.stream().filter(k -> k.key().equals("shared.z")).count());
    }

    @Test
    public void aThrowingProviderIsSkippedNotFatal() {
        NaruSession session = newSession();
        NaruRegistry registry = session.registry();
        registry.registerConfigKeyProvider(new NaruConfigKeyProvider() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public List<NaruConfigKey> configKeys(NaruSession ignored) {
                throw new RuntimeException("broken extension");
            }
        });

        // must neither throw nor leak partial output
        List<NaruConfigKey> keys = registry.configKeys();
        Assertions.assertTrue(keys.stream().noneMatch(k -> k.key().startsWith("boom.")));
        Assertions.assertFalse(keys.isEmpty(), "the healthy providers' keys must still be there");
    }

    @Test
    public void lateProvidersAreAggregatedOnDemand() {
        NaruSession session = newSession();
        NaruRegistry registry = session.registry();
        Assertions.assertTrue(registry.configKeys().stream().noneMatch(k -> k.key().equals("late.arrival")));

        registry.registerConfigKeyProvider(new StaticProvider("late", "late.arrival", "registered after defaults"));
        Assertions.assertTrue(registry.configKeys().stream().anyMatch(k -> k.key().equals("late.arrival")),
                "a provider registered later must be picked up, the surface is per session");
    }

    /** A fixed key under a fixed name, for exercising registry bookkeeping. */
    private static final class StaticProvider implements NaruConfigKeyProvider {
        private final String name;
        private final String key;
        private final String description;

        StaticProvider(String name, String key, String description) {
            this.name = name;
            this.key = key;
            this.description = description;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<NaruConfigKey> configKeys(NaruSession session) {
            return List.of(NaruConfigKey.of(key, description));
        }
    }
}