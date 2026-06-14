package net.thevpc.naru.impl.ia.model.ollama;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.nuts.elem.*;

import java.util.*;

public class NaruOllamaNativeRequestSerializer implements NaruModelRequestSerializer {

    @Override
    public NElement serialize(NaruModelRequest request, NaruModelConfig model, NaruSession session) {
        NObjectElementBuilder body = NElement.ofObjectBuilder();

        body.set("model", model.model());
        body.set("stream", false);

        // 1. Process Messages (Native Flat-Multimodal and Tool Context formatting)
        NArrayElementBuilder msgList = NElement.ofArrayBuilder();
        if (request.messages() != null) {
            for (NaruMessage m : request.messages()) {
                msgList.add(messageToElement(m));
            }
        }
        body.set("messages", msgList.build());

        // 2. Process Tools (Native Flat Schema array format)
        List<NaruToolDefinition> tools = request.tools();
        if (tools != null && !tools.isEmpty()) {
            NArrayElementBuilder toolList = NElement.ofArrayBuilder();
            for (NaruToolDefinition t : tools) {
                if (t instanceof NaruToolDefinitionFunction) {
                    toolList.add(toNativeToolDefinition((NaruToolDefinitionFunction) t));
                }
            }
            body.set("tools", toolList.build());
        }

        // 3. Process Configuration Parameters (Nested inside an options block)
        NElement options = buildNativeOptions(model);
        if (options != null && !options.isEmpty()) {
            body.set("options", options);
        }

        return body.build();
    }

    private NElement toNativeToolDefinition(NaruToolDefinitionFunction fct) {
        // This block must be returned directly at the root level of the tool entry array
        NObjectElementBuilder functionBlock = NElement.ofObjectBuilder();
        functionBlock.set("name", fct.getName());
        functionBlock.set("description", fct.getDescription());

        NObjectElementBuilder paramsObj = NElement.ofObjectBuilder();
        paramsObj.set("type", "object");

        NObjectElementBuilder propertiesObj = NElement.ofObjectBuilder();
        NArrayElementBuilder requiredArr = NElement.ofArrayBuilder();

        if (fct.getParams() != null) {
            for (NaruToolParameter p : fct.getParams()) {
                NObjectElementBuilder paramSchema = NElement.ofObjectBuilder();
                paramSchema.set("type", p.getType().name().toLowerCase());
                paramSchema.set("description", p.getDescription());
                propertiesObj.set(p.getName(), paramSchema.build());

                if (p.isRequired()) {
                    requiredArr.add(NElement.ofString(p.getName()));
                }
            }
        }

        paramsObj.set("properties", propertiesObj.build());
        paramsObj.set("required", requiredArr.build());

        if (!propertiesObj.children().isEmpty()) {
            functionBlock.set("parameters", paramsObj.build());
        }else {
            NObjectElementBuilder emptyParams = NElement.ofObjectBuilder();
            emptyParams.set("type", "object");
            emptyParams.set("properties", NElement.ofObjectBuilder().build());
            functionBlock.set("parameters", emptyParams.build());
        }

        // FIXED: Return the function object directly without wrapping it in type/function blocks
        return functionBlock.build();
    }

    private NElement buildNativeOptions(NaruModelConfig model) {
        NObjectElementBuilder options = NElement.ofObjectBuilder();
        if (model.contextLength() != null) options.set("num_ctx", model.contextLength());
        if (model.temperature() != null) options.set("temperature", model.temperature());
        if (model.nucleusThreshold() != null) options.set("top_p", model.nucleusThreshold());
        if (model.candidateCount() != null) options.set("top_k", model.candidateCount());
        if (model.maxTokens() != null) options.set("num_predict", model.maxTokens());
        if (model.stop() != null && !model.stop().isEmpty()) {
            NArrayElementBuilder stopArr = NElement.ofArrayBuilder();
            for (String s : model.stop()) {
                stopArr.add(NElement.ofString(s));
            }
            options.set("stop", stopArr.build());
        }
        return options.build();
    }

    private NElement messageToElement(NaruMessage m) {
        NObjectElementBuilder msgObj = NElement.ofObjectBuilder();
        msgObj.set("role", m.getRole().id());
        msgObj.set("content", m.getContent() != null ? m.getContent() : ""); // default for all

        // 1. Tool Message Execution Outputs (Native spec tracking)
        if (m.getRole() == NaruRole.tool) {
            return msgObj.build();
        }

        // 2. Assistant Message Containing Tool Invocations
        if (m.getRole() == NaruRole.assistant && m.hasToolCalls()) {
            msgObj.set("content", "");
            NArrayElementBuilder callsArr = NElement.ofArrayBuilder();
            for (NaruToolCall tc : m.getToolCalls()) {
                NObjectElementBuilder call = NElement.ofObjectBuilder();
                NObjectElementBuilder fn = NElement.ofObjectBuilder();

                fn.set("name", tc.getName());

                if (tc.getArguments() != null) {
                    // Ollama native engine requires arguments to be a serialized string in history contexts
                    String jsonStringArgs = NElementWriter.ofJson().formatPlain(NElements.of().toElement(tc.getArguments()));
                    fn.set("arguments", NElement.ofString(jsonStringArgs));
                }

                call.set("id", "call_"+tc.getName());
                call.set("type", "function");
                call.set("function", fn.build());
                callsArr.add(call.build());
            }
            msgObj.set("tool_calls", callsArr.build());
            return msgObj.build();
        }

        // 3. Multimodal Content (User / System with Images)
        if (m.getImages() != null && !m.getImages().isEmpty()) {
            NArrayElementBuilder imagesArr = NElement.ofArrayBuilder();
            for (String img : m.getImages()) {
                // Native Ollama expects raw base64 data strings without data-URI schema prefixes
                imagesArr.add(NElement.ofString(img));
            }
            // Images sit as a flat array on the message root level, alongside plain text "content"
            msgObj.set("images", imagesArr.build());
        }

        return msgObj.build();
    }
}
