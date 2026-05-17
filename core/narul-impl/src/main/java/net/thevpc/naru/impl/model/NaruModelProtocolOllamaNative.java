package net.thevpc.naru.impl.model;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.model.*;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.net.NWebCli;
import net.thevpc.nuts.net.NWebRequest;
import net.thevpc.nuts.net.NWebResponse;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NOptional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NaruModelProtocolOllamaNative implements NaruModelProtocol {
    private final String baseUrl;
    private final NElementReader nElementReader;
    private final NWebCli http;
    private final NaruModelKey model;
    private final NaruModelCapabilities capabilities;

    public NaruModelProtocolOllamaNative(NaruModelKey model, String baseUrl, NaruModelCapabilities capabilities) {
        this.model = model;
        this.capabilities = capabilities;
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.http = NWebCli.of()
                .connectTimeout(NDuration.ofSeconds(30))
                .setPrefix(this.baseUrl)
        ;
        nElementReader = NElementReader.ofJson();
        nElementReader.mapperStore().setDeserializer(NaruResponse.class, new NElementDeserializer() {
            @Override
            public Object toObject(NElementDeserializerContext context) {
                NElement e = context.element();
                NaruResponse response = new NaruResponse();
                if (e.isAnyObject()) {
                    NObjectElement root = e.asObject().get();

                    if (root.get("done").isPresent()) {
                        response.setDone(root.get("done").get().asBooleanValue().get());
                    }

                    NObjectElement msg = root.getObject("message").orNull();
                    if (msg == null) {
                        response.setMessage(NaruMessage.assistant(""));
                        return response;
                    }

                    String role = msg.getStringValue("role").orElse("assistant");
                    String content = msg.getStringValue("content").orElse("");

                    // Check for tool_calls
                    NOptional<NElement> toolCallsOpt = msg.get("tool_calls");
                    if (toolCallsOpt.isPresent() && !toolCallsOpt.isNull()) {
                        NArrayElement toolCallsArr = msg.getArray("tool_calls").get();
                        List<NaruToolCall> calls = new ArrayList<>();

                        for (NElement el : toolCallsArr) {
                            NObjectElement tcObj = el.asObject().get();
                            String id = tcObj.getStringValue("id").orElseGet(() -> UUID.randomUUID().toString());

                            NObjectElement fn = tcObj.getObject("function").orElse(tcObj);
                            String name = fn.getStringValue("name").orElse("unknown");

                            Map<String, Object> args = new LinkedHashMap<>();
                            if (fn.get("arguments").isPresent()) {
                                NElement argsEl = fn.get("arguments").get();
                                if (argsEl.isAnyObject()) {
                                    args = context.toObject(argsEl, Map.class);
                                } else if (argsEl.isPrimitive()) {
                                    // Sometimes arguments come as a JSON string
                                    String argsStr = argsEl.asStringValue().get();
                                    try {
                                        args = nElementReader.read(argsStr, Map.class);
                                    } catch (Exception ignored) {
                                        args.put("raw", argsStr);
                                    }
                                }
                            }
                            calls.add(new NaruToolCall(id, name, args));
                        }

                        response.setMessage(NaruMessage.assistantWithToolCalls(content, calls));
                    } else {
                        if (content.startsWith("<function=")) {
                            NaruToolCall a = parseXmlLikeToolCall(content);
                            if (a != null) {
                                List<NaruToolCall> calls = new ArrayList<>();
                                calls.add(a);
                                response.setMessage(NaruMessage.assistantWithToolCalls(content, calls));
                                return response;
                            }
                        }
                        response.setMessage(NaruMessage.assistant(content));
                    }

                    // Optional token usage
                    if (root.get("prompt_eval_count").isPresent() && root.get("eval_count").isPresent()) {
                        int total = root.getIntValue("prompt_eval_count").get() + root.getIntValue("eval_count").get();
                        response.setTotalTokens(total);
                        response.setPromptTokens(root.getIntValue("prompt_eval_count").get());
                        response.setEvalTokens(root.getIntValue("eval_count").get());
                    }

                }
                return response;
            }
        });
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


    @Override
    public NaruResponse chat(List<NaruMessage> messages, List<NaruToolDefinition> tools) {
        Map<String, Object> body = buildRequestBody(model.model(), messages, tools);
        NWebRequest request = http.POST("api/chat")
                .header("Content-Type", "application/json")
                .connectTimeout(NDuration.ofMinutes(10))
                .readTimeout(NDuration.ofMinutes(10))
                .timeout(NDuration.ofMinutes(10))
                .jsonRequestBody(body);

        try {
            NWebResponse response = request.run().failFast();
            String responseString = response.getContentAsString();
            return parseResponse(responseString);
        } catch (Exception e) {
            throw new NIllegalArgumentException(NMsg.ofC("Failed to communicate with Ollama at %s: %s", baseUrl, e.getMessage(), e));
        }
    }

    // ── Request builder ────────────────────────────────────────────────────────

    private Map<String, Object> buildRequestBody(String model, List<NaruMessage> messages,
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
                toolList.add(t.toMap());
            }
            body.put("tools", toolList);
        }

        return body;
    }

    private Map<String, Object> messageToMap(NaruMessage m) {
        if(m.getRole()== NaruRole.tool){
            return toolMessageToMap(m);
        }
        if (m.getRole()==NaruRole.assistant && m.hasToolCalls()) {
            return assistantToolCallMessageToMap(m);
        }

        // Assistant messages with tool_calls
        if ("assistant".equals(m.getRole()) && m.hasToolCalls()) {

        }

        return userMessageToMap(m); // user + system + assistant text
    }

    private Map<String, Object> userMessageToMap(NaruMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", m.getRole().id());
        // Regular messages (system / user / assistant text)
        map.put("content", m.getContent() != null ? m.getContent() : "");
        if (m.getImages() != null && !m.getImages().isEmpty()) {
            map.put("images", m.getImages());
        }
        return map;
    }

    private Map<String, Object> assistantToolCallMessageToMap(NaruMessage m) {
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

    private Map<String, Object> toolMessageToMap(NaruMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", m.getRole().id());
        map.put("content", m.getContent() != null ? m.getContent() : "");
        if (m.getToolName() != null) map.put("name", m.getToolName());
        return map;

    }

    // ── Response parser ────────────────────────────────────────────────────────

    private NaruResponse parseResponse(String json) {
        return nElementReader.read(json, NaruResponse.class);
    }


    @Override
    public NaruModelCapabilities getCapabilities() {
        return capabilities;
    }
}
