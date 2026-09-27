package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruChunkKind;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruStreamChunk;
import net.thevpc.naru.api.model.NaruStreamHandler;
import net.thevpc.naru.api.model.NaruThinkingExtraction;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.io.NTerminal;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The seam between a streamed model turn and what the user actually sees.
 *
 * <p>Every other streaming test checks one link: the parser splits a response, the
 * terminal draws a fragment. This checks the link between them, which is where a
 * capability that quietly says "no" or a duplicate final print turns into a user
 * reporting that streaming does not work -- while both halves still pass their own
 * tests perfectly.
 */
public class NaruTaskStreamingOutputTest {

    @BeforeAll
    public static void setUp() {
        try {
            net.thevpc.nuts.core.NWorkspace ws =
                    net.thevpc.nuts.Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            try {
                net.thevpc.nuts.core.NWorkspace ws = net.thevpc.nuts.Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private final List<String> fragments = new ArrayList<>();

    /** Records the order fragments arrive in, which is what "incremental" means. */
    private final class RecordingInteraction implements NaruInteraction {
        @Override
        public void open(NaruSession session) {
        }

        @Override
        public void requestInput(NaruInputRequest request) {
        }

        @Override
        public void write(NaruLogMode mode, NMsg message) {
        }

        @Override
        public void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
            fragments.add(mode + ":" + fragment.toString() + (end ? "[end]" : ""));
        }

        @Override
        public void close() {
        }
    }

    private static class FixedProtocol implements NaruModelProtocol {
        private final boolean streaming;
        private final boolean thinking;
        int streamCalls;
        int chatCalls;

        FixedProtocol(boolean streaming, boolean thinking) {
            this.streaming = streaming;
            this.thinking = thinking;
        }

        @Override
        public String providerName() {
            return "fake";
        }

        @Override
        public NaruResponse chat(NaruModelRequest request, NaruTask task) {
            chatCalls++;
            NaruResponse r = new NaruResponse();
            r.setMessage(NaruMessage.assistant("batched answer"));
            return r;
        }

        @Override
        public NaruResponse chatStream(NaruModelRequest request, NaruTask task, NaruStreamHandler handler) {
            streamCalls++;
            if (thinking) {
                handler.onChunk(chunk(NaruChunkKind.THINKING, "because ", "THINK"));
                handler.onChunk(chunk(NaruChunkKind.THINKING, "reasons", "THINK"));
                handler.onChunk(chunk(NaruChunkKind.THINKING, "", "THINK", true));
            }
            handler.onChunk(chunk(NaruChunkKind.ANSWER, "streamed ", "ANSWER"));
            handler.onChunk(chunk(NaruChunkKind.ANSWER, "answer", "ANSWER", true));
            NaruResponse r = new NaruResponse();
            r.setMessage(NaruMessage.assistant("streamed answer"));
            return r;
        }

        @Override
        public NaruModelCapabilities getCapabilities() {
            return new StubCapabilities(streaming, thinking);
        }

        static NaruStreamChunk chunk(NaruChunkKind kind, String text, String tag, boolean... complete) {
            return new StubChunk(kind, text, tag, complete.length > 0 && complete[0]);
        }
    }

    private static final class StubChunk implements NaruStreamChunk {
        private final NaruChunkKind kind;
        private final String text;
        private final String tag;
        private final boolean complete;

        StubChunk(NaruChunkKind kind, String text, String tag, boolean complete) {
            this.kind = kind;
            this.text = text;
            this.tag = tag;
            this.complete = complete;
        }

        @Override
        public NaruChunkKind kind() {
            return kind;
        }

        @Override
        public String text() {
            return text;
        }

        @Override
        public int index() {
            return 0;
        }

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public NaruThinkingExtraction extraction() {
            return NaruThinkingExtraction.valueOf(tag);
        }

        @Override
        public boolean complete() {
            return complete;
        }
    }

    private static final class StubCapabilities implements NaruModelCapabilities {
        private final boolean streaming;
        private final boolean thinking;

        StubCapabilities(boolean streaming, boolean thinking) {
            this.streaming = streaming;
            this.thinking = thinking;
        }

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
            return thinking;
        }

        @Override
        public boolean isEmbedding() {
            return false;
        }

        @Override
        public boolean isTextOnly() {
            return !thinking;
        }

        @Override
        public boolean isStreaming() {
            return streaming;
        }

        @Override
        public NaruCachingMode cachingMode() {
            return NaruCachingMode.NONE;
        }

        @Override
        public Set<String> keys() {
            return thinking ? Set.of("thinking") : Set.of("text-only");
        }

        @Override
        public net.thevpc.nuts.elem.NElement toElement() {
            return net.thevpc.nuts.elem.NElement.ofTupleBuilder("Capabilities")
                    .set("streaming", streaming)
                    .set("thinking", thinking)
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

    private NaruTask taskFor(FixedProtocol protocol) {
        NaruAgentImpl agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-stream-" + System.nanoTime()));
        NaruSessionImpl session = new NaruSessionImpl(agent, agent.projectDirectory(),
                new RecordingInteraction(), false, null, null, null, null);
        session.registry().registerModelProvider(new FakeProvider(protocol));
        NaruTask task = session.newTask(net.thevpc.naru.api.task.NaruTaskSpec.of()
                .statements("noop"));
        task.setModel(new NaruModelConfig("fake", "fake-model"));
        return task;
    }

    @Test
    public void aStreamedTurnReachesTheUserFragmentByFragment() {
        FixedProtocol protocol = new FixedProtocol(true, false);
        NaruTask task = taskFor(protocol);

        task.chat(task.model(), new NaruModelRequest(List.of(NaruMessage.user("hi")), new java.util.HashMap<>()));

        Assertions.assertEquals(1, protocol.streamCalls, "a streaming provider must be asked to stream");
        Assertions.assertEquals(0, protocol.chatCalls);
        Assertions.assertEquals(List.of(
                        "MODEL_RESPONSE:streamed ",
                        "MODEL_RESPONSE:answer",
                        "MODEL_RESPONSE:[end]"),
                fragments,
                "the answer must arrive in pieces, with the line closed at the end");
    }

    @Test
    public void reasoningAndAnswerAreDrawnInSeparateChannels() {
        FixedProtocol protocol = new FixedProtocol(true, true);
        NaruTask task = taskFor(protocol);

        task.chat(task.model(), new NaruModelRequest(List.of(NaruMessage.user("hi")), new java.util.HashMap<>()));

        Assertions.assertTrue(fragments.contains("MODEL_THINKING:because "));
        Assertions.assertTrue(fragments.contains("MODEL_THINKING:reasons"));
        Assertions.assertTrue(fragments.contains("MODEL_THINKING:[end]"),
                "reasoning must be closed before the answer starts, or they run together");
        Assertions.assertTrue(fragments.contains("MODEL_RESPONSE:answer"));
        int thinkingEnd = fragments.indexOf("MODEL_THINKING:[end]");
        int answerStart = fragments.indexOf("MODEL_RESPONSE:streamed ");
        Assertions.assertTrue(thinkingEnd < answerStart, "got: " + fragments);
    }

    @Test
    public void aStreamedAnswerIsNotAlsoReportedWhole() {
        FixedProtocol protocol = new FixedProtocol(true, false);
        NaruTask task = taskFor(protocol);

        task.chat(task.model(), new NaruModelRequest(List.of(NaruMessage.user("hi")), new java.util.HashMap<>()));

        Assertions.assertTrue(task.isResponseStreamed(),
                "the statement that made the call must be able to tell the answer was already shown");
    }

    @Test
    public void aBatchedTurnIsNotClaimedAsStreamed() {
        FixedProtocol protocol = new FixedProtocol(false, false);
        NaruTask task = taskFor(protocol);

        task.chat(task.model(), new NaruModelRequest(List.of(NaruMessage.user("hi")), new java.util.HashMap<>()));

        Assertions.assertEquals(1, protocol.chatCalls,
                "a provider that cannot stream must still be usable");
        Assertions.assertEquals(0, protocol.streamCalls);
        Assertions.assertFalse(task.isResponseStreamed(),
                "nothing was drawn, so the statement must still report the answer itself");
    }

    @Test
    public void aFailingStreamStillClosesItsLines() {
        FixedProtocol protocol = new FixedProtocol(true, false) {
            @Override
            public NaruResponse chatStream(NaruModelRequest request, NaruTask task,
                                           NaruStreamHandler handler) {
                handler.onChunk(chunk(NaruChunkKind.ANSWER, "half", "ANSWER"));
                throw new IllegalStateException("provider died mid-answer");
            }
        };
        NaruTask task = taskFor(protocol);

        Assertions.assertThrows(IllegalStateException.class, () ->
                        task.chat(task.model(), new NaruModelRequest(List.of(NaruMessage.user("hi")), new java.util.HashMap<>())));

        Assertions.assertTrue(fragments.contains("MODEL_RESPONSE:[end]"),
                "a failed turn must still terminate the line, or the next output continues it: " + fragments);
    }
}
