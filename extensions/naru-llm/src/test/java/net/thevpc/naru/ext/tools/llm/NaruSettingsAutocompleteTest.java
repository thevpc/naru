package net.thevpc.naru.ext.tools.llm;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.registry.NaruConfigKey;
import net.thevpc.naru.api.registry.NaruConfigKeyProvider;
import net.thevpc.naru.api.registry.NaruRegistry;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.cmdline.NArgCompleteCandidate;
import net.thevpc.nuts.cmdline.NArgCompletePosition;
import net.thevpc.nuts.cmdline.NArgCompleteResult;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * {@code /settings} completion against a proxy session: the registry answers a
 * {@code configKeys()} vocabulary aggregated from the core keys and the real
 * {@link NaruLlmConfigKeysProvider}, whose model-family keys must follow what
 * the session knows (the pinned model, the listing). No files, no network.
 */
public class NaruSettingsAutocompleteTest {

    private final Map<String, NElement> agentEnv = new LinkedHashMap<>();
    private final List<NaruModelKey> listed = new ArrayList<>();
    private final NaruSettingsDirective directive = new NaruSettingsDirective();
    private NaruSession session;

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    @BeforeEach
    public void reset() {
        agentEnv.clear();
        listed.clear();
        agentEnv.put("custom.alpha", NElement.ofString("whatever"));
        listed.add(new NaruModelKey("ollama", "qwen3:32b"));
        session = buildSession();
    }

    // ── harness ───────────────────────────────────────────────────────────────

    private NaruSession buildSession() {
        NaruEnv env = new NaruEnv() {
            @Override
            public NOptional<NElement> get(String key) {
                return NOptional.ofNullable(agentEnv.get(key));
            }

            @Override
            public NOptional<NElement> get(String key, NaruVisibility visibility) {
                return get(key);
            }

            @Override
            public void put(String key, NElement value, NaruVisibility visibility) {
                agentEnv.put(key, value);
            }

            @Override
            public Map<String, NElement> entries() {
                return agentEnv;
            }
        };

        NaruAgent agent = (NaruAgent) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{NaruAgent.class},
                (proxy, method, args) -> "env".equals(method.getName()) ? env : null);

