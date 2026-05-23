package net.thevpc.naru.impl.model.openapi;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.util.NOptional;

import java.util.*;

/**
 * {
 *   "choices" : [ {
 *     "finish_reason" : "tool_calls",
 *     "index" : 0,
 *     "message" : {
 *       "role" : "assistant",
 *       "tool_calls" : [ {
 *         "function" : {
 *           "arguments" : "{\"path\":\"core/nut-api\",\"include\":\"*.java\",\"recursive\":true}",
 *           "name" : "folder_find"
 *         },
 *         "id" : "function-call-11464485064754065774",
 *         "type" : "function"
 *       } ]
 *     }
 *   } ],
 *   "created" : 1779459950,
 *   "id" : "bWcQau20G9n2nsEPs9z20Qw",
 *   "model" : "gemini-2.5-flash",
 *   "object" : "chat.completion",
 *   "usage" : {
 *     "completion_tokens" : 28,
 *     "prompt_tokens" : 4500,
 *     "total_tokens" : 4592
 *   }
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
                // Defensive fallback if choices array is missing
                response.setDone(true);
                response.setMessage(NaruMessage.assistant(""));
                return response;
            }

            NObjectElement firstChoice = choicesArr.get(0).get().asObject().get();

            // Extract the finish reason to determine completion state
            String finishReason = firstChoice.getStringValue("finish_reason").orElse("");
            // "stop" means it completed cleanly; "tool_calls" means it's awaiting execution
            response.setDone("stop".equals(finishReason));

            // Extract the standard nested "message" object
            NObjectElement msg = firstChoice.getObject("message").orNull();
            if (msg == null) {
                response.setMessage(NaruMessage.assistant(""));
                return response;
            }

            String role = msg.getStringValue("role").orElse("assistant");
            String content = msg.getStringValue("content").orElse("");

            // 3. Check for standard OpenAI tool_calls structure
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
                            // Handles stringified JSON arguments safely (Gemini default style)
                            String argsStr = argsEl.asStringValue().get();
                            try {
                                args = NElementReader.ofJson().read(argsStr, Map.class);
                            } catch (Exception ignored) {
                                args.put("raw", argsStr);
                            }
                        }
                    }
                    calls.add(new NaruToolCall(id, name, args));
                }

                response.setMessage(NaruMessage.assistantWithToolCalls(content, calls));
            } else {
                // 4. Content Text Fallback processing (e.g., XML-like tool formats if applicable)
                if (content.startsWith("<function=")) {
                    NaruToolCall a = NaruModelProtocolOpenApiBase.parseXmlLikeToolCall(content);
                    if (a != null) {
                        List<NaruToolCall> calls = new ArrayList<>();
                        calls.add(a);
                        response.setMessage(NaruMessage.assistantWithToolCalls(content, calls));
                        return response;
                    }
                }
                response.setMessage(NaruMessage.assistant(content));
            }
        }
        return response;
    }
}
