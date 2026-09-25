package net.thevpc.naru.ext.models.openapi;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.ext.models.NaruModelProtocolBase;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.util.*;

/**
 * {
 * "choices" : [ {
 * "finish_reason" : "tool_calls",
 * "index" : 0,
 * "message" : {
 * "role" : "assistant",
 * "tool_calls" : [ {
 * "function" : {
 * "arguments" : "{\"path\":\"core/nut-api\",\"include\":\"*.java\",\"recursive\":true}",
 * "name" : "folder_find"
 * },
 * "id" : "function-call-11464485064754065774",
 * "type" : "function"
 * } ]
 * }
 * } ],
 * "created" : 1779459950,
 * "id" : "bWcQau20G9n2nsEPs9z20Qw",
 * "model" : "gemini-2.5-flash",
 * "object" : "chat.completion",
 * "usage" : {
 * "completion_tokens" : 28,
 * "prompt_tokens" : 4500,
 * "total_tokens" : 4592
 * }
 * }
 */
public class NaruOpenApiResponseParser implements NElementDeserializer<NaruResponse> {
    @Override
    public NaruResponse toObject(NElementDeserializerContext context) {
        NElement e = context.element();
        NaruResponse response = new NaruResponse();

        if (e.isAnyObject()) {
            NObjectElement root = e.asObject().get();

            // 1. Map OpenAI Usage Metrics block
            NObjectElement usageObj = root.getObject("usage").orNull();
            if (usageObj != null) {
                int promptTokens = usageObj.getIntValue("prompt_tokens").orElse(0);
                int completionTokens = usageObj.getIntValue("completion_tokens").orElse(0);
                int totalTokens = usageObj.getIntValue("total_tokens").orElse(0);

                response.setPromptTokens(promptTokens);
                response.setEvalTokens(completionTokens);
                response.setTotalTokens(totalTokens);
            }

            // 2. Locate the first entry in the choices array
            NArrayElement choicesArr = root.getArray("choices").orNull();
            if (choicesArr == null || choicesArr.isEmpty()) {
                response.setDone(true);
                response.setMessage(NaruMessage.assistant(""));
                return response;
            }

            NObjectElement firstChoice = choicesArr.get(0).get().asObject().get();

            // FIXES BUG #1: The turn generation is complete if it hits "stop" OR "tool_calls"
            String finishReason = firstChoice.getStringValue("finish_reason").orElse("");
            response.setStopReason(finishReason);
            response.setDone("stop".equals(finishReason) || "tool_calls".equals(finishReason));

            // Extract the standard nested "message" object
            NObjectElement msg = firstChoice.getObject("message").orNull();
            if (msg == null) {
                response.setMessage(NaruMessage.assistant(""));
                return response;
            }

            String role = msg.getStringValue("role").orElse("assistant");
            String content = msg.getStringValue("content").orElse("");

            // 2b. Extract reasoning/thinking channel (provider-specific shapes)
            String thinking = msg.getStringValue("reasoning_content").orNull();
            if (thinking == null) {
                thinking = msg.getStringValue("reasoning").orNull();
            }

            // 3. Check for standard OpenAI tool_calls structure
            NOptional<NElement> toolCallsOpt = msg.get("tool_calls");
            if (toolCallsOpt.isPresent() && !toolCallsOpt.isNull()) {
                NArrayElement toolCallsArr = msg.getArray("tool_calls").orNull();
                List<NaruToolCall> calls = new ArrayList<>();
                if (toolCallsArr != null) {
                    for (NElement el : toolCallsArr) {
                        NObjectElement tcObj = el.asObject().get();
                        String id = tcObj.getStringValue("id").orElseGet(() -> UUID.randomUUID().toString());

                        NObjectElement fn = tcObj.getObject("function").orElse(tcObj);
                        String name = fn.getStringValue("name").orElse("unknown");

                        Map<String, Object> args = new LinkedHashMap<>();
                        if (fn.get("arguments").isPresent()) {
                            args = parseArguments(fn.get("arguments").get());
                        }
                        calls.add(new NaruToolCall(id, name, args));
                    }
                }

                response.setMessage(NaruMessage.assistantWithToolCalls(content, calls).setThinking(thinking));
            } else {
                // 4. Inline <think>...</think> blocks (DeepSeek/Ollama style)
                String cleanedContent = content;
                if (content != null && content.contains("<think>")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                            "<think>(.*?)</think>", java.util.regex.Pattern.DOTALL).matcher(content);
                    StringBuilder inlineThinking = new StringBuilder();
                    while (m.find()) {
                        if (inlineThinking.length() > 0) {
                            inlineThinking.append("\n\n");
                        }
                        inlineThinking.append(m.group(1).trim());
                    }
                    cleanedContent = content.replaceAll("(?s)<think>.*?</think>", "").trim();
                    if (thinking == null && inlineThinking.length() > 0) {
                        thinking = inlineThinking.toString();
                    }
                }

                // 5. Fallback check for embedded tool calls (XML / <tool_call> / <|tool_call|>)
                List<NaruToolCall> embeddedCalls = parseEmbeddedToolCalls(cleanedContent);
                if (!embeddedCalls.isEmpty()) {
                    response.setMessage(NaruMessage.assistantWithToolCalls(cleanedContent, embeddedCalls).setThinking(thinking));
                    return response;
                }

                response.setMessage(NaruMessage.assistant(cleanedContent != null ? cleanedContent : "").setThinking(thinking));
            }
        }
        return response;
    }

    public static Map<String, Object> parseArguments(NElement argsEl) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (argsEl == null || argsEl.isNull() || argsEl.isEmpty()) {
            return args;
        }
        if (argsEl.isAnyObject()) {
            for (NPairElement entry : argsEl.asObject().get().namedPairs()) {
                String k = entry.key().asStringValue().orNull();
                if (!NBlankable.isBlank(k)) {
                    NElement val = entry.value();
                    if (val != null) {
                        if (val.isPrimitive()) {
                            args.put(k, val.asPrimitive().get().value());
                        } else {
                            args.put(k, NElement.simpleOf(val));
                        }
                    }
                }
            }
            return args;
        }
        if (argsEl.isPrimitive()) {
            String argsStr = argsEl.asStringValue().orElse("").trim();
            if (argsStr.isEmpty() || "{}".equals(argsStr)) {
                return args;
            }
            // Strip markdown code block wrappers if present (e.g. ```json ... ```)
            if (argsStr.startsWith("```")) {
                int firstNewline = argsStr.indexOf('\n');
                int lastBacktick = argsStr.lastIndexOf("```");
                if (firstNewline != -1 && lastBacktick > firstNewline) {
                    argsStr = argsStr.substring(firstNewline + 1, lastBacktick).trim();
                }
            }
            try {
                Map<?, ?> readMap = NElementReader.ofJson().read(argsStr, Map.class);
                if (readMap != null) {
                    for (Map.Entry<?, ?> entry : readMap.entrySet()) {
                        args.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
            } catch (Exception e1) {
                try {
                    // Try reading as TSON
                    NElement tsonEl = NElementReader.ofTson().read(argsStr);
                    if (tsonEl != null && tsonEl.isAnyObject()) {
                        for (NPairElement entry : tsonEl.asObject().get().namedPairs()) {
                            String k = entry.key().asStringValue().orNull();
                            if (!NBlankable.isBlank(k)) {
                                NElement val = entry.value();
                                if (val != null) {
                                    if (val.isPrimitive()) {
                                        args.put(k, val.asPrimitive().get().value());
                                    } else {
                                        args.put(k, NElement.simpleOf(val));
                                    }
                                }
                            }
                        }
                    } else {
                        args.put("raw", argsStr);
                    }
                } catch (Exception e2) {
                    args.put("raw", argsStr);
                }
            }
        }
        return args;
    }

    public static List<NaruToolCall> parseEmbeddedToolCalls(String content) {
        if (content == null || content.isEmpty()) {
            return Collections.emptyList();
        }
        List<NaruToolCall> calls = new ArrayList<>();

        // 1. Check for <function=name><parameter=key>value</parameter>...</function>
        if (content.contains("<function=")) {
            java.util.regex.Matcher funcMatcher = java.util.regex.Pattern.compile("<function=([^>]+)>(.*?)(</function>|$)", java.util.regex.Pattern.DOTALL).matcher(content);
            if (content.contains("</function>")) {
                funcMatcher = java.util.regex.Pattern.compile("<function=([^>]+)>(.*?)</function>", java.util.regex.Pattern.DOTALL).matcher(content);
            }
            while (funcMatcher.find()) {
                String funcName = funcMatcher.group(1).trim();
                String body = funcMatcher.group(2);
                Map<String, Object> arguments = new LinkedHashMap<>();
                java.util.regex.Matcher paramMatcher = java.util.regex.Pattern.compile("<parameter=([^>]+)>(.*?)</parameter>", java.util.regex.Pattern.DOTALL).matcher(body);
                while (paramMatcher.find()) {
                    arguments.put(paramMatcher.group(1).trim(), paramMatcher.group(2).trim());
                }
                calls.add(new NaruToolCall(UUID.randomUUID().toString(), funcName, arguments));
            }
            if (!calls.isEmpty()) {
                return calls;
            }
        }

        // 2. Check for <tool_call>{"name": "...", "arguments": {...}}</tool_call> or <|tool_call|>...<|end_tool_call|>
        java.util.regex.Pattern[] patterns = new java.util.regex.Pattern[]{
                java.util.regex.Pattern.compile("<\\|tool_call\\|>(.*?)<\\|end_tool_call\\|>", java.util.regex.Pattern.DOTALL),
                java.util.regex.Pattern.compile("<tool_call>(.*?)</tool_call>", java.util.regex.Pattern.DOTALL)
        };
        for (java.util.regex.Pattern pattern : patterns) {
            java.util.regex.Matcher m = pattern.matcher(content);
            while (m.find()) {
                String json = m.group(1).trim();
                try {
                    NElement el = NElementReader.ofJson().read(json);
                    if (el.isAnyObject()) {
                        NObjectElement obj = el.asObject().get();
                        String name = obj.getStringValue("name").orElse(obj.getStringValue("tool").orElse("unknown"));
                        NElement argsEl = obj.get("arguments").orElse(obj.get("args").orNull());
                        Map<String, Object> args = parseArguments(argsEl);
                        calls.add(new NaruToolCall("call_" + UUID.randomUUID().toString().substring(0, 8), name, args));
                    }
                } catch (Exception ignored) {
                }
            }
            if (!calls.isEmpty()) {
                return calls;
            }
        }

        return calls;
    }
}
