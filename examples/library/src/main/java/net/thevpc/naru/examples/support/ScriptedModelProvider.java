package net.thevpc.naru.examples.support;

import net.thevpc.naru.api.model.NaruModelContext;
import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * An offline model, so every example runs with no Ollama and no API key.
 *
 * <p>It is also the smallest complete {@link NaruModelProvider}: a provider names itself,
 * lists its models and hands out a {@link NaruModelProtocol}; the protocol turns a request
 * into a response. Plugging a real backend into NARU means writing exactly these two
 * classes, and the runtime above them (history, tools, routines, persistence) does not
 * change.
 *
 * <p>Replies come from rules registered with {@link #when}. A rule may also answer with a
 * tool call, which is how the chain example shows the tool loop without a real model.
 * It is registered on the classpath (META-INF/services), so sessions created by the
 * runtime itself — such as a {@code NaruSessionHandle} activation — find it too.
 */
public class ScriptedModelProvider implements NaruModelProvider {
    public static final String NAME = "scripted";
    private static final List<Function<NaruModelRequest, NaruResponse>> RULES = new CopyOnWriteArrayList<>();
    private static final AtomicLong CALL_IDS = new AtomicLong();

    /** Answers {@code reply} when the last user message contains {@code needle} (case-insensitive). */
    public static void when(String needle, String reply) {
        when(needle, r -> NaruMessage.assistant(reply));
    }

    public static void when(String needle, Function<NaruModelRequest, NaruMessage> reply) {
        String n = needle.toLowerCase();
        RULES.add(r -> lastUserText(r).toLowerCase().contains(n) ? response(reply.apply(r)) : null);
    }

    /**
     * Asks for {@code tool} with {@code args} the first time the model is offered that tool
     * for a matching prompt, then answers with {@code summarize(toolResult)} once the
     * result is in the history.
     */
    public static void callTool(String needle, String tool, Map<String, NElement> args,
                                Function<String, String> summarize) {
        String n = needle.toLowerCase();
        RULES.add(r -> {
            if (!lastUserText(r).toLowerCase().contains(n)) {
                return null;
            }
            NaruMessage last = r.messages().get(r.messages().size() - 1);
            if (last.getRole() == NaruRole.tool && tool.equals(last.getToolName())) {
                return response(NaruMessage.assistant(summarize.apply(last.getFilteredContent())));
            }
            boolean offered = r.tools().stream().map(NaruToolDefinition::getName).anyMatch(tool::equals);
            if (!offered) {
                return null;
            }
            NaruToolCall call = new NaruToolCall("call-" + CALL_IDS.incrementAndGet(), tool, args);
            return response(NaruMessage.assistantWithToolCalls("", List.of(call)));
        });
    }

    public static void reset() {
        RULES.clear();
    }

    static String lastUserText(NaruModelRequest request) {
        List<NaruMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == NaruRole.user) {
                String s = messages.get(i).getFilteredContent();
                return s == null ? "" : s;
            }
        }
        return "";
    }

    private static NaruResponse response(NaruMessage message) {
        NaruResponse r = new NaruResponse(message, true);
        r.setStopReason(message.hasToolCalls() ? "tool_calls" : "stop");
        return r;
    }

    private final NaruModelProtocol protocol = new NaruModelProtocol() {
        @Override
        public NaruResponse chat(NaruModelRequest request, NaruModelContext context) {
            for (Function<NaruModelRequest, NaruResponse> rule : RULES) {
                NaruResponse r = rule.apply(request);
                if (r != null) {
                    return r;
                }
            }
            String asked = lastUserText(request).strip();
            if (asked.length() > 60) {
                asked = asked.substring(0, 57) + "...";
            }
            return response(NaruMessage.assistant("(scripted reply to: " + asked + ")"));
        }

        @Override
        public String providerName() {
            return NAME;
        }

        @Override
        public NaruModelCapabilities getCapabilities() {
            return CAPABILITIES;
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
        return List.of("demo");
    }

    @Override
    public boolean isLocal(NaruModelContext context) {
        return true;
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

    private static final NaruModelCapabilities CAPABILITIES = new NaruModelCapabilities() {
        @Override
        public long contextLength() {
            return 32_768;
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
