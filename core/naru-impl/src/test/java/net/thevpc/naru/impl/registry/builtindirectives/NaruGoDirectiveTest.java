package net.thevpc.naru.impl.registry.builtindirectives;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * What {@code /go} does when there is nothing to continue.
 *
 * <p>{@code /go} means "call the model without adding a prompt", which is a useful
 * thing to want and only works if the history already holds a turn. These pin down the
 * three answers when it does not: go, kick the model off, or say why not -- and
 * specifically that the engine says so itself instead of finding out from a 400.
 */
public class NaruGoDirectiveTest {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

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

    private RecordingProtocol protocol;
    private NaruSessionImpl session;

    @BeforeEach
    public void setUp() {
        protocol = new RecordingProtocol();
        NaruAgentImpl agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-go-" + System.nanoTime()));
        session = new NaruSessionImpl(agent, agent.projectDirectory(),
                new NaruStreamInteraction(o -> {
                }), false, null, null, null, null);
        session.registry().registerModelProvider(new FakeProvider(protocol));
    }

    private NaruDirective go() {
        return new NaruGoDirective();
    }

    private NaruStmtResult go(NaruTask task) {
        return go().execute(new NaruDirectiveCallContextImpl("go", null, task));
    }

    /**
     * A task holding exactly the given conversation. Its system prompt is added by the
     * session, so the context a real run would send is the history plus instructions.
     */
    private NaruTask task(NaruMessage... history) {
        NaruTask task = session.newTask(NaruTaskSpec.of());
        task.setHistory(List.of(history));
        return task;
    }

    /**
     * The first {@code /go} of a session is the case this directive has to handle well,
     * because it is the one users hit by accident: a context of nothing but instructions
     * is not a request, and sending it as one is a 400 from the far side.
     */
    @Test
    @Timeout(60)
    public void goOnASessionWithOnlyASystemPromptKicksTheModelOff() {
        NaruStmtResult result = go(task(NaruMessage.system("you are naru")));

        Assertions.assertEquals(0, result.exitCode(),
                "a system-only context is something to do, not an error: " + result.errorValue());
    }

    /**
     * The kickoff is a real user turn. Anything less sends the request the user was
     * trying to avoid in the first place.
     */
    @Test
    @Timeout(60)
    public void theKickoffIsAPromptTheModelCanAnswer() {
        NaruTask task = task(NaruMessage.system("you are naru"));
        Assertions.assertEquals(0, go(task).exitCode());

        run(task);
        Assertions.assertEquals(1, protocol.chatCalls, "the kickoff has to reach the model");
        List<NaruMessage> sent = protocol.lastRequest.messages();
        NaruMessage last = sent.get(sent.size() - 1);
        Assertions.assertEquals(NaruGoDirective.KICKOFF, last.getContent(),
                "the model must be handed a turn, not an empty one");
    }

    @Test
    @Timeout(60)
    public void aContextEndingInAToolResultNothingAskedForIsRefusedWithTheReason() {
        NaruTask task = task(
                NaruMessage.user("read A.java"),
                NaruMessage.tool("read_file", "call-42", "contents"));

        NaruStmtResult result = go(task);

        Assertions.assertNotEquals(0, result.exitCode(), "this tail cannot be continued");
        Assertions.assertTrue(String.valueOf(result.errorValue()).contains("cannot go"),
                String.valueOf(result.errorValue()));
        Assertions.assertTrue(String.valueOf(result.errorValue()).contains("call-42"),
                "the refusal has to say what is wrong: " + result.errorValue());
        Assertions.assertEquals(0, protocol.chatCalls, "nothing may be sent");
    }

