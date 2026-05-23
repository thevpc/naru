package net.thevpc.naru.impl.model.ollama;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.impl.model.openapi.NaruModelProtocolOpenApiBase;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.util.NOptional;

import java.util.*;

public class NaruOllamaResponseParser implements NElementDeserializer<NaruResponse> {
    @Override
    public NaruResponse toObject(NElementDeserializerContext context) {
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
}
