package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruInvalidHistoryException;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruToolCall;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refusing a request the history cannot support, before it is sent.
 *
 * <p>The tail of a request is assembled here rather than by the user, so a mistake in it
 * is cheap to make and expensive to read: the provider answers with a 400 whose text
 * names a wire format, and nothing anywhere says which message is at fault. These pin
 * down that the refusal happens first, that it names the thing that is wrong, and --
 * just as important -- that a tail no provider can continue from is the only thing it
 * refuses on its own.
 */
public class NaruRequestTailValidationTest {

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
        agent.projectDirectory(NPath.ofTempFolder("naru-tail-" + System.nanoTime()));
        session = new NaruSessionImpl(agent, agent.projectDirectory(),
                new NaruStreamInteraction(o -> {
                }), false, null, null, null, null);
        session.registry().registerModelProvider(new FakeProvider(protocol));
    }

    private NaruTask task() {
        return session.newTask(NaruTaskSpec.of());
    }

    private static NaruModelRequest request(NaruMessage... messages) {
        return new NaruModelRequest(List.of(messages), new HashMap<>());
    }

    private static NaruMessage assistantCalls(String callId, String name) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", "A.java");
        return NaruMessage.assistantWithToolCalls("", List.of(new NaruToolCall(callId, name, args)));
    }

    /**
     * A tool call the model made and nobody ran is a tail no model can continue from: it
     * will re-ask, and the tool will never run. Sending it is how a session ends up
     * asking the same question forever.
     */
    @Test
    public void anUnansweredToolCallIsRefusedBeforeAnythingIsSent() {
        NaruTask task = task();

        NaruInvalidHistoryException e = Assertions.assertThrows(NaruInvalidHistoryException.class,
                () -> task.chat(task.model(), request(
                        NaruMessage.user("read A.java"),
                        assistantCalls("call-1", "read_file"))));

        Assertions.assertEquals(0, protocol.chatCalls,
                "the request must be refused before the transport is asked to do anything");
        Assertions.assertTrue(e.reason().contains("read_file"),
                "the reason has to name the tool that was never answered: " + e.reason());
    }

    /**
     * The name is the whole point of the message. "history ends with tool call" leaves the
     * reader to go looking; "nothing has answered read_file yet" is actionable.
     */
    @Test
    public void theRefusalSaysWhichCallIsUnanswered() {
        NaruTask task = task();

        NaruInvalidHistoryException e = Assertions.assertThrows(NaruInvalidHistoryException.class,
                () -> task.chat(task.model(), request(assistantCalls("call-9", "write_file"))));

        Assertions.assertTrue(e.reason().contains("write_file"), e.reason());
        Assertions.assertTrue(e.getMessage().contains("write_file"),
                "the exception message must carry the reason, not just the model: " + e.getMessage());
    }

    /**
     * A result with no call behind it is history from another conversation -- a resumed
     * session, or an edited message -- and no provider accepts one.
     */
    @Test
    public void aToolResultWithNoCallBehindItIsRefused() {
        NaruTask task = task();

        NaruInvalidHistoryException e = Assertions.assertThrows(NaruInvalidHistoryException.class,
                () -> task.chat(task.model(), request(
                        NaruMessage.user("read A.java"),
                        assistantCalls("call-1", "read_file"),
                        NaruMessage.tool("read_file", "call-77", "contents"))));

        Assertions.assertEquals(0, protocol.chatCalls, "nothing may be sent");
        Assertions.assertTrue(e.reason().contains("call-77"), e.reason());
    }

    @Test
    public void aResultAnsweringACallIsSentNormally() {
        NaruTask task = task();

        task.chat(task.model(), request(
                NaruMessage.user("read A.java"),
                assistantCalls("call-1", "read_file"),
                NaruMessage.tool("read_file", "call-1", "contents")));

        Assertions.assertEquals(1, protocol.chatCalls,
                "a complete call/result pair is the normal shape of a turn and must go through");
    }

    @Test
    public void aRequestWithNoMessagesAtAllIsRefused() {
        NaruTask task = task();

        Assertions.assertThrows(NaruInvalidHistoryException.class,
                () -> task.chat(task.model(), request()));
        Assertions.assertEquals(0, protocol.chatCalls);
    }

    /**
     * The default judges only what no provider can continue from. Two consecutive user
     * turns are fine for an OpenAI-compatible endpoint and are an error for Anthropic,
     * and the default must not pick a side: a protocol that cares says so itself.
     */
    @Test
    public void theDefaultDoesNotImportAnotherProvidersRules() {
        NaruTask task = task();

        task.chat(task.model(), request(
                NaruMessage.user("first"),
                NaruMessage.user("second")));

        Assertions.assertEquals(1, protocol.chatCalls,
                "the shared rule is about a tail no model can answer, not about any one wire format");
    }

    @Test
    public void aProtocolCanRefuseATailTheDefaultAccepts() {
        StrictProtocol strict = new StrictProtocol();
        NaruAgentImpl agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-tail-strict-" + System.nanoTime()));
        NaruSessionImpl strictSession = new NaruSessionImpl(agent, agent.projectDirectory(),
                new NaruStreamInteraction(o -> {
                }), false, null, null, null, null);
        strictSession.registry().registerModelProvider(new FakeProvider(strict));
        NaruTask task = strictSession.newTask(NaruTaskSpec.of());

        NaruInvalidHistoryException e = Assertions.assertThrows(NaruInvalidHistoryException.class,
                () -> task.chat(task.model(), request(
                        NaruMessage.user("first"),
                        NaruMessage.user("second"))));

        Assertions.assertEquals("two consecutive user turns", e.reason(),
                "a protocol's own reason is the one that must be reported");
    }

    // ── stubs ────────────────────────────────────────────────────────────────

    private static class RecordingProtocol implements NaruModelProtocol {
        int chatCalls;

        @Override
        public String providerName() {
            return "fake";
        }

        @Override
        public NaruResponse chat(NaruModelRequest request, NaruTask task) {
            chatCalls++;
            NaruResponse r = new NaruResponse();
            r.setMessage(NaruMessage.assistant("answered"));
            return r;
        }

        @Override
        public NaruModelCapabilities getCapabilities() {
            return new StubCapabilities();
        }
    }

    /** A protocol with an opinion the default does not have. */
    private static final class StrictProtocol extends RecordingProtocol {
        @Override
        public NOptional<String> validateTail(List<NaruMessage> messages) {
            int users = 0;
            for (NaruMessage m : messages) {
                if (m.getRole() == net.thevpc.naru.api.agent.NaruRole.user) {
                    users++;
                }
            }
            if (users > 1) {
                return NOptional.of("two consecutive user turns");
            }
            return NOptional.ofNamedEmpty("tail");
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