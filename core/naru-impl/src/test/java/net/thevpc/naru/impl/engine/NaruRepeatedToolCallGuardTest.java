package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
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
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A model that asks for the same tool call over and over.
 *
 * <p>Reproduced from a recorded desktop turn: qwen2.5-coder answered "what is the capital of
 * tunisia" with a {@code think} call whose result is {@code ok}, read that back, and asked for the
 * identical call again — seven requests in nine seconds on the first page of the audit log, and a
 * transcript whose last visible line was whatever had been there before the user typed. The round
 * budget bounds the cost, but twenty rounds of the same call is still twenty requests and no
 * answer, so the repetition itself ends the turn.
 */
public class NaruRepeatedToolCallGuardTest {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

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

        String text() {
            return String.join("\n", lines);
        }
    }

    /** The shape the model actually returned: no text at all, one call, arguments unchanged. */
    private static final class RepeatingProtocol implements NaruModelProtocol {
        final AtomicInteger calls = new AtomicInteger();
        private final boolean varyArguments;

        RepeatingProtocol(boolean varyArguments) {
            this.varyArguments = varyArguments;
        }

        @Override
        public String providerName() {
            return "fake";
        }

        @Override
        public NaruResponse chat(NaruModelRequest request, NaruTask task) {
            int n = calls.incrementAndGet();
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("thought", varyArguments ? "attempt " + n : "The capital of Tunisia is Tunis.");
            NaruResponse r = new NaruResponse();
            r.setMessage(NaruMessage.assistantWithToolCalls("",
                    List.of(new NaruToolCall("call_think", "think", arguments))));
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

    /** Returns what {@code think} returns: a word that tells the model nothing. */
    private static final class OkTool implements NaruTool {
        final AtomicInteger runs = new AtomicInteger();

        @Override
        public String name() {
            return "think";
        }

        @Override
        public NText getDescription(NaruTask task) {
            return NText.ofPlain("think privately");
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
            return new NaruToolDefinition(name(), "think privately");
        }

        @Override
        public String execute(NaruToolCallContext context) {
            runs.incrementAndGet();
            return "ok";
        }
    }

    private static final class OkToolToolset implements NaruToolset {
        private final NaruTool tool;

        OkToolToolset(NaruTool tool) {
            this.tool = tool;
        }

        @Override
        public String id() {
            return "guard-think";
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

    private static NaruSession sessionOf(Recorder recorder, RepeatingProtocol protocol,
                                         NaruTool tool) {
        NaruWorkspace agent = NaruWorkspace.of();
        NPath dir = NPath.ofTempFolder("naru-guard-" + System.nanoTime());
        dir.mkdirs();
        agent.projectDirectory(dir);
        NaruSession s = agent.newSession()
                .directory(dir)
                .interaction(recorder)
                .configureDefaults(false)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
        ((NaruRegistryImpl) s.registry()).registerToolset(new OkToolToolset(tool));
        s.registry().registerModelProvider(new FakeProvider(protocol));
        return s;
    }

    /**
     * The stalled turn: two requests, not twenty. The second one is what the guard reads — its
     * answer repeats the first call, so the loop ends there instead of paying for a third.
     */
    @Test
    @Timeout(60)
    public void anUnchangedRepeatEndsTheTurn() {
        Recorder recorder = new Recorder();
        RepeatingProtocol protocol = new RepeatingProtocol(false);
        OkTool tool = new OkTool();
        NaruSession s = sessionOf(recorder, protocol, tool);

        NaruTask task = s.newTask(NaruTaskSpec.of().statements("what is the capital of tunisia"));
        task.setModel(new NaruModelConfig("fake", "fake-model"));
        s.start();
        task.unhold();
        assertTrue(task.await(PATIENCE), "the turn did not finish: " + recorder.text());

        assertTrue(protocol.calls.get() <= 2,
                "a stalled loop must stop at the repeat, not run the whole budget: "
                        + protocol.calls.get() + " requests\n" + recorder.text());
        assertTrue(tool.runs.get() <= 1, "the repeated call must not be dispatched again: " + tool.runs);
        assertTrue(recorder.text().contains("repeated its previous tool call"), recorder.text());
    }

    /**
     * A turn that ends without an answer has to say so. Silence here is what made the user report
     * the message as never delivered: the transcript kept showing the previous greeting.
     */
    @Test
    @Timeout(60)
    public void aTurnWithNoAnswerSaysSo() {
        Recorder recorder = new Recorder();
        RepeatingProtocol protocol = new RepeatingProtocol(false);
        NaruSession s = sessionOf(recorder, protocol, new OkTool());

        NaruTask task = s.newTask(NaruTaskSpec.of().statements("what is the capital of tunisia"));
        task.setModel(new NaruModelConfig("fake", "fake-model"));
        s.start();
        task.unhold();
        assertTrue(task.await(PATIENCE), "the turn did not finish: " + recorder.text());

        List<String> answers = recorder.lines.stream()
                .filter(l -> l.startsWith("AGENT_RESPONSE"))
                .collect(Collectors.toList());
        assertTrue(answers.stream().anyMatch(l -> l.contains("without an answer")),
                "the user must be told the turn produced no text: " + recorder.text());
    }

    /**
     * The guard keys on the arguments, so a model that is working — same tool, new input each
     * round — is left alone. Interrupting real work would be worse than the loop it prevents.
     */
    @Test
    @Timeout(60)
    public void aChangingRepeatKeepsRunning() {
        Recorder recorder = new Recorder();
        RepeatingProtocol protocol = new RepeatingProtocol(true);
        OkTool tool = new OkTool();
        NaruSession s = sessionOf(recorder, protocol, tool);

        NaruTask task = s.newTask(NaruTaskSpec.of().statements("count to twenty"));
        task.setModel(new NaruModelConfig("fake", "fake-model"));
        s.start();
        task.unhold();
        assertTrue(task.await(PATIENCE), "the turn did not finish: " + recorder.text());

        assertTrue(protocol.calls.get() > 2,
                "distinct arguments are progress, not a stall: " + protocol.calls.get() + " requests");
    }
}
