package net.thevpc.naru.ext.models.ollama;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.ext.models.NaruModelProtocolBase;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.util.*;

public class NaruOllamaNativeResponseParser implements NElementDeserializer<NaruResponse> {
    @Override
    public NaruResponse toObject(NElementDeserializerContext context) {
        NElement e = context.element();
        NaruResponse response = new NaruResponse();

        if (e.isAnyObject()) {
            NObjectElement root = e.asObject().get();

            // 1. Process termination state flags
            if (root.get("done").isPresent()) {
                response.setDone(root.getBooleanValue("done").orElse(false));
            }

            NObjectElement msg = root.getObject("message").orNull();
            if (msg == null) {
                response.setMessage(NaruMessage.assistant(""));
                return response;
            }

            String role = msg.getStringValue("role").orElse("assistant");
            String content = msg.getStringValue("content").orElse("");

            // 2. Check for tool_calls array block (Native Ollama Spec)
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
                            // Safe fallback mapping loop over NObjectElement entries instead of abstract Map.class unmarshalling
                            for (NPairElement entry : argsEl.asObject().get().namedPairs()) {
                                String k = entry.key().asStringValue().orNull();
                                if (!NBlankable.isBlank(k)) {
                                    args.put(k, toPlainValue(entry.value()));
                                }
                            }
                        } else if (argsEl.isPrimitive()) {
                            // Sometimes native arguments flow down as plain escaped JSON string data blocks
                            String argsStr = argsEl.asStringValue().get();
                            try {
                                Map<?, ?> readMap = NElementReader.ofJson().read(argsStr, Map.class);
                                for (Map.Entry<?, ?> entry : readMap.entrySet()) {
                                    args.put(String.valueOf(entry.getKey()), entry.getValue());
                                }
                            } catch (Exception ignored) {
                                args.put("raw", argsStr);
                            }
                        }
                    }
                    calls.add(new NaruToolCall(id, name, args));
                }

                response.setMessage(NaruMessage.assistantWithToolCalls(content, calls));
            } else {
                // 3. Document/XML string fallback parsing alternative methods
                if (content.startsWith("<function=")) {
                    NaruToolCall a = NaruModelProtocolBase.parseXmlLikeToolCall(content);
                    if (a != null) {
                        List<NaruToolCall> calls = new ArrayList<>();
                        calls.add(a);
                        response.setMessage(NaruMessage.assistantWithToolCalls(content, calls));
                        return response;
                    }
                }
                response.setMessage(NaruMessage.assistant(content));
            }

            // 4. Map native metrics tokens telemetry counters
            if (root.get("prompt_eval_count").isPresent() || root.get("eval_count").isPresent()) {
                int promptTokens = root.getIntValue("prompt_eval_count").orElse(0);
                int evalTokens = root.getIntValue("eval_count").orElse(0);

                response.setPromptTokens(promptTokens);
                response.setEvalTokens(evalTokens);
                response.setTotalTokens(promptTokens + evalTokens);
            }
        }
        return response;
    }

    /**
     * Convert a tool-call argument element to its raw Java value. {@code
     * NElement} string primitives must NOT be passed around as elements: their
     * {@code toString()} yields the TSON representation (delimiter quotes and
     * doubled inner quotes, e.g. {@code "pom.xml"} / {@code ""1.0""}) instead of
     * the plain value ({@code pom.xml} / {@code "1.0"}), which would corrupt any
     * file path or content handed to a tool. Nested objects/arrays are kept as
     * elements (rare in flat tool arguments).
     */
    private static Object toPlainValue(NElement v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isPrimitive()) {
            NPrimitiveElement pv = v.asPrimitive().orNull();
            if (pv != null && pv.value() != null) {
                return pv.value();
            }
            return v.asStringValue().orNull();
        }
        return NElement.simpleOf(v);
    }
}
