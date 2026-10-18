package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruOutput;
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
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.registry.NaruInteractiveTool;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.naru.impl.registry.NaruRegistryImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1: the tool-call lifecycle as a typed event, next to the log line that has always carried it.
 *
 * <p>The plan's v1 acceptance asks for a tool card showing name, arguments, duration and the
 * <em>whole</em> result, and the console gives a host only a 300-character abbreviation inside a
 * decorated {@code PROGRESS} line. These tests pin both halves of the change: the event exists
 * with the full text and the real attribution, and the old line is still there, still abbreviated
 * the same way, because the terminal must not change when a desktop gets a new channel.
 */
public class NaruToolCallLifecycleTest {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    /** Everything the session said, and every tool-call report it filed. */
    private static final class Recorder implements NaruInteraction {
        final List<NaruToolCallEvent> events = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        final List<NaruInputRequest> questions = new ArrayList<>();
        volatile boolean throwOnReport;

        @Override
        public void open(NaruSession session) {
        }

        @Override
        public void requestInput(NaruInputRequest request) {
            questions.add(request);
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
            if (throwOnReport) {
                throw new IllegalStateException("a host that cannot draw a card");
            }
            events.add(event);
        }

        @Override
        public void close() {
        }

        List<NaruToolCallEvent> ofKind(NaruToolCallEvent.Kind kind) {
            return events.stream().filter(e -> e.kind() == kind).collect(Collectors.toList());
        }
    }

    private static NaruSession session(Recorder interaction, NaruTool... tools) {
        NaruWorkspace agent = NaruWorkspace.of();
        NPath dir = NPath.ofTempFolder("naru-e1-" + System.nanoTime());
        dir.mkdirs();
        agent.projectDirectory(dir);
        NaruSession s = agent.newSession()
                .directory(dir)
                .interaction(interaction)
                .configureDefaults(false)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
        for (NaruTool t : tools) {
            ((NaruRegistryImpl) s.registry()).registerToolset(new OneToolToolset("e1-" + t.name(), t));
        }
        return s;
    }

    /** A session whose model asks for exactly one tool call, then answers. */
    private static NaruSession scriptedSession(Recorder recorder, NaruToolCall call,
                                               NaruTool... tools) {
        NaruSession s = session(recorder, tools);
        s.registry().registerModelProvider(new FakeProvider(new ScriptedProtocol(call)));
        return s;
    }

    private static NaruTask run(NaruSession s, String prompt) {
        NaruTask task = s.newTask(NaruTaskSpec.of().statements(prompt));
        s.start();
        task.unhold();
        assertTrue(task.await(PATIENCE), "the turn did not finish within " + PATIENCE);
        return task;
    }

    // ── the happy path ────────────────────────────────────────────────────────

    /**
     * The card needs more than the log line has ever offered: which call it is, who ran it,
     * what was asked of the tool, and the result whole rather than cut at 300 characters.
     */
    @Test
    @Timeout(60)
    public void aToolCallReportsItsArgumentsAndItsWholeResult() {
        String longResult = "L".repeat(4000);
        Recorder r = new Recorder();
        NaruToolCall call = new NaruToolCall("call-77", "slow_tool", Map.of("path", "a.txt", "n", 3));
        NaruSession s = scriptedSession(r, call, new EchoTool("slow_tool", longResult, 40));

        NaruTask task = run(s, "read a.txt");

        List<NaruToolCallEvent> started = r.ofKind(NaruToolCallEvent.Kind.STARTED);
        List<NaruToolCallEvent> done = r.ofKind(NaruToolCallEvent.Kind.RESULT);
        assertEquals(1, started.size(), "one call, one start: " + r.events);
        assertEquals(1, done.size(), "one call, one result: " + r.events);
        assertEquals(longResult, done.get(0).result(), "the event carries the whole text");
        assertEquals("call-77", done.get(0).callId());
        assertEquals("slow_tool", done.get(0).toolName());
        assertEquals(task.id(), done.get(0).taskId(), "the host must not guess the task");
        assertEquals(Map.of("path", "a.txt", "n", 3), done.get(0).arguments());
        assertTrue(done.get(0).durationMillis() >= 40,
                "a 40 ms tool must not report a shorter duration: " + done.get(0).durationMillis());
        assertTrue(done.get(0).isFinal());
        assertNull(started.get(0).result(), "nothing has returned when a call starts");
        assertEquals(NaruToolCallEvent.UNKNOWN_DURATION, started.get(0).durationMillis());
        // the same call, in the order a host draws them
        assertSameCall(started.get(0), done.get(0));
        assertEquals(r.events.indexOf(started.get(0)) < r.events.indexOf(done.get(0)), true);
    }

