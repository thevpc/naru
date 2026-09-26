package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Guards the contract that makes {@code /model use <n>} usable: the index printed by
 * {@code /model [list] [--free|--provider=x|<filter>]} must select the very same model.
 *
 * <p>Historically the listing numbered a <i>filtered</i> {@code modelsInfos} view while
 * {@code findModel("<n>")} resolved against the unfiltered {@code modelsKeys} catalog, so
 * {@code /model --free} followed by {@code /model use 3} silently selected an unrelated
 * (paid) model. Two things are asserted here:
 * <ol>
 *     <li>{@code modelsKeys} and {@code modelsInfos} agree on order and content, so the
 *         unfiltered catalog is a single source of truth for positional indexes;</li>
 *     <li>the rows of the last listing are snapshotted on the session and take precedence
 *         when a numeric index is resolved.</li>
 * </ol>
 */
public class ModelIndexStabilityTest {

    private NaruSession session;

    @BeforeAll
    public static void setUpWorkspace() {
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

    @BeforeEach
    public void setUp() {
        NaruAgent agent = new NaruAgentImpl();
        agent.setProjectDirectory(NPath.ofTempFolder("naru-model-index"));
        // configureDefaults=false: no SPI provider discovery, no network, no real models
        session = new NaruSessionImpl(agent, agent.getProjectDirectory(), false,
                new NaruSessionListener() {
                    @Override
                    public void onEventAppended(NaruEvent newEvent) {
                    }

                    @Override
                    public void sessionStarted(NaruSession session) {
                    }

                    @Override
                    public void sessionStopped(NaruSession session) {
                    }

                    @Override
                    public void onSessionReloaded(NaruSession naruSession) {
                    }
                }, null, null, null);
    }

    @AfterEach
    public void tearDown() {
        if (session != null) {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    public void modelsKeysAndInfosShareTheSameOrder() {
        session.registry().registerModelProvider(new FakeProvider("openrouter",
                Arrays.asList("a-paid", "b-paid", "c:free"), true));
        session.registry().registerModelProvider(new FakeProvider("ollama",
                Arrays.asList("qwen3:latest", "llama3:latest"), true));

        List<String> keys = session.registry().modelsKeys(session).stream()
                .map(NaruModelKey::toString).collect(Collectors.toList());
        List<String> infos = session.registry().modelsInfos(session).stream()
                .map(NaruModelInfo::key).map(NaruModelKey::toString).collect(Collectors.toList());

        Assertions.assertEquals(List.of(
                "ollama/llama3:latest",
                "ollama/qwen3:latest",
                "openrouter/a-paid",
                "openrouter/b-paid",
                "openrouter/c:free"
        ), keys);
        // the displayed order and the index-resolution order must be the exact same list
        Assertions.assertEquals(keys, infos);
    }

    @Test
    public void unavailableProvidersAreExcludedFromBothLists() {
        // 'aaa' sorts before 'ollama': if it leaked into modelsKeys only, every index
        // displayed to the user would be shifted by its model count.
        session.registry().registerModelProvider(new FakeProvider("aaa",
                Arrays.asList("hidden-1", "hidden-2"), false));
        session.registry().registerModelProvider(new FakeProvider("ollama",
                Arrays.asList("qwen3:latest"), true));

        List<String> keys = session.registry().modelsKeys(session).stream()
                .map(NaruModelKey::toString).collect(Collectors.toList());
        List<String> infos = session.registry().modelsInfos(session).stream()
                .map(NaruModelInfo::key).map(NaruModelKey::toString).collect(Collectors.toList());

        Assertions.assertEquals(List.of("ollama/qwen3:latest"), keys);
        Assertions.assertEquals(List.of("ollama/qwen3:latest"), infos);
    }

    @Test
    public void indexResolvesAgainstLastFilteredListing() {
        // 'aaa' sorts first and its paid models are dropped by --free, so the filtered
        // numbering is shifted by 2 relative to the raw catalog. Resolving against the
        // catalog would answer 'aaa/a-paid' for index 1.
        session.registry().registerModelProvider(new FakeProvider("aaa",
                Arrays.asList("a-paid", "a2-paid", "a3:free"), true));
        session.registry().registerModelProvider(new FakeProvider("ollama",
                Arrays.asList("qwen3:latest", "llama3:latest"), true));

        // '/model --free' shows, in display order:
        //   [1] aaa/a3:free   [2] ollama/llama3:latest   [3] ollama/qwen3:latest
        List<NaruModelKey> displayed = session.registry().modelsKeys(session).stream()
                .filter(k -> k.provider().equals("ollama") || k.model().endsWith(":free"))
                .collect(Collectors.toList());
        session.setListedModels(displayed);

        Assertions.assertEquals(3, displayed.size());
        Assertions.assertEquals("aaa/a-paid", session.registry().modelsKeys(session).get(0).toString(),
                "precondition: the raw catalog really does start with a filtered-out model");
        // '/model use <n>' must be the nth *displayed* row, not the nth of the whole catalog
        Assertions.assertEquals("aaa/a3:free", keyOf(session, "1"));
        Assertions.assertEquals("ollama/llama3:latest", keyOf(session, "2"));
        Assertions.assertEquals("ollama/qwen3:latest", keyOf(session, "3"));
    }

    @Test
    public void outOfRangeIndexDoesNotSilentlySelectAnotherModel() {
        session.registry().registerModelProvider(new FakeProvider("openrouter",
                Arrays.asList("a-paid", "b-paid", "c:free"), true));
        session.registry().registerModelProvider(new FakeProvider("ollama",
                Arrays.asList("qwen3:latest", "llama3:latest"), true));
        session.setListedModels(List.of(new NaruModelKey("ollama", "qwen3:latest")));

        // index 4 is beyond the last listing: error, never 'a-paid'
        Assertions.assertFalse(session.findModel("4").isPresent());
    }

    @Test
    public void indexFallsBackToCatalogWhenNothingWasListed() {
        session.registry().registerModelProvider(new FakeProvider("ollama",
                Arrays.asList("b-model", "a-model"), true));

        Assertions.assertTrue(session.listedModels().isEmpty());
        Assertions.assertEquals("ollama/a-model", keyOf(session, "1"));
        Assertions.assertEquals("ollama/b-model", keyOf(session, "2"));
        Assertions.assertFalse(session.findModel("3").isPresent());
    }

    @Test
    public void namesAndAliasesWinOverIndexes() {
        session.registry().registerModelProvider(new FakeProvider("ollama",
                Arrays.asList("a-model", "b-model"), true));
        session.registry().registerModelProvider(new FakeProvider("openrouter",
                Arrays.asList("c:free"), true));
        session.addModelAlias("cheap", new NaruModelConfig("openrouter", "c:free"));
        session.setListedModels(List.of(new NaruModelKey("ollama", "b-model")));

        Assertions.assertEquals("openrouter/c:free", keyOf(session, "cheap"));
        Assertions.assertEquals("ollama/a-model", keyOf(session, "a-model"));
        Assertions.assertEquals("ollama/a-model", keyOf(session, "ollama/a-model"));
    }

    @Test
    public void duplicateModelIdsAreListedOnce() {
        // a custom endpoint may re-declare a model id already owned by another provider
        session.registry().registerModelProvider(new FakeProvider("ollama",
                Arrays.asList("a-model", "a-model", "a-model"), true));

        Assertions.assertEquals(1, session.registry().modelsKeys(session).size());
        Assertions.assertEquals(1, session.registry().modelsInfos(session).size());
    }

    private static String keyOf(NaruSession session, String ref) {
        return session.findModel(ref).orNull().key().toString();
    }

    /**
     * Minimal offline provider: no HTTP, no env, deterministic ids.
     */
    private static class FakeProvider implements NaruModelProvider {

        private final String name;
        private final List<String> modelIds;
        private final boolean available;

        FakeProvider(String name, List<String> modelIds, boolean available) {
            this.name = name;
            this.modelIds = modelIds;
            this.available = available;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public NOptional<String> apiKey(NaruSession session) {
            return NOptional.ofEmpty();
        }

        @Override
        public List<String> findModelIds(NaruSession session) {
            return new ArrayList<>(modelIds);
        }

        @Override
        public boolean isAvailable(NaruSession session) {
            return available;
        }

        @Override
        public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
            return NOptional.of(new NaruModelProtocol() {
                @Override
                public NaruResponse chat(NaruModelRequest request, NaruTask task) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public NaruModelCapabilities getCapabilities() {
                    return new NaruModelCapabilities() {
                        @Override
                        public long contextLength() {
                            return 8192;
                        }

                        @Override
                        public boolean isVision() {
                            return false;
                        }

                        @Override
                        public boolean isTools() {
                            return true;
                        }

                        @Override
                        public boolean isThinking() {
                            return false;
                        }

                        @Override
                        public boolean isEmbedding() {
                            return false;
                        }

                        @Override
                        public boolean isTextOnly() {
                            return false;
                        }

                        @Override
                        public Set<String> keys() {
                            return Collections.singleton("tools");
                        }

                        @Override
                        public NElement toElement() {
                            return NElement.ofNull();
                        }
                    };
                }
            });
        }

        @Override
        public void setParam(String name, String value) {
        }

        @Override
        public NOptional<String> getParam(String name) {
            return NOptional.ofEmpty();
        }

        @Override
        public Set<String> getParamNames() {
            return Collections.emptySet();
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void setEnabled(boolean enabled) {
        }
    }
}
