package net.thevpc.naru.ext.models.gemini;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.ext.models.anthropic.NaruAnthropicResponseParser;
import net.thevpc.nuts.elem.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Parses a native Gemini {@code generateContent} response.
 *
 * <pre>
 * {
 *   "candidates": [{
 *     "content": {"role": "model", "parts": [
 *        {"text": "..."},
 *        {"functionCall": {"name": "search", "args": {"q": "naru"}}}
 *     ]},
 *     "finishReason": "STOP" | "MAX_TOKENS" | "SAFETY"
 *   }],
 *   "usageMetadata": {
 *     "promptTokenCount": 100,
 *     "candidatesTokenCount": 20,
 *     "totalTokenCount": 120,
 *     "cachedContentTokenCount": 80
 *   }
 * }
 * </pre>
 */
public class NaruGeminiNativeResponseParser implements NElementDeserializer<NaruResponse> {

    @Override
    public NaruResponse toObject(NElementDeserializerContext context) {
        NaruResponse response = new NaruResponse();
        NObjectElement root = context.element().asObject().orNull();
        if (root == null) {
            return response;
        }

        NObjectElement usage = root.getObject("usageMetadata").orNull();
        if (usage != null) {
            int promptTokens = usage.getIntValue("promptTokenCount").orElse(0);
            int outputTokens = usage.getIntValue("candidatesTokenCount").orElse(0);
            response.setPromptTokens(promptTokens);
            response.setEvalTokens(outputTokens);
            response.setTotalTokens(usage.getIntValue("totalTokenCount")
                    .orElse(promptTokens + outputTokens));

            // As with every other provider, cached tokens are a breakdown of the
            // prompt total rather than an addition to it. Whatever was not
            // served from cache was processed at full rate this turn.
            int cached = usage.getIntValue("cachedContentTokenCount").orElse(-1);
            if (cached >= 0) {
                response.setCacheReadTokens(cached);
                response.setCacheWriteTokens(Math.max(0, promptTokens - cached));
            }
        }

        NArrayElement candidates = root.getArray("candidates").orNull();
        if (candidates != null) {
            NObjectElement first = candidates.children().isEmpty() ? null
                    : candidates.children().get(0).asObject().orNull();
            if (first != null) {
                String finish = first.getStringValue("finishReason").orElse("");
                response.setStopReason(finish);
                response.setDone("STOP".equals(finish)
                        || "MAX_TOKENS".equals(finish)
                        || "FINISH_REASON_UNSPECIFIED".equals(finish));

                StringBuilder text = new StringBuilder();
                List<NaruToolCall> calls = new ArrayList<>();
                NObjectElement content = first.getObject("content").orNull();
                NArrayElement parts = content == null ? null : content.getArray("parts").orNull();
                if (parts != null) {
                    for (NElement p : parts.children()) {
                        NObjectElement part = p.asObject().orNull();
                        if (part == null) {
                            continue;
                        }
                        String t = part.getStringValue("text").orNull();
                        if (t != null) {
                            if (text.length() > 0 && !t.isEmpty()) {
                                text.append("\n");
                            }
                            text.append(t);
                        }
                        NObjectElement fnCall = part.getObject("functionCall").orNull();
                        if (fnCall != null) {
                            String name = fnCall.getStringValue("name").orElse("unknown");
                            calls.add(new NaruToolCall(UUID.randomUUID().toString(), name, args(fnCall)));
                        }
                    }
                }
                if (calls.isEmpty()) {
                    response.setMessage(NaruMessage.assistant(text.toString()));
                } else {
                    response.setMessage(NaruMessage.assistantWithToolCalls(text.toString(), calls));
                }
            }
        }
        return response;
    }

    /**
     * Reuses the shared argument conversion rather than reimplementing it, so
     * Gemini and Anthropic cannot drift apart on how a tool's arguments become
     * Java values.
     */
    private static Map<String, Object> args(NObjectElement fnCall) {
        return NaruAnthropicResponseParser.parseInput(fnCall.get("args").orNull());
    }
}