        final NaruSession[] holder = new NaruSession[1];
        NaruRegistry registry = (NaruRegistry) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{NaruRegistry.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "configKeys":
                            // the same aggregation the real registry does: first declaration wins, sorted
                            TreeMap<String, NaruConfigKey> byKey = new TreeMap<>();
                            List<NaruConfigKeyProvider> providers = List.of(
                                    new CoreKeysProvider(),
                                    new NaruLlmConfigKeysProvider());
                            for (NaruConfigKeyProvider p : providers) {
                                List<NaruConfigKey> keys = p.configKeys(holder[0]);
                                if (keys != null) {
                                    for (NaruConfigKey k : keys) {
                                        if (k != null) {
                                            byKey.putIfAbsent(k.key(), k);
                                        }
                                    }
                                }
                            }
                            return new ArrayList<>(byKey.values());
                        case "modelsKeys":
                            return new ArrayList<>(listed);
                        default:
                            return null;
                    }
                });

        NaruSession session = (NaruSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{NaruSession.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "agent":
                            return agent;
                        case "registry":
                            return registry;
                        case "listedModels":
                            return new ArrayList<>(listed);
                        case "registrations":
                            return Map.of();
                        case "getProjectEnv":
                            // the pinned model key: seeded so the model-family keys appear
                            return NOptional.of(NElement.ofString("qwen3:32b"));
                        default:
                            return null;
                    }
                });
        holder[0] = session;
        return session;
    }

    /** The baseline keys a session always understands, mirroring the core provider. */
    private static final class CoreKeysProvider implements NaruConfigKeyProvider {
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
                            "enable per-statement trace logging for this session").withValues("true", "false")
            );
        }
    }

    private List<String> completeValues(String... words) {
        return complete(words).stream().map(NArgCompleteCandidate::value).collect(Collectors.toList());
    }

    private List<NArgCompleteCandidate> complete(String... words) {
        NCmdLine cmdLine = NCmdLine.of(words);
        int last = words.length - 1;
        NArgCompletePosition pos = NArgCompletePosition.of(last, words[last].length(), 0);
        return directive.resolveCandidates(cmdLine.completePosition(pos), pos, session).candidates();
    }

    private static String display(List<NArgCompleteCandidate> candidates, String value) {
        for (NArgCompleteCandidate c : candidates) {
            if (c.value().equals(value)) {
                return c.display();
            }
        }
        return null;
    }

    // ── the declared vocabulary ───────────────────────────────────────────────

    @Test
    public void declaredKeysCompleteWithDescriptions() {
        List<NArgCompleteCandidate> c = complete("/settings", "model.");
        List<String> values = c.stream().map(NArgCompleteCandidate::value).collect(Collectors.toList());

        Assertions.assertTrue(values.contains("model.thinking"), () -> "got: " + values);
        Assertions.assertTrue(values.contains("model.thinking="), () -> "the '=' hole must be offered, got: " + values);
        Assertions.assertTrue(values.contains("model.noStream"), () -> "got: " + values);
        Assertions.assertTrue(values.contains("model.maxRetries"), () -> "got: " + values);
        Assertions.assertFalse(values.contains("maxSteps"),
                "a currentArg of 'model.' must only answer the model keys, got: " + values);

        String thinking = display(c, "model.thinking");
        Assertions.assertNotNull(thinking, () -> "model.thinking candidate missing");
        Assertions.assertTrue(thinking.contains("switch model reasoning"),
                () -> "the description must ride the candidate, got: " + thinking);

        // an empty currentArg sees the whole vocabulary, including the core keys
        List<String> all = completeValues("/settings", "");
        Assertions.assertTrue(all.contains("maxSteps"), () -> "core keys must be completable, got: " + all);
        Assertions.assertTrue(all.contains("trace=true") || all.contains("trace"),
                () -> "the trace key must be completable, got: " + all);
    }

    @Test
    public void fixedVocabularyCompletesAfterTheEquals() {
        List<String> c = completeValues("/settings", "model.thinking=");
        Assertions.assertTrue(c.contains("model.thinking=true"), () -> "got: " + c);
        Assertions.assertTrue(c.contains("model.thinking=false"), () -> "got: " + c);
        Assertions.assertFalse(c.contains("model.thinking"),
                "once the '=' is typed only values answer, got: " + c);

        List<String> partial = completeValues("/settings", "model.thinking=f");
        Assertions.assertEquals(List.of("model.thinking=false"), partial, () -> "got: " + partial);

        // a free-form key offers nothing past the '='
        Assertions.assertTrue(completeValues("/settings", "model.timeout=").isEmpty(),
                "a free-form value cannot be completed past the '='");
    }

    @Test
    public void modelFamilyKeysFollowWhatTheSessionKnows() {
        // the session pins qwen3:32b, so the qwen-indexed timeout keys are completable
        List<String> c = completeValues("/settings", "model.qwen.");
        Assertions.assertTrue(c.contains("model.qwen.readTimeout"), () -> "got: " + c);
        Assertions.assertTrue(c.contains("model.qwen.connectTimeout"), () -> "got: " + c);
        Assertions.assertTrue(c.contains("model.qwen.timeout"), () -> "got: " + c);
    }

    @Test
    public void anExistingEnvKeyCompletesOnceItExists() {
        List<String> c = completeValues("/settings", "custom.");
        Assertions.assertTrue(c.contains("custom.alpha"), () -> "got: " + c);
        Assertions.assertTrue(c.contains("custom.alpha="), () -> "got: " + c);
        String alpha = display(complete("/settings", "custom.alpha"), "custom.alpha");
        Assertions.assertTrue(alpha.contains("session or agent env"),
                () -> "an undeclared key must be labeled as env-present, got: " + alpha);
    }

    @Test
    public void visibilityFlagsComplete() {
        List<String> c = completeValues("/settings", "--");
        Assertions.assertTrue(c.contains("--private"), () -> "got: " + c);
        Assertions.assertTrue(c.contains("--public"), () -> "got: " + c);
        Assertions.assertTrue(c.contains("--local"), () -> "got: " + c);
    }
}