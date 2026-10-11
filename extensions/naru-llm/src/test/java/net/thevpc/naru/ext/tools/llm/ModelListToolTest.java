package net.thevpc.naru.ext.tools.llm;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.AbstractNaruModelProvider;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelInfo;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruModelRegistration;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruRegistry;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code model_list} — the listing companion to {@code delegate_to_model}: registered
 * models only by default, with capability / free / provider / keyword filters, and the
 * full auto-enumerated catalog only behind {@code all=true}.
 */
public class ModelListToolTest {

    /**
     * A self-contained catalog: two built-in types that auto-enumerate (hidden by
     * default) and three registrations (one unpinned, two pinned).
     */
    private static final class Fixture {
        final List<NaruModelInfo> catalog = new ArrayList<>();
        final Map<String, NaruModelProvider> providers = new LinkedHashMap<>();
        final Map<String, NaruModelRegistration> registrations = new LinkedHashMap<>();

        Fixture removeRegistrations() {
            registrations.clear();
            providers.keySet().removeIf(k -> k.equals("personal") || k.equals("fast") || k.equals("gpu"));
            return this;
        }
    }

    private static Fixture fixture() {
        Fixture f = new Fixture();
        StubProvider openrouter = new StubProvider("openrouter");
        StubProvider ollama = new StubProvider("ollama");
        StubProvider gemini = new StubProvider("gemini");
        f.providers.put("openrouter", openrouter);
        f.providers.put("ollama", ollama);
        f.providers.put("gemini", gemini);

        // built-in auto-enumerated catalogs (hidden by default)
        f.catalog.add(row("openrouter", "or-a:free", false, true, false, false, true, NaruCachingMode.AUTOMATIC_PREFIX));
        f.catalog.add(row("openrouter", "or-b", false, true, false, false, true, NaruCachingMode.AUTOMATIC_PREFIX));
        f.catalog.add(row("ollama", "llama3", false, true, false, false, true, NaruCachingMode.NONE));

        // registration instances
        f.providers.put("personal", registration(gemini, "personal", null));
        f.providers.put("fast", registration(gemini, "fast", "gem-1"));
        f.providers.put("gpu", registration(ollama, "gpu", "llama3"));

        f.catalog.add(row("personal", "gem-1", true, true, false, false, true, NaruCachingMode.NONE));
        f.catalog.add(row("personal", "gem-2", false, false, false, false, true, NaruCachingMode.NONE));
        f.catalog.add(row("fast", "gem-1", true, true, false, false, true, NaruCachingMode.NONE));
        f.catalog.add(row("gpu", "llama3", false, true, false, false, true, NaruCachingMode.NONE));

        f.registrations.put("personal", NaruModelRegistration.of("personal", "gemini"));
        f.registrations.put("fast", NaruModelRegistration.of("fast", "gemini"));
        f.registrations.put("gpu", NaruModelRegistration.of("gpu", "ollama"));
        return f;
    }

    @BeforeAll
    public static void setUp() {
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            try {
                NWorkspace ws = Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Exception ignored) {
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static NaruTask task(Fixture f) {
        NaruRegistry registry = (NaruRegistry) Proxy.newProxyInstance(
                ModelListToolTest.class.getClassLoader(),
                new Class<?>[]{NaruRegistry.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "modelsInfos":
                            return new ArrayList<>(f.catalog);
                        case "modelProviders":
                            return new LinkedHashMap<>(f.providers);
                        case "provider": {
                            String id = args[0] == null ? null : String.valueOf(args[0]).toLowerCase();
                            return NOptional.ofNullable(f.providers.get(id));
                        }
                        default:
                            return null;
                    }
                });
        NaruSession session = (NaruSession) Proxy.newProxyInstance(
                ModelListToolTest.class.getClassLoader(),
                new Class<?>[]{NaruSession.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "registry":
                            return registry;
                        case "registrations":
                            return new LinkedHashMap<>(f.registrations);
                        default:
                            return null;
                    }
                });
        return (NaruTask) Proxy.newProxyInstance(
                ModelListToolTest.class.getClassLoader(),
                new Class<?>[]{NaruTask.class},
                (proxy, method, args) -> "session".equals(method.getName()) ? session : null);
    }

    private static String list(Fixture f, String capability, Boolean free, String provider, String query, Boolean all) {
        return ModelListTool.listModels(task(f), capability, free, provider, query, all);
    }

    @Test
    public void defaultListsRegisteredModelsOnly() {
        String out = list(fixture(), null, null, null, null, null);
        Assertions.assertTrue(out.contains("personal/gem-1"), out);
        Assertions.assertTrue(out.contains("personal/gem-2"), out);
        Assertions.assertTrue(out.contains("fast/gem-1"), out);
        Assertions.assertTrue(out.contains("gpu/llama3"), out);
        Assertions.assertFalse(out.contains("openrouter/"), () -> "built-in auto catalog must stay hidden: " + out);
        Assertions.assertFalse(out.contains("ollama/llama3"), () -> "built-in auto catalog must stay hidden: " + out);
        Assertions.assertTrue(out.contains("Registered models: 4 of 7"), out);
    }

