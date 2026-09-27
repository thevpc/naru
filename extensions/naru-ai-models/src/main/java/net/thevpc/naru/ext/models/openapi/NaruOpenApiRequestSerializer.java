package net.thevpc.naru.ext.models.openapi;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.elem.NObjectElementBuilder;

import java.util.*;

public class NaruOpenApiRequestSerializer implements NaruModelRequestSerializer {

    @Override
    public NElement serialize(NaruModelRequest request, NaruModelConfig model, NaruSession session) {
        return serialize(request, model, session, false);
    }

    @Override
    public NElement serialize(NaruModelRequest request, NaruModelConfig model, NaruSession session, boolean stream) {
        NObjectElementBuilder body = NElement.ofObjectBuilder();

        body.set("model", model.model());
        // Set explicitly rather than left to default: the whole point of the
        // streaming call is that the server sends frames, and a body that merely
        // omits the flag gets a single buffered document back.
        body.set("stream", stream);
        if (stream) {
            // Without this most OpenAI-compatible servers omit usage from the
            // stream, and a streamed turn would then be metered as free while
            // the identical batched turn is charged. That asymmetry is a billing
            // bug, not a missing field.
            body.set("stream_options",
                    NElement.ofObjectBuilder()
                            .set("include_usage", true)
                            .build());
        }

        // Process Messages
        NArrayElementBuilder msgList = NElement.ofArrayBuilder();
        if (request.messages() != null) {
            for (NaruMessage m : request.messages()) {
                msgList.add(messageToElement(m));
            }
        }
        body.set("messages", msgList.build());

        // Process Tools (OpenAI wrapped schema format)
        List<NaruToolDefinition> tools = request.tools();
        if (tools != null && !tools.isEmpty()) {
            NArrayElementBuilder toolList = NElement.ofArrayBuilder();
            for (NaruToolDefinition t : tools) {
                if (t instanceof NaruToolDefinitionFunction) {
                    toolList.add(toOpenAiToolDefinition((NaruToolDefinitionFunction) t));
                }
            }
            body.set("tools", toolList.build());
        }

        // Standard OpenAI Hyper-parameters belong right at the root, NOT inside an options block
        if (model.temperature() != null) body.set("temperature", model.temperature());
        if (model.nucleusThreshold() != null) body.set("top_p", model.nucleusThreshold());
        if (model.maxTokens() != null) body.set("max_tokens", model.maxTokens());
        if (model.stop() != null && !model.stop().isEmpty()) {
            NArrayElementBuilder stopArr = NElement.ofArrayBuilder();
            for (String s : model.stop()) {
                stopArr.add(NElement.ofString(s));
            }
            body.set("stop", stopArr.build());
        }

        return body.build();
    }

    private NElement toOpenAiToolDefinition(NaruToolDefinitionFunction fct) {
        NObjectElementBuilder functionBlock = NElement.ofObjectBuilder();
        functionBlock.set("name", fct.getName());
        functionBlock.set("description", fct.getDescription() != null ? fct.getDescription() : "");

        NObjectElementBuilder paramsObj = NElement.ofObjectBuilder();
        paramsObj.set("type", "object");

        NObjectElementBuilder propertiesObj = NElement.ofObjectBuilder();
        NArrayElementBuilder requiredArr = NElement.ofArrayBuilder();

        if (fct.getParams() != null) {
            for (NaruToolParameter p : fct.getParams()) {
                propertiesObj.set(p.getName(), paramToSchema(p));

                if (p.isRequired()) {
                    requiredArr.add(NElement.ofString(p.getName()));
                }
            }
        }

        paramsObj.set("properties", propertiesObj.build());
        if (!requiredArr.children().isEmpty()) {
            paramsObj.set("required", requiredArr.build());
        }

        functionBlock.set("parameters", paramsObj.build());

        return NElement.ofObjectBuilder()
                .set("type", "function")
                .set("function", functionBlock.build())
                .build();
    }

