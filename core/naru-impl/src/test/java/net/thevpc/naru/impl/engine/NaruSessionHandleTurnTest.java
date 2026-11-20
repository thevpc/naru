package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruInput;
import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruTurnResult;
import net.thevpc.naru.api.agent.NaruTurnStatus;
import net.thevpc.naru.api.agent.NaruWorkspace;
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
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A backend handle runs one turn per submit and hibernates the session between turns.
 *
 * <p>Pins the round trip that did not work end to end: every turn failed on a wait without the
 * monitor, the first turn's input was dropped when the task parked before the input reached it,
 * an interactive handle never parked at all, a rehydrated session was never started, and a
 * reloaded task forgot the model it was spawned with.
 */
public class NaruSessionHandleTurnTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    @Test
    @Timeout(600)
    public void aConversationSurvivesHibernationBetweenTurns() throws Exception {
        NaruWorkspace ws = NaruWorkspace.of();
        NPath dir = NPath.ofTempFolder("naru-handle-turn-" + System.nanoTime());
        dir.mkdirs();
        ws.projectDirectory(dir);
        String id = "conv-" + UUID.randomUUID();

        Turn first = turn(ws, id, "hello");
        assertNull(first.result.failure());
        assertEquals(NaruTurnStatus.HIBERNATED, first.result.status());
        assertEquals("user turns seen: 1", first.answer.text());

        // a fresh handle: nothing is in memory, the session comes back from the store
        Turn second = turn(ws, id, "again");
        assertNull(second.result.failure());
        assertEquals(NaruTurnStatus.HIBERNATED, second.result.status());
        assertEquals("user turns seen: 2", second.answer.text());
    }

    private record Turn(NaruTurnResult result, Answer answer) {
    }

    private static Turn turn(NaruWorkspace ws, String id, String text) throws Exception {
        Answer answer = new Answer();
        NaruTurnResult result = ws.newSession()
                .id(id)
                .interactive()
                .task(NaruTaskSpec.of().name("chat").model(CountingProvider.NAME + "/m"))
                .interaction(answer)
                .hibernateAfter(Duration.ZERO)
                .buildHandle()
                .submit(NaruInput.of(text))
                .result().get(5, TimeUnit.MINUTES);
        return new Turn(result, answer);
    }

    private static final class Answer implements NaruInteraction {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void open(NaruSession session) {
        }

        @Override
        public void requestInput(NaruInputRequest request) {
        }

        @Override
        public synchronized void write(NaruLogMode mode, NMsg message) {
            if (mode == NaruLogMode.MODEL_RESPONSE) {
                text.append(message);
            }
        }

        @Override
        public synchronized void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
            if (mode == NaruLogMode.MODEL_RESPONSE) {
                text.append(fragment);
            }
        }

        @Override
        public void close() {
        }

        synchronized String text() {
            return text.toString().strip();
        }
    }

    /**
     * Answers with how many user messages the request carries, so the second turn proves the
     * first one's history was restored. Found through META-INF/services because the handle
     * builds its session itself.
     */
    public static final class CountingProvider implements NaruModelProvider {
        static final String NAME = "handle-turn-fake";

        private final NaruModelProtocol protocol = new NaruModelProtocol() {
            @Override
            public NaruResponse chat(NaruModelRequest request, NaruTask task) {
                long users = request.messages().stream().filter(m -> m.getRole() == NaruRole.user).count();
                return new NaruResponse(NaruMessage.assistant("user turns seen: " + users), true);
            }

            @Override
            public String providerName() {
                return NAME;
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
        };

        @Override
        public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
            return NOptional.of(protocol);
        }

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public NOptional<String> apiKey(NaruSession session) {
            return NOptional.ofEmpty();
        }

        @Override
        public List<String> findModelIds(NaruSession session) {
            return List.of("m");
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
