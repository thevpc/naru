package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A model whose answers are written in Java: no network, no API key, the same reply every run.
 * <p>
 * It is meant for examples, tests and demos, and for prototyping a custom backend: the
 * {@link #otherwise} fallback turns any function of the request into a provider.
 *
 * <pre>{@code
 * NaruScriptedModelProvider model = new NaruScriptedModelProvider()
 *         .when("capital of tunisia", "Tunis.")
 *         .callTool("where is my order", "lookup_order",
 *                 Map.of("order_id", NElement.ofString("A-1042")),
 *                 result -> "It left the hub: " + result);
 * // a nuts component instance: every session of the workspace finds it,
 * // as "scripted/default"
 * NExtensions.of().registerInstance(NaruModelProvider.class, model);
 * }</pre>
 * <p>
 * Rules are tried in the order they were added, against the last user message; the first
 * that answers wins. A request no rule answers gets the {@link #otherwise} reply, by default
 * an echo of the question.
 */
public class NaruScriptedModelProvider implements NaruModelProvider {
    public static final String DEFAULT_NAME = "scripted";
    public static final String DEFAULT_MODEL = "default";

    private final String name;
    private final List<String> modelIds = new CopyOnWriteArrayList<>(List.of(DEFAULT_MODEL));
    private final List<Function<NaruModelRequest, NaruResponse>> rules = new CopyOnWriteArrayList<>();
    private final AtomicLong callIds = new AtomicLong();
    private volatile Function<NaruModelRequest, NaruMessage> otherwise = request -> {
        String asked = lastUserText(request).strip();
        if (asked.length() > 60) {
            asked = asked.substring(0, 57) + "...";
        }
        return NaruMessage.assistant("(scripted reply to: " + asked + ")");
    };
    private volatile boolean enabled = true;

    /** A provider named {@value #DEFAULT_NAME}, with one model, {@value #DEFAULT_MODEL}. */
    public NaruScriptedModelProvider() {
        this(DEFAULT_NAME);
    }

    /** A provider under another name, so several scripted providers can be registered at once. */
    public NaruScriptedModelProvider(String name) {
        this.name = name;
    }

    /** The model ids this provider lists, replacing {@value #DEFAULT_MODEL}. All answer alike. */
    public NaruScriptedModelProvider models(String... ids) {
        modelIds.clear();
        modelIds.addAll(List.of(ids));
        return this;
    }

    /** Answers {@code reply} when the last user message contains {@code needle} (case-insensitive). */
    public NaruScriptedModelProvider when(String needle, String reply) {
        return when(needle, r -> NaruMessage.assistant(reply));
    }

    /** Answers {@code reply(request)} when the last user message contains {@code needle} (case-insensitive). */
    public NaruScriptedModelProvider when(String needle, Function<NaruModelRequest, NaruMessage> reply) {
        return when(contains(needle), reply);
    }

    /** Answers {@code reply(request)} for the requests {@code matches} accepts. */
    public NaruScriptedModelProvider when(Predicate<NaruModelRequest> matches, Function<NaruModelRequest, NaruMessage> reply) {
        rules.add(r -> matches.test(r) ? response(reply.apply(r)) : null);
        return this;
    }

    /**
     * Asks for {@code tool} with {@code args} the first time the model is offered that tool
     * for a matching prompt, then answers with {@code summarize(toolResult)} once the
     * result is in the history. A request that does not offer the tool falls through to the
     * next rule.
     */
    public NaruScriptedModelProvider callTool(String needle, String tool, Map<String, NElement> args,
                                              Function<String, String> summarize) {
        Predicate<NaruModelRequest> matches = contains(needle);
        rules.add(r -> {
            if (!matches.test(r)) {
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
            NaruToolCall call = new NaruToolCall("call-" + callIds.incrementAndGet(), tool, args);
            return response(NaruMessage.assistantWithToolCalls("", List.of(call)));
        });
        return this;
    }

    /** The reply to a request no rule answers. */
    public NaruScriptedModelProvider otherwise(Function<NaruModelRequest, NaruMessage> reply) {
        this.otherwise = reply;
        return this;
    }

    /** Forgets every rule; the {@link #otherwise} reply stays. */
    public NaruScriptedModelProvider clear() {
        rules.clear();
        return this;
    }

    /** The text of the last user message in {@code request}, or an empty string. */
    public static String lastUserText(NaruModelRequest request) {
        List<NaruMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == NaruRole.user) {
                String s = messages.get(i).getFilteredContent();
                return s == null ? "" : s;
            }
        }
        return "";
    }

    private static Predicate<NaruModelRequest> contains(String needle) {
        String n = needle.toLowerCase();
        return r -> lastUserText(r).toLowerCase().contains(n);
    }

    private static NaruResponse response(NaruMessage message) {
        NaruResponse r = new NaruResponse(message, true);
        r.setStopReason(message.hasToolCalls() ? "tool_calls" : "stop");
        return r;
    }

    private final NaruModelProtocol protocol = new NaruModelProtocol() {
        @Override
        public NaruResponse chat(NaruModelRequest request, NaruModelContext context) {
            for (Function<NaruModelRequest, NaruResponse> rule : rules) {
                NaruResponse r = rule.apply(request);
                if (r != null) {
                    return r;
                }
            }
            return response(otherwise.apply(request));
        }

        @Override
        public String providerName() {
            return name;
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
        return name;
    }

    @Override
    public NOptional<String> apiKey(NaruModelContext context) {
        return NOptional.ofEmpty();
    }

    @Override
    public List<String> findModelIds(NaruModelContext context) {
        return List.copyOf(modelIds);
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
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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
