package net.thevpc.naru.impl.model.openapi;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.net.NWebCli;
import net.thevpc.nuts.net.NWebRequest;
import net.thevpc.nuts.net.NWebResponse;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NChronometer;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class NaruModelProtocolOpenApiBase implements NaruModelProtocol {
    protected final NElementReader nElementReader;
    protected final NaruModelConfig model;
    protected final NaruModelCapabilities capabilities;
    protected final String configPrefix;

    public NaruModelProtocolOpenApiBase(NaruModelConfig model, String configPrefix, NaruModelCapabilities capabilities, NElementDeserializer<NaruResponse> responseParser) {
        this.model = model;
        this.capabilities = capabilities;
        this.configPrefix = configPrefix;
        this.nElementReader = NElementReader.ofJson();
        this.nElementReader.mapperStore().setDeserializer(NaruResponse.class, responseParser);
    }

    public static NaruToolCall parseXmlLikeToolCall(String input) {
        NaruToolCall c = new NaruToolCall();
        // Find the function name first
        Matcher funcMatcher = Pattern.compile("<function=([^>]+)>").matcher(input);
        if (funcMatcher.find()) {
            c.setName(funcMatcher.group(1));
        } else {
            return null;
        }

        // Find all parameters
        Matcher paramMatcher = Pattern.compile("<parameter=([^>]+)>(.*?)</parameter>", Pattern.DOTALL).matcher(input);
        while (paramMatcher.find()) {
            c.getArguments().put(paramMatcher.group(1), paramMatcher.group(2).trim());
        }
        return c;
    }

    protected String url(NaruSession session) {
        String url = session.agent().env().get(configPrefix + ".url").flatMap(x -> x.asStringValue()).orElse("http://localhost:11434");
        return url.replaceAll("/$", "");
    }

    protected NDuration connectTimeout(NaruSession session) {
        return session.agent().env().get(configPrefix + ".connectTimeout").flatMap(x -> x.asStringValue())
                .flatMap(x -> NDuration.parse(x))
                .orElseGetOptionalFrom(
                        () -> session.agent().env().get(configPrefix + ".timeout").flatMap(x -> x.asStringValue())
                                .flatMap(x -> NDuration.parse(x))
                )
                .orElse(NDuration.ofSeconds(30));
    }

    protected NDuration readTimeout(NaruSession session) {
        return session.agent().env().get(configPrefix + ".readTimeout").flatMap(x -> x.asStringValue())
                .flatMap(x -> NDuration.parse(x))
                .orElseGetOptionalFrom(
                        () -> session.agent().env().get(configPrefix + ".timeout").flatMap(x -> x.asStringValue())
                                .flatMap(x -> NDuration.parse(x))
                )
                .orElse(NDuration.ofSeconds(30));
    }

    @Override
    public NaruResponse chat(List<NaruMessage> messages, List<NaruToolDefinition> tools, NaruSession session) {
        Map<String, Object> body = buildRequestBody(model.model(), messages, tools);
        NWebCli http = NWebCli.of()
                .connectTimeout(connectTimeout(session))
                .prefix(url(session));
        NWebRequest request = http.POST("api/chat")
                .timeout(readTimeout(session))
                .jsonRequestBody(body);

        try {
            NChronometer chrono = NChronometer.of();
            NaruUtils.logWebRequest(request, NMsg.ofC("chat with %s", model), body);
            NWebResponse response = request.run().ifErrorThrow();
            String responseString = response.contentAsString();
            NaruUtils.logWebResponse(request, NMsg.ofC("chat with %s", model), responseString, chrono);
            return parseResponse(responseString);
        } catch (Exception e) {
            throw new NIllegalArgumentException(NMsg.ofC("Failed to communicate with Ollama at %s: %s", url(session), e.getMessage(), e));
        }
    }

    // ── Request builder ────────────────────────────────────────────────────────

    protected Map<String, Object> buildRequestBody(String model, List<NaruMessage> messages,
                                                   List<NaruToolDefinition> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);

        List<Map<String, Object>> msgList = new ArrayList<>();
        for (NaruMessage m : messages) {
            msgList.add(messageToMap(m));
        }
        body.put("messages", msgList);

        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> toolList = new ArrayList<>();
            for (NaruToolDefinition t : tools) {
                toolList.add(toMapToolDefinition(t));
            }
            body.put("tools", toolList);
        }
        Map<String, Object> options = buildOptions();
        if (!options.isEmpty()) {
            body.put("options", options);
        }
        return body;
    }

    public Map<String, Object> toMapToolDefinition(NaruToolDefinition definition) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (definition instanceof NaruToolDefinitionFunction) {
            map.put("type", "function");
            map.put("function", toMapFunctionDef(((NaruToolDefinitionFunction) definition)));
        }
        return map;
    }

    public Map<String, Object> toMapFunctionDef(NaruToolDefinitionFunction fct) {
        Map<String, Object> map = new LinkedHashMap<>();

        map.put("name", fct.getName());
        map.put("description", fct.getDescription());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (NaruToolParameter p : fct.getParams()) {
            HashMap<Object, Object> paramSchema = new HashMap<>();
            paramSchema.put("type", p.getType().name().toLowerCase());
            paramSchema.put("description", p.getDescription());
            if (p.getDefaultValue() != null) {
                // it is not supported by gemini
                //paramSchema.put("default", p.getDefaultValue());
            }
            properties.put(p.getName(), paramSchema);
            if (p.isRequired()) {
                required.add(p.getName());
            }
        }
        params.put("required", required);
        params.put("properties", properties);
        if (!properties.isEmpty()) {
            map.put("parameters", params);
        }
        return map;
    }


    protected Map<String, Object> buildOptions() {
        Map<String, Object> options = new LinkedHashMap<>();
        if (this.model.contextLength() != null) options.put("num_ctx", this.model.contextLength());
        if (this.model.temperature() != null) options.put("temperature", this.model.temperature());
        if (this.model.nucleusThreshold() != null) options.put("top_p", this.model.nucleusThreshold());
        if (this.model.candidateCount() != null) options.put("top_k", this.model.candidateCount());
        if (this.model.maxTokens() != null) options.put("num_predict", this.model.maxTokens());
        if (this.model.stop() != null && !this.model.stop().isEmpty()) {
            options.put("stop", this.model.stop());
        }
        return options;
    }

    protected Map<String, Object> messageToMap(NaruMessage m) {
        if (m.getRole() == NaruRole.tool) {
            return toolMessageToMap(m);
        }
        if (m.getRole() == NaruRole.assistant && m.hasToolCalls()) {
            return assistantToolCallMessageToMap(m);
        }
        return userMessageToMap(m); // user + system + assistant text
    }

    protected Map<String, Object> userMessageToMap(NaruMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", m.getRole().id());
        // Regular messages (system / user / assistant text)
        map.put("content", m.getContent() != null ? m.getContent() : "");
        if (m.getImages() != null && !m.getImages().isEmpty()) {
            map.put("images", m.getImages());
        }
        return map;
    }

    protected Map<String, Object> assistantToolCallMessageToMap(NaruMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", m.getRole().id());
        map.put("content", m.getContent() != null ? m.getContent() : "");
        List<Map<String, Object>> calls = new ArrayList<>();
        for (NaruToolCall tc : m.getToolCalls()) {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("id", tc.getId() != null ? tc.getId() : "call_" + tc.getName());
            call.put("type", "function");
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tc.getName());
            fn.put("arguments", tc.getArguments());
            call.put("function", fn);
            calls.add(call);
        }
        map.put("tool_calls", calls);
        return map;
    }

    protected Map<String, Object> toolMessageToMap(NaruMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", m.getRole().id());
        map.put("content", m.getContent() != null ? m.getContent() : "");
        if (m.getToolName() != null) map.put("name", m.getToolName());
        return map;
    }

    // ── Response parser ────────────────────────────────────────────────────────

    protected NaruResponse parseResponse(String json) {
        return nElementReader.read(json, NaruResponse.class);
    }


    @Override
    public NaruModelCapabilities getCapabilities() {
        return capabilities;
    }

}
