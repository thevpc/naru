package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
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
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.stmt.NaruPromptStmt;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ctrl+C while the model is talking.
 *
 * <p>The contract being pinned down: an interrupt is <i>not</i> a kill. The stream stops at
 * its next chunk, the turn ends the way a finished turn ends (answer kept, advance), and the
 * task carries on -- which for an interactive task means the prompt comes back. The two
 * failure modes worth catching here are an interrupt that lets the stream run to completion,
 * and an interrupt that quietly degrades into a kill and takes the session with it.
 */
public class NaruTaskInterruptTest {

    @BeforeAll
    public static void setUp() {
        Nuts.require();
    }

    /**
     * Counts how often the prompt was (re-)asked, which is how "the prompt came back" is
     * observed from outside the engine.
     */
    private static class RecordingInteraction implements NaruInteraction {
        final List<String> fragments = new ArrayList<>();
        final List<String> writes = new ArrayList<>();
        final AtomicInteger prompts = new AtomicInteger();

        @Override
        public void open(NaruSession session) {
        }

        @Override
        public void requestInput(NaruInputRequest request) {
            prompts.incrementAndGet();
        }

        @Override
        public void write(NaruLogMode mode, NMsg message) {
            writes.add(mode + ":" + message);
        }

        @Override
        public void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
            fragments.add(mode + ":" + fragment);
        }

