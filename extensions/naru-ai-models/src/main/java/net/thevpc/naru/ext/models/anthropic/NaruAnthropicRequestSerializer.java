package net.thevpc.naru.ext.models.anthropic;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.ext.models.openapi.NaruOpenApiRequestSerializer;
import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;

import java.util.*;

/**
 * Serializes a {@link NaruModelRequest} into the Anthropic Messages API wire format:
 * <pre>
 * POST {base}/v1/messages
 * {
 *   "model": "...",
 *   "max_tokens": 4096,                       // REQUIRED by Anthropic
 *   "system": "You are...",                   // top-level, joined system messages
 *   "messages": [
 *     {"role": "user", "content": "..."}                         // plain text
 *     {"role": "user", "content": [{"type":"text",...},{"type":"image",...}]} // multimodal
 *     {"role": "assistant", "content": [...{"type":"tool_use","id":...,"name":...,"input":{...}}]}
 *     {"role": "user", "content": [{"type":"tool_result","tool_use_id":"...","content":"..."}]}
 *   ],
 *   "tools": [{"name":"...","description":"...","input_schema":{...}}],
 *   "temperature": ..., "top_p": ..., "stop_sequences": [...]
 * }
 * </pre>
 */
public class NaruAnthropicRequestSerializer implements NaruModelRequestSerializer {

    private static final int DEFAULT_MAX_TOKENS = 4096;

    @Override
    public NElement serialize(NaruModelRequest request, NaruModelConfig model, NaruSession session) {
        NObjectElementBuilder body = NElement.ofObjectBuilder();

        body.set("model", model.model());

        // max_tokens is mandatory in the Anthropic Messages API
        int maxTokens = model.maxTokens() != null ? model.maxTokens() : DEFAULT_MAX_TOKENS;
        body.set("max_tokens", maxTokens);

        // System messages are hoisted to the top-level "system" field
        List<String> systemParts = new ArrayList<>();
        NArrayElementBuilder msgList = NElement.ofArrayBuilder();
        if (request.messages() != null) {
            for (NaruMessage m : request.messages()) {
                if (m.getRole() == NaruRole.system) {
                    if (m.getContent() != null && !m.getContent().isBlank()) {
                        systemParts.add(m.getContent());
                    }
                } else {
                    msgList.add(messageToElement(m));
                }
            }
        }
        if (!systemParts.isEmpty()) {
            body.set("system", String.join("\n\n", systemParts));
        }
        body.set("messages", msgList.build());

        // Tools (Anthropic native schema without the OpenAI "function" wrapper)
        List<NaruToolDefinition> tools = request.tools();
        if (tools != null && !tools.isEmpty()) {
            NArrayElementBuilder toolList = NElement.ofArrayBuilder();
            for (NaruToolDefinition t : tools) {
                if (t instanceof NaruToolDefinitionFunction) {
                    toolList.add(toAnthropicToolDefinition((NaruToolDefinitionFunction) t));
                }
            }
            body.set("tools", toolList.build());
        }

        // Standard hyper-parameters at the root
        if (model.temperature() != null) {
            body.set("temperature", model.temperature());
        }
        if (model.nucleusThreshold() != null) {
            body.set("top_p", model.nucleusThreshold());
        }
        if (model.stop() != null && !model.stop().isEmpty()) {
            NArrayElementBuilder stopArr = NElement.ofArrayBuilder();
            for (String s : model.stop()) {
                stopArr.add(NElement.ofString(s));
            }
            body.set("stop_sequences", stopArr.build());
        }

        return body.build();
    }

    private NElement toAnthropicToolDefinition(NaruToolDefinitionFunction fct) {
        NObjectElementBuilder tool = NElement.ofObjectBuilder();
        tool.set("name", fct.getName());
        tool.set("description", fct.getDescription() != null ? fct.getDescription() : "");

        NObjectElementBuilder inputSchema = NElement.ofObjectBuilder();
        inputSchema.set("type", "object");

        NObjectElementBuilder propertiesObj = NElement.ofObjectBuilder();
        NArrayElementBuilder requiredArr = NElement.ofArrayBuilder();

        if (fct.getParams() != null) {
            for (NaruToolParameter p : fct.getParams()) {
                propertiesObj.set(p.getName(), NaruOpenApiRequestSerializer.paramToSchema(p));
                if (p.isRequired()) {
                    requiredArr.add(NElement.ofString(p.getName()));
                }
            }
        }

        inputSchema.set("properties", propertiesObj.build());
        if (!requiredArr.children().isEmpty()) {
            inputSchema.set("required", requiredArr.build());
        }
        tool.set("input_schema", inputSchema.build());
        return tool.build();
    }

    private NElement messageToElement(NaruMessage m) {
        NObjectElementBuilder msgObj = NElement.ofObjectBuilder();

        // 1. Tool execution output -> a "user" message carrying a tool_result block
        if (m.getRole() == NaruRole.tool) {
            msgObj.set("role", "user");
            NArrayElementBuilder contentArr = NElement.ofArrayBuilder();
            NObjectElementBuilder tr = NElement.ofObjectBuilder();
            tr.set("type", "tool_result");
            tr.set("tool_use_id", m.getToolCallId() != null ? m.getToolCallId() : "toolu_" + m.getToolName());
            tr.set("content", m.getContent() != null ? m.getContent() : "");
            contentArr.add(tr.build());
            msgObj.set("content", contentArr.build());
            return msgObj.build();
        }

        String role = m.getRole() == NaruRole.assistant ? "assistant" : "user";
        msgObj.set("role", role);

        // 2. Assistant message containing tool invocations -> content blocks
        if (m.getRole() == NaruRole.assistant && m.hasToolCalls()) {
            NArrayElementBuilder contentArr = NElement.ofArrayBuilder();
            if (m.getContent() != null && !m.getContent().isBlank()) {
                NObjectElementBuilder text = NElement.ofObjectBuilder();
                text.set("type", "text");
                text.set("text", m.getContent());
                contentArr.add(text.build());
            }
            for (NaruToolCall tc : m.getToolCalls()) {
                NObjectElementBuilder tu = NElement.ofObjectBuilder();
                tu.set("type", "tool_use");
                tu.set("id", tc.getId() != null ? tc.getId() : "toolu_" + tc.getName());
                tu.set("name", tc.getName());
                tu.set("input", NElement.of(tc.getArguments() != null ? tc.getArguments() : new LinkedHashMap<>()));
                contentArr.add(tu.build());
            }
            msgObj.set("content", contentArr.build());
            return msgObj.build();
        }

        // 3. Multimodal content (user / assistant with base64 images)
        if (m.getImages() != null && !m.getImages().isEmpty()) {
            NArrayElementBuilder contentArr = NElement.ofArrayBuilder();
            NObjectElementBuilder text = NElement.ofObjectBuilder();
            text.set("type", "text");
            text.set("text", m.getContent() != null ? m.getContent() : "");
            contentArr.add(text.build());
            for (String img : m.getImages()) {
                NObjectElementBuilder image = NElement.ofObjectBuilder();
                image.set("type", "image");
                NObjectElementBuilder source = NElement.ofObjectBuilder();
                source.set("type", "base64");
                source.set("media_type", "image/jpeg");
                source.set("data", img);
                image.set("source", source.build());
                contentArr.add(image.build());
            }
            msgObj.set("content", contentArr.build());
            return msgObj.build();
        }

        // 4. Plain text
        msgObj.set("content", m.getContent() != null ? m.getContent() : "");
        return msgObj.build();
    }
}