    /**
     * The desktop reads the typed event; the terminal reads the log line. Both must still be
     * true after E1, so the abbreviated line is asserted rather than assumed.
     */
    @Test
    @Timeout(60)
    public void theLogLineIsStillTheSameAbbreviatedLine() {
        String longResult = "X".repeat(4000);
        Recorder r = new Recorder();
        NaruToolCall call = new NaruToolCall("call-1", "big_tool", Map.of());
        NaruSession s = scriptedSession(r, call, new EchoTool("big_tool", longResult, 0));

        run(s, "read it");

        List<String> results = r.lines.stream()
                .filter(l -> l.startsWith("PROGRESS") && l.contains("Result:"))
                .collect(Collectors.toList());
        assertEquals(1, results.size(), "the result is still logged once: " + r.lines);
        String line = results.get(0);
        assertTrue(line.contains("X".repeat(300)), "300 characters, as the terminal has always shown");
        assertFalse(line.contains("X".repeat(301)), "and not one more: the event, not the line, is untruncated");
        assertTrue(r.ofKind(NaruToolCallEvent.Kind.RESULT).get(0).result().length() > line.length(),
                "the event must be the one holding the whole result");
    }

    // ── the calls that do not run ─────────────────────────────────────────────

    /** A call for a tool nobody registered still has to be visible, whole, and final. */
    @Test
    @Timeout(60)
    public void anUnknownToolFailsWithTheWholeError() {
        Recorder r = new Recorder();
        NaruToolCall call = new NaruToolCall("call-2", "not_a_tool", Map.of());
        NaruSession s = scriptedSession(r, call);

        run(s, "use a tool that does not exist");

        List<NaruToolCallEvent> failed = r.ofKind(NaruToolCallEvent.Kind.FAILED);
        assertEquals(1, failed.size(), "an unknown tool is a failure, not a result: " + r.events);
        assertTrue(failed.get(0).result().startsWith("ERROR: Unknown tool"), failed.get(0).result());
        assertTrue(failed.get(0).result().contains("Available tools"),
                "the text the model sees is the text the host shows");
        assertEquals(0, r.ofKind(NaruToolCallEvent.Kind.RESULT).size());
        assertEquals("call-2", failed.get(0).callId());
    }

    /**
     * Plan §3.3: with no approval prompt in v1, a denial has to be legible as a card. That only
     * works if the denial reaches the host whole — the rule it hit is inside the text.
     */
    @Test
    @Timeout(60)
    public void aDeniedCallReportsFailedAndNeverRuns() {
        Recorder r = new Recorder();
        AtomicBoolean ran = new AtomicBoolean();
        NaruTool tool = new EchoTool("echo_tool", "OK", 0) {
            @Override
            public String execute(NaruToolCallContext context) {
                ran.set(true);
                return "OK";
            }
        };
        NaruToolCall call = new NaruToolCall("call-3", "echo_tool", Map.of());
        NaruSession s = scriptedSession(r, call, tool);
        s.accessPolicy().putRule(new NaruPermissionRule()
                .id("deny-echo")
                .resourceType(NaruResourceType.TOOL)
                .patterns("echo_tool")
                .actions(NaruAction.CALL)
                .effect(NaruAccessEffect.DENY));

        run(s, "echo please");

        List<NaruToolCallEvent> failed = r.ofKind(NaruToolCallEvent.Kind.FAILED);
        assertEquals(1, failed.size(), "the denial is reported: " + r.events);
        assertTrue(failed.get(0).result().contains("deny-echo"),
                "a full card is only useful if it names the rule: " + failed.get(0).result());
        assertFalse(ran.get(), "the tool must not run when denied");
    }

    /**
     * A blank tool name is rejected before dispatch, so the host never sees a START for it.
     * The final event still has to arrive, or the card would sit there as "running…".
     */
    @Test
    @Timeout(60)
    public void aCallRejectedBeforeDispatchStillReportsASingleFinalEvent() {
        Recorder r = new Recorder();
        NaruToolCall call = new NaruToolCall("call-4", "", Map.of());
        NaruSession s = scriptedSession(r, call);

        run(s, "call nothing");

        assertEquals(0, r.ofKind(NaruToolCallEvent.Kind.STARTED).size(),
                "nothing was dispatched");
        List<NaruToolCallEvent> failed = r.ofKind(NaruToolCallEvent.Kind.FAILED);
        assertEquals(1, failed.size(), r.events.toString());
        assertTrue(failed.get(0).result().startsWith("ERROR: Empty tool name"), failed.get(0).result());
    }

