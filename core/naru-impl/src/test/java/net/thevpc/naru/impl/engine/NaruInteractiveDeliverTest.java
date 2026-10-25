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
import net.thevpc.naru.api.scheduler.NaruTaskMode;
import net.thevpc.naru.api.store.NaruSessionScope;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the desktop does on every message: a conversation task parked at the readline prompt,
 * a line delivered to it, and the model expected to answer.
 * <p>
 * The readline statement carries a one-shot runtime flag ({@code runtimeWaiting}) that says
 * "this instance already asked for the line and must now consume it". The flag is not part of
 * the persisted statement, so a task saved while parked comes back as a first-time READLINE:
 * delivered lines are never consumed and the task re-parks instead of answering. The restore
 * test below is that user-visible failure — a reopened session that swallows the first message.
 */
@Timeout(90)
public class NaruInteractiveDeliverTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static class RecordingInteraction implements NaruInteraction {
        final List<NaruInputRequest> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger prompts = new AtomicInteger();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public void open(NaruSession session) {
        }

        @Override
        public void requestInput(NaruInputRequest request) {
            requests.add(request);
            prompts.incrementAndGet();
        }

        @Override
        public void write(NaruLogMode mode, NMsg message) {
        }

        @Override
        public void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
        }

        @Override
        public void close() {
        }
    }

    private static class CountingProtocol implements NaruModelProtocol {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String providerName() {
            return "fake";
        }

        @Override
        public NaruResponse chat(NaruModelRequest request, NaruTask task) {
            calls.incrementAndGet();
            NaruResponse r = new NaruResponse();
            r.setMessage(NaruMessage.assistant("answer " + calls.get()));
            return r;
        }

        @Override
        public NaruResponse chatStream(NaruModelRequest request, NaruTask task, NaruStreamHandler handler) {
            handler.onChunk(new AnswerChunk("answer"));
            return chat(request, task);
        }

        @Override
        public NaruModelCapabilities getCapabilities() {
            return new StubCapabilities();
        }
    }

    private static final class AnswerChunk implements NaruStreamChunk {
        private final String text;

        AnswerChunk(String text) {
            this.text = text;
        }

        @Override
        public NaruChunkKind kind() {
            return NaruChunkKind.ANSWER;
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
            return NaruThinkingExtraction.valueOf("ANSWER");
        }

        @Override
        public boolean complete() {
            return true;
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

    private static NaruSession buildSession(NPath dir, String id, RecordingInteraction host) {
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(dir);
        NaruSession session = agent.newSession()
                .directory(dir)
                .id(id)
                .scope(NaruSessionScope.PRIVATE)
                .interaction(host)
                .banner(false)
                .richTerm(false)
                .configureDefaults(false)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .task(NaruTaskSpec.of().statements(new String[0]).model("fake/fake-model"))
                .interactive()
                .build();
        session.registry().registerModelProvider(new FakeProvider(new CountingProtocol()));
        return session;
    }

    private static void await(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        Assertions.assertTrue(condition.getAsBoolean(), "timed out waiting for " + what);
    }

    @Test
    public void aLineDeliveredToAFreshParkedTaskReachesTheModel() throws Exception {
        NPath dir = NPath.ofTempFolder("naru-deliver-fresh-" + System.nanoTime());
        RecordingInteraction host = new RecordingInteraction();
        NaruSession session = buildSession(dir, UUID.randomUUID().toString(), host);
        session.start();
        await(() -> host.prompts.get() >= 1, "the interactive task to park on the prompt");

        session.deliverInput("can you explain how log works in naru ?");

        NaruTask task = session.findTask(session.foregroundTaskId()).orNull();
        Assertions.assertNotNull(task, "the conversation task must be foreground");
        await(() -> hasAssistantAnswer(task) || host.prompts.get() >= 2,
                "the model call (assistant answer or a re-asked prompt)");
        Assertions.assertTrue(hasAssistantAnswer(task),
                "a delivered line must produce an assistant answer: history=" + task.history());
        session.stop();
    }

    @Test
    public void aLineDeliveredToARestoredParkedTaskReachesTheModel() throws Exception {
        NPath dir = NPath.ofTempFolder("naru-deliver-restore-" + System.nanoTime());
        String id = UUID.randomUUID().toString();

        RecordingInteraction firstHost = new RecordingInteraction();
        NaruSession first = buildSession(dir, id, firstHost);
        first.start();
        await(() -> firstHost.prompts.get() >= 1, "the first session to park on the prompt");
        first.save();
        // the desktop closes a quiescent session with hibernate(), not stop(): stop() would kill
        // the parked task rather than preserve the park the restore test depends on
        first.hibernate();

        RecordingInteraction secondHost = new RecordingInteraction();
        NaruSession second = buildSession(dir, id, secondHost);
        NaruTask restored = second.tasks().iterator().next();
        // the spec's model is not re-applied to a resumed task (the store owns it), and this
        // minimal harness does not persist the resolved config, so the fake model is re-set here;
        // the desktop store keeps its own model across a restore.
        restored.setModel(new NaruModelConfig("fake", "fake-model"));
        second.foregroundTaskId(restored.id());
        second.start();
        // A restored park never re-emits its input request — the desktop discovers it by polling
        // for BLOCKED_ON_INPUT (parkedOnInputId), then calls deliverInput. Mirror that here.
        await(() -> restored.status() == net.thevpc.naru.api.scheduler.NaruTaskStatus.BLOCKED_ON_INPUT,
                "the restored task to report itself parked");

        second.deliverInput("can you explain how log works in naru ?");

        await(() -> hasAssistantAnswer(restored),
                "the restored task to answer the delivered line");
        Assertions.assertTrue(hasAssistantAnswer(restored),
                "a line delivered to a restored conversation must reach the model, not sit in "
                        + "the input buffer: status=" + restored.status()
                        + ", history=" + restored.history());
        second.stop();
    }

    private static boolean hasAssistantAnswer(NaruTask task) {
        try {
            return task.history().stream().anyMatch(m -> m.getRole() == net.thevpc.naru.api.agent.NaruRole.assistant);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