    /**
     * Nothing at all is not the same as nothing but instructions, and the two are easy to
     * conflate now that the system-only case is kickoff rather than refusal: a context
     * with no messages has no model to talk to, so it stays a refusal.
     *
     * <p>Reached through a stub task because no real run can produce it -- every task is
     * given a system prompt when it is created -- which is exactly why it needs a test:
     * nothing else exercises the branch.
     */
    @Test
    @Timeout(60)
    public void aContextWithNothingInItAtAllIsStillNothingToDo() {
        NaruTask empty = emptyContextTask();

        NaruStmtResult result = go(empty);

        Assertions.assertNotEquals(0, result.exitCode());
        Assertions.assertTrue(String.valueOf(result.errorValue()).contains("nothing to do"),
                String.valueOf(result.errorValue()));
        Assertions.assertEquals(0, protocol.chatCalls, "nothing may be sent");
    }

    /** A task whose context is empty, answering only what {@code /go} asks of it. */
    private NaruTask emptyContextTask() {
        NaruModelRequest empty = new NaruModelRequest(List.of(), new HashMap<>());
        return (NaruTask) java.lang.reflect.Proxy.newProxyInstance(
                NaruTask.class.getClassLoader(),
                new Class<?>[]{NaruTask.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "inputBuffer" -> args == null || args.length == 0 ? "" : null;
                    case "context" -> empty;
                    case "log" -> null;
                    case "toString" -> "stub-task";
                    case "hashCode" -> 0;
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError(
                            "/go asked the task for something it should not need: " + method.getName());
                });
    }

    /**
     * An answer already given is not something to repeat: {@code /go} used to send it
     * anyway, as a request ending in an assistant message.
     */
    @Test
    @Timeout(60)
    public void aContextEndingInAnAnswerIsNothingToDo() {
        NaruStmtResult result = go(task(
                NaruMessage.user("hi"),
                NaruMessage.assistant("hello")));

        Assertions.assertNotEquals(0, result.exitCode());
        Assertions.assertTrue(String.valueOf(result.errorValue()).contains("nothing to do"),
                String.valueOf(result.errorValue()));
    }

    /**
     * The ordinary case: there is a question the model was asked and not yet answered.
     * Strictness must not turn that into a refusal.
     */
    @Test
    @Timeout(60)
    public void aPendingQuestionIsContinuedRatherThanRefused() {
        NaruStmtResult result = go(task(
                NaruMessage.system("you are naru"),
                NaruMessage.user("what is 2+2?")));

        Assertions.assertEquals(0, result.exitCode(), String.valueOf(result.errorValue()));
    }

    /**
     * A prompt typed before {@code /go} is a turn in its own right, so the history is
     * not what decides whether the call goes out.
     */
    @Test
    @Timeout(60)
    public void aTypedPromptIsSentWhateverTheHistoryHolds() {
        NaruTask task = task(NaruMessage.system("you are naru"));
        task.inputBuffer("summarise the project");

        Assertions.assertEquals(0, go(task).exitCode());
    }

    /**
     * Release a held task and wait for it.
     *
     * <p>{@code /go} does not call the model itself: it prepends a model-call statement
     * and returns. So checking what it decided means letting the statement run.
     */
    private void run(NaruTask task) {
        session.start();
        task.unhold();
        Assertions.assertTrue(task.await(PATIENCE), "/go produced no result within " + PATIENCE);
    }

    // ── stubs ────────────────────────────────────────────────────────────────

    private static class RecordingProtocol implements NaruModelProtocol {
        int chatCalls;
        NaruModelRequest lastRequest;

        @Override
        public String providerName() {
            return "fake";
        }

        @Override
        public NaruResponse chat(NaruModelRequest request, NaruTask task) {
            chatCalls++;
            lastRequest = request;
            NaruResponse r = new NaruResponse();
            r.setMessage(NaruMessage.assistant("answered"));
            return r;
        }

        @Override
        public NaruModelCapabilities getCapabilities() {
            return new StubCapabilities();
        }
    }

    private static final class StubCapabilities implements NaruModelCapabilities {
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
            return Set.of("text-only");
        }

        @Override
        public NElement toElement() {
            return NElement.ofObjectBuilder()
                    .set("streaming", false)
                    .set("thinking", false)
                    .build();
        }
    }

    private static final class FakeProvider implements NaruModelProvider {
        private final NaruModelProtocol protocol;

        FakeProvider(NaruModelProtocol protocol) {
            this.protocol = protocol;
        }

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
}