    /**
     * The report is a side channel. A host that cannot draw a card must not be able to fail a
     * tool call that already succeeded — the same rule {@code write} has always had.
     */
    @Test
    @Timeout(60)
    public void aHostThatThrowsCannotFailTheToolCall() {
        Recorder r = new Recorder();
        r.throwOnReport = true;
        NaruToolCall call = new NaruToolCall("call-5", "echo_tool", Map.of());
        NaruSession s = scriptedSession(r, call, new EchoTool("echo_tool", "OK", 0));

        NaruTask task = run(s, "echo");

        assertEquals("OK", lastToolResult(task),
                "the tool still ran and its result still reached the model");
        assertEquals(1, task.history().stream()
                        .filter(m -> m.getRole() == NaruRole.tool).count(),
                "exactly one tool turn, whatever the host did");
    }

    // ── an interactive tool: started, parked, then finished ───────────────────

    /**
     * {@code ask_user} parks on purpose, and the plan wants the park visible (Phase 1 renders
     * it as buttons). The lifecycle must not pretend the call finished when it only stopped to
     * ask, and must settle it once the answer is in.
     */
    @Test
    @Timeout(60)
    public void anInteractiveCallParksAndLaterSettles() throws Exception {
        Recorder r = new Recorder();
        NaruToolCall call = new NaruToolCall("call-6", "ask_tool", Map.of("question", "which colour?"));
        NaruSession s = scriptedSession(r, call, new AskTool());
        // the answer comes from the host, the way it does in the desktop: another thread, later
        Thread answered = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 15_000;
            while (r.questions.isEmpty() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!r.questions.isEmpty()) {
                r.questions.get(0).deliver("red");
            }
        }, "e1-answer");
        answered.setDaemon(true);
        answered.start();

        NaruTask task = run(s, "ask me something");
        answered.join(5_000);

        List<NaruToolCallEvent.Kind> kinds = r.events.stream()
                .map(NaruToolCallEvent::kind).collect(Collectors.toList());
        assertTrue(kinds.contains(NaruToolCallEvent.Kind.PARKED), "the park is reported: " + kinds);
        assertEquals(1, r.ofKind(NaruToolCallEvent.Kind.RESULT).size(),
                "and the call settles exactly once: " + kinds);
        int parked = kinds.indexOf(NaruToolCallEvent.Kind.PARKED);
        int settled = kinds.indexOf(NaruToolCallEvent.Kind.RESULT);
        assertTrue(parked < settled, "a result before the park would be a lie: " + kinds);
        assertTrue(r.ofKind(NaruToolCallEvent.Kind.RESULT).get(0).result().contains("red"),
                "the answer is what the tool returned: " + r.ofKind(NaruToolCallEvent.Kind.RESULT).get(0).result());
        assertSameCall(r.events.get(parked), r.ofKind(NaruToolCallEvent.Kind.RESULT).get(0));
    }

    // ── attribution: which task said this ─────────────────────────────────────

    /**
     * §0 check 2: {@code NaruOutput} carried no task, so a host with parallel tasks had to guess
     * where a line belonged. Task-produced output is stamped now; the id is the task's own.
     */
    @Test
    @Timeout(60)
    public void taskOutputCarriesTheTaskThatProducedIt() {
        List<NaruOutput> outputs = Collections.synchronizedList(new ArrayList<>());
        NaruStreamInteraction stream = new NaruStreamInteraction(outputs::add);
        NaruWorkspace agent = NaruWorkspace.of();
        NPath dir = NPath.ofTempFolder("naru-e1-stamp-" + System.nanoTime());
        dir.mkdirs();
        agent.projectDirectory(dir);
        NaruSession s = agent.newSession()
                .directory(dir)
                .interaction(stream)
                .configureDefaults(false)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
        s.registry().registerModelProvider(new FakeProvider(
                new ScriptedProtocol(new NaruToolCall("call-8", "echo_tool", Map.of()))));
        ((NaruRegistryImpl) s.registry()).registerToolset(new OneToolToolset("e1-echo", new EchoTool("echo_tool", "OK", 0)));

        NaruTask task = s.newTask(NaruTaskSpec.of().statements("echo"));
        s.start();
        task.unhold();
        assertTrue(task.await(PATIENCE), "the turn did not finish");

        List<NaruOutput> mine = outputs.stream()
                .filter(o -> o.taskId() == task.id()).collect(Collectors.toList());
        assertFalse(mine.isEmpty(), "task output must be attributed, got: " + outputs);
        assertTrue(mine.stream().anyMatch(o -> o.mode() == NaruLogMode.PROGRESS
                        && String.valueOf(o.message()).contains("Result")),
                "the tool result line is stamped: " + mine);
        // the other half of honesty: nothing is stamped with a task that did not say it
        List<Long> foreign = outputs.stream()
                .map(NaruOutput::taskId)
                .filter(x -> x != task.id() && x != NaruOutput.NO_TASK)
                .distinct().collect(Collectors.toList());
        assertTrue(foreign.isEmpty(), "no output claims another task: " + foreign);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static void assertSameCall(NaruToolCallEvent a, NaruToolCallEvent b) {
        // the two halves of one call: a host keys a card on exactly these three fields, so a
        // drift here would split one tool call into two cards
        assertEquals(a.callId(), b.callId(), "same call id");
        assertEquals(a.toolName(), b.toolName(), "same tool");
        assertEquals(a.taskId(), b.taskId(), "same task");
    }

    private static String lastToolResult(NaruTask task) {
        List<NaruMessage> history = task.history();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).getRole() == NaruRole.tool) {
                return history.get(i).getContent();
            }
        }
        return null;
    }

    /** Answers the first model turn with one tool call, then answers plainly. */
    private static final class ScriptedProtocol implements NaruModelProtocol {
        private final NaruToolCall call;
        int calls;

        ScriptedProtocol(NaruToolCall call) {
            this.call = call;
        }

        @Override
        public String providerName() {
            return "fake";
        }

        @Override
        public NaruResponse chat(NaruModelRequest request, NaruTask task) {
            NaruResponse r = new NaruResponse();
            calls++;
            if (calls == 1) {
                r.setMessage(NaruMessage.assistantWithToolCalls("let me check", List.of(call)));
            } else {
                r.setMessage(NaruMessage.assistant("done"));
            }
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
            return Set.of();
        }

        @Override
        public NElement toElement() {
            return NElement.ofTupleBuilder("Capabilities").build();
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

    /** The model config parameter is unused; naming it keeps the import list short. */

    private static class EchoTool implements NaruTool {
        private final String name;
        private final String result;
        private final int delayMillis;

        EchoTool(String name, String result, int delayMillis) {
            this.name = name;
            this.result = result;
            this.delayMillis = delayMillis;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public NText getDescription(NaruTask task) {
            return NText.ofPlain("e1 test tool");
        }

        @Override
        public Set<String> tags() {
            return Collections.emptySet();
        }

        @Override
        public boolean isEssential() {
            return true;
        }

        @Override
        public NaruToolDefinition getDefinition(NaruTask task) {
            return new NaruToolDefinition(name, "e1 test tool");
        }

        @Override
        public String execute(NaruToolCallContext context) {
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return result;
        }
    }

    /** The engine's parking shape: return the marker, get the answer through {@code complete}. */
    private static final class AskTool implements NaruInteractiveTool {
        @Override
        public String name() {
            return "ask_tool";
        }

        @Override
        public NText getDescription(NaruTask task) {
            return NText.ofPlain("e1 interactive tool");
        }

        @Override
        public Set<String> tags() {
            return Collections.emptySet();
        }

        @Override
        public boolean isEssential() {
            return true;
        }

        @Override
        public NaruToolDefinition getDefinition(NaruTask task) {
            return new NaruToolDefinition(name(), "e1 interactive tool");
        }

        @Override
        public String execute(NaruToolCallContext context) {
            return NaruInteractiveTool.WAITING_FOR_INPUT;
        }

        @Override
        public String complete(NaruToolCall call, String answer) {
            return "answered: " + answer;
        }
    }

    private static final class OneToolToolset implements NaruToolset {
        private final String id;
        private final NaruTool tool;

        OneToolToolset(String id, NaruTool tool) {
            this.id = id;
            this.tool = tool;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public List<NaruTool> tools() {
            return List.of(tool);
        }

        @Override
        public void open(NaruSession session) {
        }

        @Override
        public void close() {
        }
    }
}
