package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/**
 * Model resolution belongs to the task and happens lazily.
 *
 * <p>Task creation used to resolve the model up front -- following the spec hint,
 * or the session's best fit -- and threw "missing model" when nothing answered.
 * Resolution is now {@link NaruTask#model()}'s business, so a machine with no
 * model installed can still create tasks and report the problem where the prompt
 * actually runs, and a child task follows its parent's model instead of taking a
 * birth snapshot.
 */
public class NaruLazyModelResolutionTest {

    private static final String MODEL = "res-launch-probe";

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    @Test
    public void newTaskDoesNotResolveTheModelEagerly() {
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(tempProject());
        NaruSession session = agent.newSession()
                .directory(tempProject())
                .interaction(new NaruStreamInteraction(o -> {
                }))
                .configureDefaults(false)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
        // deliberately no provider registered and no model anywhere
        NaruTask task = session.newTask(NaruTaskSpec.of());
        Assertions.assertNotNull(task, "a task with no resolvable model must still be created");
        Assertions.assertNull(task.model(),
                "resolution must be lazy: model() reports the absence instead of newTask throwing");
    }

    @Test
    public void anExplicitModelWinsOverEverythingElse() {
        NaruTask task = taskFor(tempProject());
        NaruModelConfig explicit = new NaruModelConfig("fake", "explicit-probe");
        task.setModel(explicit);
        Assertions.assertEquals(explicit.key(), task.model().key(),
                "setModel must beat the spawn hint and the project env");
    }

    @Test
    public void aSpawnHintIsResolvedAtFirstUse() {
        NaruTask task = taskFor(tempProject(), NaruTaskSpec.of().model(MODEL));
        Assertions.assertEquals("res-launch-probe", task.model().model(),
                "the spawn-time hint resolves into the registered model when first read");
    }

    @Test
    public void aChildInheritsItsParentModelByDefault() {
        NaruSession session = sessionFor(tempProject(), true);
        NaruTask parent = session.newTask(NaruTaskSpec.of());
        parent.setModel(new NaruModelConfig("fake", "parent-probe"));

        NaruTask child = session.newTask(NaruTaskSpec.of().parentId(parent.id()));
        Assertions.assertEquals(parent.model().key(), child.model().key(),
                "a child that never picks a model follows its parent");
        Assertions.assertEquals("parent-probe", child.model().model());

        child.setModel(new NaruModelConfig("fake", "child-probe"));
        Assertions.assertEquals("child-probe", child.model().model(),
                "a child that picks its own model keeps it");
    }

    @Test
    public void projectEnvModelFeedsTheBestFit() {
        NaruTask task = taskFor(tempProject());
        task.session().setProjectEnv("model",
                new NaruModelConfig("fake", MODEL).toElement(),
                NaruVisibility.PRIVATE);
        Assertions.assertEquals(MODEL, task.model().model(),
                "/model use-global writes this store; the lazy resolution must honor it");
    }

    @Test
    public void nothingIsGuessedWhenTheUserHasNotChosenAModel() {
        NaruTask task = taskFor(tempProject());
        // 3720c87 removed the best-fit fallback deliberately: a model is defined by the user, at
        // session creation or with /model use. One tools-capable model in the registry is not the
        // user's choice, so the honest answer here is null and the turn says so out loud.
        Assertions.assertNull(task.model(),
                "a registered tools-capable model must never be picked by itself");
    }

    // ── harness (provider registration optional) ─────────────────────────────

    private NaruTask taskFor(NPath projectDir) {
        return taskFor(projectDir, NaruTaskSpec.of());
    }

    private NaruTask taskFor(NPath projectDir, NaruTaskSpec spec) {
        return sessionFor(projectDir, true).newTask(spec);
    }

    private NaruSession sessionFor(NPath projectDir, boolean withProvider) {
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(projectDir);
        NaruSession session = agent.newSession()
                .directory(projectDir)
                .interaction(new NaruStreamInteraction(o -> {
                }))
                .configureDefaults(false)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
        if (withProvider) {
            session.registry().registerModelProvider(new FakeProvider());
        }
        return session;
    }

    private static NPath tempProject() {
        return NPath.ofTempFolder("naru-lazymodel-" + System.nanoTime());
    }

    private static final class FakeProvider implements NaruModelProvider {

        @Override
        public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
            return NOptional.of(new NaruModelProtocol() {
                @Override
                public String providerName() {
                    return "fake";
                }

                @Override
                public NaruResponse chat(NaruModelRequest request, NaruTask task) {
                    NaruResponse r = new NaruResponse();
                    r.setMessage(NaruMessage.assistant("ok"));
                    return r;
                }

                @Override
                public NaruModelCapabilities getCapabilities() {
                    return new NaruModelCapabilities() {
                        @Override
                        public long contextLength() {
                            return 4096;
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
                            return true;
                        }

                        @Override
                        public boolean isStreaming() {
                            return false;
                        }

                        @Override
                        public NaruCachingMode cachingMode() {
                            return NaruCachingMode.NONE;
                        }

                        @Override
                        public Set<String> keys() {
                            return Set.of();
                        }

                        @Override
                        public NElement toElement() {
                            return NElement.ofTupleBuilder("Capabilities").build();
                        }
                    };
                }
            });
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public NOptional<String> apiKey(NaruSession session) {
            return NOptional.ofEmpty();
        }

        @Override
        public List<String> findModelIds(NaruSession session) {
            return List.of(MODEL);
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
            return Set.of();
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
