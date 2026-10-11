package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/**
 * The per-model {@code .naru/models/*.md} sources and their precedence.
 *
 * <p>An extension ships a default for a known-difficult model as
 * {@code META-INF/naru/models/<model>.md}; this proves that resource is picked
 * up for the selected model (the CLASSPATH source) and that a project file at
 * {@code .naru/models/<model>.md} still wins over it (PROJECT loads later, so
 * its front matter overrides the jar's). The test resource {@code res-mech-probe}
 * exists only here, so no other test's model name collides with it.
 */
public class NaruModelResourceResolutionTest {

    private static final String MODEL = "res-mech-probe";

    @BeforeAll
    public static void setUpWorkspace() {
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Throwable e) {
            try {
                NWorkspace ws = Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    @Test
    public void aClasspathModelDefaultIsResolvedIntoTheContextEnv() {
        NaruTask task = taskFor(tempProject());

        NaruModelRequest context = task.context(NaruSource.CLASSPATH);

        Assertions.assertEquals("from-classpath",
                stringValue(context.env().get("probe_key")),
                "the jar-shipped default's front matter must land in the request env");
        Assertions.assertEquals("true",
                stringValue(context.env().get("emulate_tool_calls")));
        boolean bodyIncluded = context.messages().stream()
                .anyMatch(m -> m.getContent() != null
                        && m.getContent().contains("### AGENT CLASSPATH:")
                        && m.getContent().contains("classpath default body"));
        Assertions.assertTrue(bodyIncluded,
                "the resource body must be contributed as a classpath system message");
    }

    @Test
    public void aProjectModelDefaultOverridesTheClasspathOne() throws Exception {
        NPath projectDir = tempProject();
        NPath modelFile = projectDir.resolve(".naru/models").resolve(MODEL + ".md");
        modelFile.parent().mkdirs();
        modelFile.writeString(
                "---\nprobe_key: \"from-project\"\n---\nThis is the project override body.");
        NaruTask task = taskFor(projectDir);

        NaruModelRequest context = task.context(NaruSource.CLASSPATH, NaruSource.PROJECT);

        Assertions.assertEquals("from-project",
                stringValue(context.env().get("probe_key")),
                "a project .naru/models file must beat the jar-shipped default");
        long projectMessages = context.messages().stream()
                .filter(m -> m.getContent() != null && m.getContent().contains("### PROJECT CONTEXT:"))
                .count();
        Assertions.assertEquals(1, projectMessages);
    }

    private NaruTask taskFor(NPath projectDir) {
        NaruAgentImpl agent = new NaruAgentImpl();
        agent.projectDirectory(projectDir);
        NaruSessionImpl session = new NaruSessionImpl(agent, projectDir,
                new NaruStreamInteraction(o -> {
                }), false, null, null, null, null);
        session.registry().registerModelProvider(new FakeProvider());
        NaruTask task = session.newTask(NaruTaskSpec.of());
        task.setModel(new NaruModelConfig("fake", MODEL));
        return task;
    }

    private static NPath tempProject() {
        return NPath.ofTempFolder("naru-mres-" + System.nanoTime());
    }

    private static String stringValue(NElement e) {
        return e == null ? null : e.asStringValue().orNull();
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