    @Test
    public void allIncludesBuiltInCatalog() {
        String out = list(fixture(), null, null, null, null, true);
        Assertions.assertTrue(out.contains("openrouter/or-a:free"), out);
        Assertions.assertTrue(out.contains("ollama/llama3"), out);
        Assertions.assertTrue(out.contains("Available models: 7"), out);
    }

    @Test
    public void visionFilterKeepsOnlyVisionModels() {
        String out = list(fixture(), "vision", null, null, null, null);
        Assertions.assertTrue(out.contains("personal/gem-1"), out);
        Assertions.assertTrue(out.contains("fast/gem-1"), out);
        Assertions.assertFalse(out.contains("personal/gem-2"), out);
        Assertions.assertFalse(out.contains("gpu/llama3"), out);
    }

    @Test
    public void freeFilterUsesOllamaTypeAndFreeSuffix() {
        Fixture f = fixture();
        String free = list(f, null, true, null, null, null);
        Assertions.assertEquals(1, countRows(free), () -> "only gpu/llama3 is free among registrations: " + free);
        Assertions.assertTrue(free.contains("gpu/llama3"), free);

        String paid = list(f, null, false, null, null, null);
        Assertions.assertTrue(paid.contains("personal/gem-1"), paid);
        Assertions.assertFalse(paid.contains("gpu/llama3"), paid);

        String allFree = list(f, null, true, null, null, true);
        Assertions.assertTrue(allFree.contains("openrouter/or-a:free"), allFree);
        Assertions.assertTrue(allFree.contains("gpu/llama3"), allFree);
    }

    @Test
    public void providerFilterMatchesInstanceIdOrType() {
        Fixture f = fixture();
        String byType = list(f, null, null, "gemini", null, null);
        Assertions.assertTrue(byType.contains("personal/gem-1"), byType);
        Assertions.assertTrue(byType.contains("fast/gem-1"), byType);
        Assertions.assertFalse(byType.contains("gpu/llama3"), byType);

        String byId = list(f, null, null, "gpu", null, null);
        Assertions.assertTrue(byId.contains("gpu/llama3"), byId);
        Assertions.assertFalse(byId.contains("personal/"), byId);
    }

    @Test
    public void unknownCapabilityIsReported() {
        String out = list(fixture(), "telepathy", null, null, null, null);
        Assertions.assertTrue(out.startsWith("Error: unknown capability"), out);
    }

    @Test
    public void noRegisteredModelsPointsAtAll() {
        String out = list(fixture().removeRegistrations(), null, null, null, null, null);
        Assertions.assertTrue(out.contains("No registered models"), out);
        Assertions.assertTrue(out.contains("all=true"), out);
    }

    @Test
    public void delegateDescriptionNoLongerInlinesTheCatalog() {
        NaruToolDefinition def = new ModelDelegateTool().getDefinition(task(fixture()));
        String description = def.getDescription();
        Assertions.assertFalse(description.contains("personal/gem-1"),
                () -> "delegate_to_model must not inline the catalog: " + description);
        Assertions.assertTrue(description.contains("model_list"),
                () -> "delegate_to_model should point at model_list: " + description);
        List<String> params = ((NaruToolDefinitionFunction) def).getParams().stream()
                .map(NaruToolParameter::getName).collect(Collectors.toList());
        Assertions.assertTrue(params.containsAll(Arrays.asList("model_name", "routine")), params.toString());
    }

    private static int countRows(String out) {
        int n = 0;
        for (String line : out.split("\n")) {
            if (line.startsWith("  ")) {
                n++;
            }
        }
        return n;
    }

    private static NaruModelInfo row(String provider, String model, boolean vision, boolean tools,
                                     boolean thinking, boolean embedding, boolean streaming,
                                     NaruCachingMode caching) {
        return new NaruModelInfo(provider, model, new NaruModelCapabilities() {
            @Override
            public long contextLength() {
                return 32768;
            }

            @Override
            public boolean isVision() {
                return vision;
            }

            @Override
            public boolean isTools() {
                return tools;
            }

            @Override
            public boolean isThinking() {
                return thinking;
            }

            @Override
            public boolean isEmbedding() {
                return embedding;
            }

            @Override
            public boolean isTextOnly() {
                return !vision && !tools && !thinking && !embedding;
            }

            @Override
            public boolean isStreaming() {
                return streaming;
            }

            @Override
            public NaruCachingMode cachingMode() {
                return caching;
            }

            @Override
            public Set<String> keys() {
                return java.util.Collections.emptySet();
            }

            @Override
            public NElement toElement() {
                return NElement.ofObjectBuilder().build();
            }
        });
    }

    private static NaruModelProvider registration(StubProvider base, String id, String pinnedModel) {
        NaruModelProvider instance = base.newInstance(id);
        if (pinnedModel != null) {
            instance.setParam("model", pinnedModel);
        }
        return instance;
    }

    /** A minimal provider type: enough for identity, type and declared-model checks. */
    public static class StubProvider extends AbstractNaruModelProvider {
        public StubProvider() {
            super("stub", new String[0]);
        }

        StubProvider(String type) {
            super(type, new String[0]);
        }

        @Override
        public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
            return NOptional.ofNamedEmpty(NMsg.ofC("no protocol in test"));
        }

        @Override
        public List<String> findModelIds(NaruSession session) {
            return List.of();
        }
    }
}
