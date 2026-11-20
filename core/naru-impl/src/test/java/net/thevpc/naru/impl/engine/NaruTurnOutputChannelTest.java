package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruToolCallEvent;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where a turn's user-visible text is delivered.
 *
 * <p>Two channels carry a turn out of the engine: {@code NaruInteraction}, which a host
 * watches live, and the task history, which is the durable record a host re-reads after a
 * reload. The messaging change put every turn notice and every non-streamed answer into
 * history only, which silently broke every live host that does not tail the store — the
 * terminal, the command line, and an embedder that implements {@code NaruInteraction} and
 * nothing else. These tests pin both halves of that contract: the answer reaches the
 * interaction exactly once, and it is stored exactly once.
 */
public class NaruTurnOutputChannelTest {

    private static final Duration PATIENCE = Duration.ofSeconds(30);
    private static final String ANSWER = "The capital of Tunisia is Tunis.";

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static final class Recorder implements NaruInteraction {
        final List<String> lines = new ArrayList<>();

        @Override
        public void open(NaruSession session) {
        }

        @Override
        public void requestInput(NaruInputRequest request) {
        }

        @Override
        public void write(NaruLogMode mode, NMsg message) {
            lines.add(mode + " " + message);
        }

        @Override
        public void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
            lines.add(mode + " " + fragment + (end ? "[end]" : ""));
        }

        @Override
        public void reportToolCall(NaruToolCallEvent event) {
        }

        @Override
        public void close() {
        }

        List<String> with(String needle) {
            return lines.stream().filter(l -> l.contains(needle)).collect(Collectors.toList());
        }

        String text() {
            return String.join("\n", lines);
        }
    }

    /** A plain, non-streaming completion: the shape that has no other route to a host. */
    private static final class AnswerProtocol implements NaruModelProtocol {
        @Override
        public String providerName() {
            return "fake";
        }

        @Override
        public NaruResponse chat(NaruModelRequest request, NaruTask task) {
            NaruResponse r = new NaruResponse();
            r.setMessage(NaruMessage.assistant(ANSWER));
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
                    return false;
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
    }

    private static final class FakeProvider implements NaruModelProvider {
        private final AnswerProtocol protocol = new AnswerProtocol();

        @Override
        public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
            return NOptional.of(protocol);
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
            return List.of("fake-model");
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

    private static NaruSession sessionOf(Recorder recorder) {
        NaruWorkspace agent = NaruWorkspace.of();
        NPath dir = NPath.ofTempFolder("naru-output-channel-" + System.nanoTime());
        dir.mkdirs();
        agent.projectDirectory(dir);
        NaruSession s = agent.newSession()
                .sessionDir(dir)
                .interaction(recorder)
                .configureDefaults(false)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
        s.registry().registerModelProvider(new FakeProvider());
        return s;
    }

    @Test
    @Timeout(60)
    public void theAnswerReachesTheInteractionExactlyOnce() {
        Recorder recorder = new Recorder();
        NaruSession s = sessionOf(recorder);
        NaruTask task = s.newTask(NaruTaskSpec.of().statements("what is the capital of tunisia"));
        task.setModel(new NaruModelConfig("fake", "fake-model"));
        s.start();
        task.unhold();
        assertTrueFinished(task, recorder);

        assertEquals(1, recorder.with(ANSWER).size(),
                "a non-streamed answer must be drawn once for a live host, saw: " + recorder.text());
    }

    @Test
    @Timeout(60)
    public void theAnswerIsStoredExactlyOnce() {
        Recorder recorder = new Recorder();
        NaruSession s = sessionOf(recorder);
        NaruTask task = s.newTask(NaruTaskSpec.of().statements("what is the capital of tunisia"));
        task.setModel(new NaruModelConfig("fake", "fake-model"));
        s.start();
        task.unhold();
        assertTrueFinished(task, recorder);

        List<NaruMessage> copies = task.history().stream()
                .filter(m -> m.getRole() == NaruRole.assistant || m.getRole() == NaruRole.engine)
                .filter(m -> ANSWER.equals(m.getFilteredContent()))
                .collect(Collectors.toList());
        assertEquals(1, copies.size(),
                "the answer must appear once in history; a second copy renders it twice on reload. history="
                        + task.history().stream()
                        .map(m -> m.getRole() + "/" + m.getParts().size() + "p[" + m.getFilteredContent() + "]")
                        .collect(Collectors.toList())
                        + "\ninteraction:\n" + recorder.text());
    }

    private static void assertTrueFinished(NaruTask task, Recorder recorder) {
        org.junit.jupiter.api.Assertions.assertTrue(task.await(PATIENCE),
                "the turn did not finish: " + recorder.text());
    }
}
