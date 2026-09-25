package net.thevpc.naru.ext.models.anthropic;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.util.NBlankable;

import java.util.*;

/**
 * Parses an Anthropic Messages API response:
 * <pre>
 * {
 *   "id": "msg_...",
 *   "type": "message",
 *   "role": "assistant",
 *   "content": [
 *     {"type": "text", "text": "Hello"},
 *     {"type": "tool_use", "id": "toolu_1", "name": "web_search", "input": {"q": "..."}}
 *   ],
 *   "stop_reason": "end_turn" | "tool_use" | "max_tokens" | "stop_sequence",
 *   "usage": {"input_tokens": 25, "output_tokens": 30}
 * }
 * </pre>
 */
public class NaruAnthropicResponseParser implements NElementDeserializer<NaruResponse> {
    @Override
    public NaruResponse toObject(NElementDeserializerContext context) {
        NElement e = context.element();
        NaruResponse response = new NaruResponse();

        if (e.isAnyObject()) {
            NObjectElement root = e.asObject().get();

            // 1. Usage
            NObjectElement usageObj = root.getObject("usage").orNull();
            if (usageObj != null) {
                int inputTokens = usageObj.getIntValue("input_tokens").orElse(0);
                int outputTokens = usageObj.getIntValue("output_tokens").orElse(0);
                response.setPromptTokens(inputTokens);
                response.setEvalTokens(outputTokens);
                response.setTotalTokens(inputTokens + outputTokens);

                // Anthropic reports the cache breakdown separately, and
                // input_tokens EXCLUDES both cache reads and cache writes. So the
                // billed input total is the sum of all three, and a cost model
                // that only looks at input_tokens would under-report spending by
                // exactly the cached portion.
                int cacheCreation = usageObj.getIntValue("cache_creation_input_tokens").orElse(-1);
                int cacheRead = usageObj.getIntValue("cache_read_input_tokens").orElse(-1);
                if (cacheCreation >= 0 || cacheRead >= 0) {
                    response.setCacheWriteTokens(Math.max(0, cacheCreation));
                    response.setCacheReadTokens(Math.max(0, cacheRead));
                }
            }

            // 2. Stop reason: end_turn / stop_sequence / tool_use => turn finished
            String stopReason = root.getStringValue("stop_reason").orElse("");
            response.setStopReason(stopReason);
            response.setDone("end_turn".equals(stopReason)
                    || "stop_sequence".equals(stopReason)
                    || "tool_use".equals(stopReason));

            // 3. Content blocks: text -> content, tool_use -> tool calls
            NArrayElement contentArr = root.getArray("content").orNull();
            StringBuilder text = new StringBuilder();
            List<NaruToolCall> calls = new ArrayList<>();
            if (contentArr != null) {
                for (NElement el : contentArr.children()) {
                    NObjectElement block = el.asObject().orNull();
                    if (block == null) {
                        continue;
                    }
                    String type = block.getStringValue("type").orElse("");
                    if ("text".equals(type)) {
                        String t = block.getStringValue("text").orElse("");
                        if (text.length() > 0 && !t.isEmpty()) {
                            text.append("\n");
                        }
                        text.append(t);
                    } else if ("tool_use".equals(type)) {
                        String id = block.getStringValue("id").orElseGet(() -> UUID.randomUUID().toString());
                        String name = block.getStringValue("name").orElse("unknown");
                        Map<String, Object> input = parseInput(block.get("input").orNull());
                        calls.add(new NaruToolCall(id, name, input));
                    }
                }
            }

            if (calls.isEmpty()) {
                response.setMessage(NaruMessage.assistant(text.toString()));
            } else {
                response.setMessage(NaruMessage.assistantWithToolCalls(text.toString(), calls));
            }
        }
        return response;
    }

    public static Map<String, Object> parseInput(NElement inputEl) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (inputEl == null || inputEl.isNull() || inputEl.isEmpty()) {
            return args;
        }
        if (inputEl.isAnyObject()) {
            for (NPairElement entry : inputEl.asObject().get().namedPairs()) {
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
        }
        return args;
    }
}