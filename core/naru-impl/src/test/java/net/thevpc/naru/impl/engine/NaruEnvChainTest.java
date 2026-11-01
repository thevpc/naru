package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.agent.NaruTaskConfig;
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
 * The settings chain, from the store your hand is on to the one the config files
 * hold: task env, then -- only for the inherited lookups -- session env, the
 * per-process agent env, and the project settings.
 *
 * <p>Two lookups and one shared chain:
 *
 * <ul>
 *   <li>{@link NaruTask#getTaskEnv(String, boolean)} and
 *       {@link NaruSession#getSessionEnv(String, boolean)} are the <em>simple</em>
 *       walk asked of the API: each holds its own store first and asks the next one
 *       down for the rest. A tool reads a setting once through the task and is
 *       overridable at every level below.</li>
 *   <li>{@link NaruTaskConfig#resolve} is the <em>reporting</em> walk: same order,
 *       but it labels which store answered, so "the setting says true and nothing
 *       happened" can name the file. Its per-store steps must stay per-store now
 *       that the simple walk reaches the bottom of the chain, or an agent-env value
 *       would be labeled "task".</li>
 * </ul>
 */
public class NaruEnvChainTest {

    private static final String MODEL = "env-chain-probe";
    private static final String P = "env.chain.probe";

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    // ── the simple walk: store, then the next one down ──────────────────────

    @Test
    public void taskEnvWinsOverTheWiderStores() {
        NaruTask task = taskFor(tempProject());
        task.session().agent().agentEnv().put(P, NElement.ofString("from-agent"), NaruVisibility.PRIVATE);
        task.session().setSessionEnv(P, "from-session");
        task.setTaskEnv(P, "from-task");

        Assertions.assertEquals("from-task", task.getTaskEnv(P, true).orNull(),
                "the task env must win the inherited lookup");
        Assertions.assertEquals("from-session", task.session().getSessionEnv(P, true).orNull(),
                "the session lookup holds its own store before looking down");
    }

    @Test
    public void theInheritedLookupWalksSessionThenAgentThenSettings() {
        NaruTask sessionOnly = taskFor(tempProject());
        sessionOnly.session().setSessionEnv(P, "from-session");
        Assertions.assertEquals("from-session", sessionOnly.session().getSessionEnv(P, true).orNull());
        Assertions.assertEquals("from-session", sessionOnly.getTaskEnv(P, true).orNull(),
                "the task lookup must hand the inherited half to the session");

        NaruTask agentOnly = taskFor(tempProject());
        agentOnly.session().agent().agentEnv().put(P, NElement.ofString("from-agent"), NaruVisibility.PRIVATE);
        Assertions.assertFalse(agentOnly.session().getSessionEnv(P).isPresent(),
                "the plain session lookup must not leak into the agent env");
        Assertions.assertEquals("from-agent",
                NaruTaskConfig.toText(agentOnly.session().getSessionEnv(P, true).orNull()),
                "the inherited session lookup finds the per-process agent env");
        Assertions.assertEquals("from-agent",
                NaruTaskConfig.toText(agentOnly.getTaskEnv(P, true).orNull()));

        NaruTask configOnly = taskFor(tempProject());
        configOnly.session().setProjectEnv(P, NElement.ofString("from-config"), NaruVisibility.PRIVATE);
        Assertions.assertEquals("from-config",
                NaruTaskConfig.toText(configOnly.session().getSessionEnv(P, true).orNull()),
                "the inherited session lookup reaches the project settings last");
        Assertions.assertEquals("from-config",
                NaruTaskConfig.toText(configOnly.getTaskEnv(P, true).orNull()));
    }

    @Test
    public void theNonInheritedLookupStaysOnItsOwnStore() {
        NaruTask task = taskFor(tempProject());
        task.session().agent().agentEnv().put(P, NElement.ofString("from-agent"), NaruVisibility.PRIVATE);
        Assertions.assertFalse(task.session().getSessionEnv(P, false).isPresent(),
                "session env without inheritance must not reach the agent env");

        task.session().setSessionEnv(P, "from-session");
        Assertions.assertFalse(task.getTaskEnv(P, false).isPresent(),
                "task env without inheritance must not reach the session env");
    }

    // ── the reporting walk keeps its per-store labels ───────────────────────

    @Test
    public void taskConfigScopeReportingSurvivesTheChainedTaskLookup() {
        NaruTask agentTask = taskFor(tempProject());
        agentTask.session().agent().agentEnv().put(P, NElement.ofString("agent"), NaruVisibility.PRIVATE);
        NaruTaskConfig.Resolved agentValue = NaruTaskConfig.resolve(agentTask, P).get();
        Assertions.assertEquals(NaruTaskConfig.SCOPE_AGENT, agentValue.scope(),
                "an agent-env value must not be labeled 'task' now that getTaskEnv(key, true) walks the whole chain");

        NaruTask configTask = taskFor(tempProject());
        configTask.session().setProjectEnv(P, NElement.ofString("cfg"), NaruVisibility.PRIVATE);
        NaruTaskConfig.Resolved configValue = NaruTaskConfig.resolve(configTask, P).get();
        Assertions.assertEquals(NaruTaskConfig.SCOPE_CONFIG, configValue.scope());
        Assertions.assertEquals(NaruVisibility.PRIVATE, configValue.visibility());

        NaruTask sessionTask = taskFor(tempProject());
        sessionTask.session().setSessionEnv(P, "session");
        Assertions.assertEquals(NaruTaskConfig.SCOPE_SESSION,
                NaruTaskConfig.resolve(sessionTask, P).get().scope());
    }

    // ── validating reads ────────────────────────────────────────────────────

    @Test
    public void positiveIntSettingsRejectZeroNegativeAndNonNumbers() {
        String key = "naru.fs.test.positiveProbe";
        NaruTask task = taskFor(tempProject());
        int def = 42;
        Assertions.assertEquals(def, NaruTaskConfig.getPositiveInt(task, key, def), "unset stays the default");

        task.setTaskEnv(key, "0");
        Assertions.assertEquals(def, NaruTaskConfig.getPositiveInt(task, key, def), "0 must fall back");
        task.setTaskEnv(key, "-3");
        Assertions.assertEquals(def, NaruTaskConfig.getPositiveInt(task, key, def), "negative must fall back");
        task.setTaskEnv(key, "abc");
        Assertions.assertEquals(def, NaruTaskConfig.getPositiveInt(task, key, def), "non-numeric must fall back");
        task.setTaskEnv(key, 7);
        Assertions.assertEquals(7, NaruTaskConfig.getPositiveInt(task, key, def));
        task.setTaskEnv(key, "125");
        Assertions.assertEquals(125, NaruTaskConfig.getPositiveInt(task, key, def));
    }

    @Test
    public void rangedIntSettingsRejectOutOfRangeValues() {
        String key = "naru.fs.test.rangeProbe";
        NaruTask task = taskFor(tempProject());
        int def = 10;
        Assertions.assertEquals(def, NaruTaskConfig.getIntInRange(task, key, def, 1, 100));
        task.setTaskEnv(key, "0");
        Assertions.assertEquals(def, NaruTaskConfig.getIntInRange(task, key, def, 1, 100), "below min must fall back");
        task.setTaskEnv(key, "150");
        Assertions.assertEquals(def, NaruTaskConfig.getIntInRange(task, key, def, 1, 100), "above max must fall back");
        task.setTaskEnv(key, "50");
        Assertions.assertEquals(50, NaruTaskConfig.getIntInRange(task, key, def, 1, 100));
    }

    // ── harness ─────────────────────────────────────────────────────────────

    private NaruTask taskFor(NPath projectDir) {
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
        session.registry().registerModelProvider(new FakeProvider());
        NaruTask task = session.newTask(NaruTaskSpec.of());
        task.setModel(new NaruModelConfig("fake", MODEL));
        return task;
    }

    private static NPath tempProject() {
        return NPath.ofTempFolder("naru-envchain-" + System.nanoTime());
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
