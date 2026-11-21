package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.model.NaruModelContext;
import net.thevpc.naru.api.agent.NaruInput;
import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionHandle;
import net.thevpc.naru.api.agent.NaruTurn;
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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        NaruWorkspace ws = workspace();
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

    /**
     * A message submitted while the session is about to hibernate belongs to the conversation.
     * The idle check that decides to hibernate runs outside the lock {@code submit} takes, so
     * a submit could land after it: the session then hibernated with that input in flight, and
     * the turn failed or the text was dropped. The hook holds the worker in that window.
     */
    @Test
    @Timeout(600)
    public void aMessageSubmittedAsTheSessionHibernatesIsNotLost() throws Exception {
        NaruWorkspace ws = workspace();
        String id = "conv-" + UUID.randomUUID();
        CountDownLatch inWindow = new CountDownLatch(1);
        CountDownLatch submitted = new CountDownLatch(1);
        AtomicBoolean once = new AtomicBoolean();
        NaruSessionHandleImpl.beforeHibernate = () -> {
            if (once.compareAndSet(false, true)) {
                inWindow.countDown();
                try {
                    submitted.await(1, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        try {
            NaruTurn first = handle(ws, id, new Answer()).submit(NaruInput.of("hello"));
            Assertions.assertTrue(inWindow.await(1, TimeUnit.MINUTES), "the first turn never got idle");
            NaruTurn second = handle(ws, id, new Answer()).submit(NaruInput.of("again"));
            submitted.countDown();

            NaruTurnResult r1 = first.result().get(5, TimeUnit.MINUTES);
            NaruTurnResult r2 = second.result().get(5, TimeUnit.MINUTES);
            assertNull(r1.failure());
            assertNull(r2.failure());
            assertEquals(NaruTurnStatus.HIBERNATED, r2.status());
        } finally {
            NaruSessionHandleImpl.beforeHibernate = null;
        }

        // "again" reached the conversation: the next turn is the third user message
        assertEquals("user turns seen: 3", turn(ws, id, "and again").answer.text());
    }

    /**
     * A session being saved for hibernation is not busy: its turn is over. A message that
     * arrives during the save used to get NaruSessionBusyException whatever the busy policy;
     * it now waits for the save and is served from the session the store holds. The hook
     * keeps the save open until the submitting thread is parked waiting on it.
     */
    @Test
    @Timeout(600)
    public void aMessageArrivingDuringTheSaveWaitsAndIsServed() throws Exception {
        NaruWorkspace ws = workspace();
        String id = "conv-" + UUID.randomUUID();
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean once = new AtomicBoolean();
        NaruSessionHandleImpl.whileHibernating = () -> {
            if (once.compareAndSet(false, true)) {
                saving.countDown();
                try {
                    release.await(1, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        try {
            NaruTurn first = handle(ws, id, new Answer()).submit(NaruInput.of("hello"));
            Assertions.assertTrue(saving.await(1, TimeUnit.MINUTES), "the first turn never hibernated");

            Answer answer = new Answer();
            AtomicReference<Object> outcome = new AtomicReference<>();
            Thread submitter = new Thread(() -> {
                try {
                    outcome.set(handle(ws, id, answer).submit(NaruInput.of("again")).result().get(5, TimeUnit.MINUTES));
                } catch (Throwable e) {
                    outcome.set(e);
                }
            });
            submitter.start();
            // parked on the save means it is waiting; finished means it was turned away
            long until = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
            while (submitter.getState() != Thread.State.TIMED_WAITING
                    && submitter.getState() != Thread.State.TERMINATED && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            release.countDown();
            submitter.join(TimeUnit.MINUTES.toMillis(5));

            assertNull(first.result().get(5, TimeUnit.MINUTES).failure());
            Assertions.assertInstanceOf(NaruTurnResult.class, outcome.get(), () -> "the submit failed: " + outcome.get());
            NaruTurnResult second = (NaruTurnResult) outcome.get();
            assertNull(second.failure());
            assertEquals(NaruTurnStatus.HIBERNATED, second.status());
            assertEquals("user turns seen: 2", answer.text());
        } finally {
            NaruSessionHandleImpl.whileHibernating = null;
            release.countDown();
        }
    }

    private static NaruWorkspace workspace() {
        NaruWorkspace ws = NaruWorkspace.of();
        NPath dir = NPath.ofTempFolder("naru-handle-turn-" + System.nanoTime());
        dir.mkdirs();
        ws.projectDirectory(dir);
        return ws;
    }

    private record Turn(NaruTurnResult result, Answer answer) {
    }

    private static NaruSessionHandle handle(NaruWorkspace ws, String id, Answer answer) {
        return ws.newSession()
                .id(id)
                .interactive()
                .task(NaruTaskSpec.of().name("chat").model(CountingProvider.NAME + "/m"))
                .interaction(answer)
                .hibernateAfter(Duration.ZERO)
                .buildHandle();
    }

    private static Turn turn(NaruWorkspace ws, String id, String text) throws Exception {
        Answer answer = new Answer();
        NaruTurnResult result = handle(ws, id, answer)
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
            public NaruResponse chat(NaruModelRequest request, NaruModelContext context) {
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
        public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruModelContext context) {
            return NOptional.of(protocol);
        }

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public NOptional<String> apiKey(NaruModelContext context) {
            return NOptional.ofEmpty();
        }

        @Override
        public List<String> findModelIds(NaruModelContext context) {
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