        @Override
        public void close() {
        }
    }

    /**
     * Streams until the collector says stop, and -- on the call chosen by
     * {@link #interruptAtCall} -- raises the interrupt itself after a few chunks, which is
     * the same interleaving as a terminal signal landing mid-stream.
     */
    private static class InterruptingProtocol implements NaruModelProtocol {
        static final int PLANNED = 50;

        /** which call raises the interrupt; any other value means "none does" */
        int interruptAtCall = 1;
        int callCount;
        int lastCallEmitted;
        boolean toolCallAnswer;

        @Override
        public String providerName() {
            return "fake";
        }

        @Override
        public NaruResponse chat(NaruModelRequest request, NaruTask task) {
            int thisCall = ++callCount;
            lastCallEmitted = 0;
            if (thisCall == interruptAtCall) {
                task.interrupt();
            }
            return answer();
        }

        @Override
        public NaruResponse chatStream(NaruModelRequest request, NaruTask task, NaruStreamHandler handler) {
            int thisCall = ++callCount;
            lastCallEmitted = 0;
            boolean interruptsHere = thisCall == interruptAtCall;
            for (int i = 0; i < PLANNED; i++) {
                if (handler.isCancelled()) {
                    break;
                }
                handler.onChunk(chunk(NaruChunkKind.ANSWER, "t" + i));
                lastCallEmitted++;
                // the signal lands while tokens are flowing, exactly as it would from
                // a terminal -- the collector polls between chunks and stops here
                if (interruptsHere && lastCallEmitted == 3) {
                    task.interrupt();
                }
            }
            return answer();
        }

        private NaruResponse answer() {
            NaruResponse r = new NaruResponse();
            r.setMessage(toolCallAnswer
                    ? NaruMessage.assistantWithToolCalls("calling shell",
                    List.of(new NaruToolCall("c1", "shell", Map.of("command", "ls"))))
                    : NaruMessage.assistant("partial answer"));
            return r;
        }

        @Override
        public NaruModelCapabilities getCapabilities() {
            return new StubCapabilities();
        }

        static NaruStreamChunk chunk(NaruChunkKind kind, String text) {
            return new StubChunk(kind, text, "ANSWER", true);
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
            return true;
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
        public net.thevpc.nuts.elem.NElement toElement() {
            return net.thevpc.nuts.elem.NElement.ofTupleBuilder("Capabilities")
                    .set("streaming", true)
                    .set("thinking", false)
                    .build();
        }
    }

    private static class FakeProvider implements NaruModelProvider {
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

    private NaruTask taskFor(NaruModelProtocol protocol) {
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(NPath.ofTempFolder("naru-interrupt-" + System.nanoTime()));
        NaruSession session = agent.newSession()
                .directory(agent.projectDirectory())
                .interaction(new RecordingInteraction())
                .configureDefaults(false)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
        session.registry().registerModelProvider(new FakeProvider(protocol));
        NaruTask task = session.newTask(NaruTaskSpec.of().statements("noop"));
        task.setModel(new NaruModelConfig("fake", "fake-model"));
        return task;
    }

    private static NaruModelRequest request() {
        return new NaruModelRequest(List.of(NaruMessage.user("hi")), new HashMap<>());
    }

    // ── the interrupt itself ─────────────────────────────────────────────────

    @Test
    public void interruptStopsTheStreamWithoutEndingTheTask() {
        InterruptingProtocol protocol = new InterruptingProtocol();
        NaruTask task = taskFor(protocol);

        task.chat(task.model(), request());

        Assertions.assertTrue(protocol.lastCallEmitted < InterruptingProtocol.PLANNED,
                "the stream must stop at the interrupt, it drew " + protocol.lastCallEmitted);
        Assertions.assertTrue(task.isInterruptRequested());
        Assertions.assertFalse(task.isKillRequested(),
                "an interrupt must not turn into a kill behind the scenes");
        Assertions.assertNotEquals(NaruTaskStatus.KILLED, task.status(),
                "the task survives an interrupt; only kill() ends it");
    }

    /**
     * The press belongs to the call that was running when the user made it. If the flag
     * survived the call it stopped, the next call would be cancelled before its first chunk
     * -- the REPL would answer nothing, ever, after a single Ctrl+C.
     */
    @Test
    public void theNextCallDoesNotInheritTheInterrupt() {
        InterruptingProtocol protocol = new InterruptingProtocol();
        NaruTask task = taskFor(protocol);

        task.chat(task.model(), request());
        Assertions.assertTrue(task.isInterruptRequested(), "precondition: the call was interrupted");
        protocol.interruptAtCall = -1;

        task.chat(task.model(), request());

        Assertions.assertFalse(task.isInterruptRequested(),
                "the interrupt must be spent by the call it stopped");
        Assertions.assertEquals(InterruptingProtocol.PLANNED, protocol.lastCallEmitted,
                "a stale interrupt would cancel the next call at its first chunk");
    }

    @Test
    public void interruptIsNotKill() {
        InterruptingProtocol protocol = new InterruptingProtocol();
        NaruTask task = taskFor(protocol);
        NaruTaskStatus before = task.status();

        Assertions.assertSame(task, task.interrupt());
        Assertions.assertEquals(before, task.status(),
                "interrupt() must not move the task towards a terminal state");
        Assertions.assertTrue(task.isInterruptRequested());

        task.kill();
        Assertions.assertEquals(NaruTaskStatus.KILLED, task.status());
        Assertions.assertTrue(task.isKillRequested());
    }

    // ── what the interrupted turn leaves behind ──────────────────────────────

    /**
     * A tool_calls entry with no tool results after it is a poison pill: the provider
     * rejects the history on every later request of this task. The half-started round is
     * therefore dropped whole -- the model never finished asking for it, and the user never
     * saw it run.
     */
    @Test
    public void anInterruptedToolRoundIsDroppedRatherThanScheduled() {
        InterruptingProtocol protocol = new InterruptingProtocol();
        protocol.toolCallAnswer = true;
        NaruTask task = taskFor(protocol);

        new NaruPromptStmt("run something").exec(task);

        Assertions.assertTrue(task.history().stream().noneMatch(NaruMessage::hasToolCalls),
                "a tool call with no results must not reach the history: " + task.history());
        Assertions.assertEquals(1, task.history().size(),
                "only the prompt the user sent belongs to this interrupted turn");
        NaruStatement next = task.peekStatement().orNull();
        Assertions.assertNotNull(next);
        Assertions.assertNotEquals(NaruStatement.Type.TOOL_CALL, next.type,
                "an interrupted round must not schedule its tool calls");
    }

    /** The control for the test above: an untouched round still runs its full round. */
    @Test
    public void aToolRoundThatWasNotInterruptedStillRunsItsRound() {
        InterruptingProtocol protocol = new InterruptingProtocol();
        protocol.toolCallAnswer = true;
        protocol.interruptAtCall = -1;
        NaruTask task = taskFor(protocol);

        new NaruPromptStmt("run something").exec(task);

        Assertions.assertTrue(task.history().stream().anyMatch(NaruMessage::hasToolCalls),
                "the control: an untouched round keeps its tool call in history");
        NaruStatement next = task.peekStatement().orNull();
        Assertions.assertNotNull(next);
        Assertions.assertEquals(NaruStatement.Type.TOOL_CALL, next.type,
                "the control: an untouched round schedules its tool calls");
    }

    /**
     * The whole point, end to end through the scheduler: a streamed call is interrupted, the
     * partial answer is committed to the conversation, and the interactive task drops back to
     * the prompt still running -- rather than dying the way {@code kill()} would.
     */
    @Test
    @Timeout(60)
    public void interruptingAStreamDropsBackToThePromptWithTheTaskAlive() throws Exception {
        InterruptingProtocol protocol = new InterruptingProtocol();
        RecordingInteraction interaction = new RecordingInteraction();
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(NPath.ofTempFolder("naru-interrupt-e2e-" + System.nanoTime()));
        NaruSession session = agent.newSession()
                .directory(agent.projectDirectory())
                .interaction(interaction)
                .configureDefaults(false)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .interactive()
                .task(NaruTaskSpec.of()
                        .statements("hello")
                        .resolveName()
                        .model("fake/fake-model"))
                .build();
        session.registry().registerModelProvider(new FakeProvider(protocol));

        NaruTask run = session.run();

        // wait until the prompt asked for the next line again -- that is the only
        // externally visible end of the interrupted turn
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (interaction.prompts.get() < 1 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        Assertions.assertEquals(1, interaction.prompts.get(),
                "the prompt must come back after an interrupt");

        // the model call ran and was cut where the interrupt landed
        Assertions.assertTrue(protocol.callCount >= 1,
                "the model call never ran; model=" + run.model()
                        + ", writes=" + interaction.writes);
        Assertions.assertTrue(protocol.lastCallEmitted < InterruptingProtocol.PLANNED,
                "the stream must stop early, it drew " + protocol.lastCallEmitted);

        Assertions.assertFalse(run.isCompleted(),
                "an interrupted task keeps running; only kill() ends it");
        Assertions.assertNotEquals(NaruTaskStatus.KILLED, run.status());
        Assertions.assertTrue(interaction.fragments.stream().anyMatch(f -> f.contains("t0")),
                "the partial answer must have been drawn while it streamed: " + interaction.fragments);

        List<NaruMessage> history = run.history();
        Assertions.assertEquals(2, history.size(),
                "prompt plus interrupted answer, nothing else: " + history);
        Assertions.assertEquals(NMsg.ofP("partial answer"), history.get(1).getContent(),
                "the partial answer is committed so the next turn continues from it");
        run.cancel();
    }
}