    public static NElement paramToSchema(NaruToolParameter p) {
        NObjectElementBuilder paramSchema = NElement.ofObjectBuilder();
        paramSchema.set("type", p.getType() != null ? p.getType().name().toLowerCase() : "string");
        if (p.getDescription() != null) {
            paramSchema.set("description", p.getDescription());
        }
        if (p.getDefaultValue() != null) {
            paramSchema.set("default", NElement.of(p.getDefaultValue()));
        }
        if (p.getEnumValues() != null && !p.getEnumValues().isEmpty()) {
            NArrayElementBuilder enumArr = NElement.ofArrayBuilder();
            for (Object val : p.getEnumValues()) {
                enumArr.add(NElement.of(val));
            }
            paramSchema.set("enum", enumArr.build());
        }
        if (p.getType() == NaruToolParameter.Type.ARRAY) {
            if (p.getItemType() != null) {
                paramSchema.set("items", paramToSchema(p.getItemType()));
            } else {
                NObjectElementBuilder itemSchema = NElement.ofObjectBuilder();
                itemSchema.set("type", "string");
                paramSchema.set("items", itemSchema.build());
            }
        } else if (p.getType() == NaruToolParameter.Type.OBJECT) {
            NObjectElementBuilder nestedProps = NElement.ofObjectBuilder();
            NArrayElementBuilder nestedReq = NElement.ofArrayBuilder();
            if (p.getProperties() != null) {
                for (NaruToolParameter np : p.getProperties()) {
                    nestedProps.set(np.getName(), paramToSchema(np));
                    if (np.isRequired()) {
                        nestedReq.add(NElement.ofString(np.getName()));
                    }
                }
            }
            paramSchema.set("properties", nestedProps.build());
            if (!nestedReq.children().isEmpty()) {
                paramSchema.set("required", nestedReq.build());
            }
        }
        return paramSchema.build();
    }

    private NElement messageToElement(NaruMessage m) {
        NObjectElementBuilder msgObj = NElement.ofObjectBuilder();
        msgObj.set("role", m.getRole().id());
        msgObj.set("content", m.getContent() != null ? m.getContent() : "");

        // 1. Tool Message Execution Outputs
        if (m.getRole() == NaruRole.tool) {
            msgObj.set("tool_call_id", m.getToolCallId() != null ? m.getToolCallId() : "call_" + m.getToolName());
            if (m.getToolName() != null) {
                msgObj.set("name", m.getToolName());
            }
            return msgObj.build();
        }

        // 2. Assistant Message Containing Tool Invocations
        if (m.getRole() == NaruRole.assistant && m.hasToolCalls()) {
            NArrayElementBuilder callsArr = NElement.ofArrayBuilder();
            for (NaruToolCall tc : m.getToolCalls()) {
                NObjectElementBuilder call = NElement.ofObjectBuilder();
                String callId = tc.getId() != null ? tc.getId() : "call_" + tc.getName();

                call.set("id", callId);
                call.set("type", "function");

                NObjectElementBuilder fn = NElement.ofObjectBuilder();
                fn.set("name", tc.getName());

                if (tc.getArguments() != null) {
                    // FIXED: OpenAI strictly mandates that arguments are passed as a JSON-escaped string
                    String jsonStringArgs = NElementWriter.ofJson().formatPlain(NElement.of(tc.getArguments()));
                    fn.set("arguments", NElement.ofString(jsonStringArgs));
                } else {
                    fn.set("arguments", NElement.ofString("{}"));
                }

                call.set("function", fn.build());
                callsArr.add(call.build());
            }
            msgObj.set("tool_calls", callsArr.build());
            return msgObj.build();
        }

        // 3. Multimodal Content (User / System with Images)
        if (m.getImages() != null && !m.getImages().isEmpty()) {
            NArrayElementBuilder contentParts = NElement.ofArrayBuilder();

            NObjectElementBuilder textPart = NElement.ofObjectBuilder();
            textPart.set("type", "text");
            textPart.set("text", m.getContent() != null ? m.getContent() : "");
            contentParts.add(textPart.build());

            for (String img : m.getImages()) {
                NObjectElementBuilder imgPart = NElement.ofObjectBuilder();
                imgPart.set("type", "image_url");

                NObjectElementBuilder imgUrl = NElement.ofObjectBuilder();
                imgUrl.set("url", "data:image/jpeg;base64," + img);

                imgPart.set("image_url", imgUrl.build());
                contentParts.add(imgPart.build());
            }
            msgObj.set("content", contentParts.build());
        }

        return msgObj.build();
    }
}
