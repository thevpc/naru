package net.thevpc.naru.ext.models.gemini;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.ext.models.openapi.NaruOpenApiRequestSerializer;
import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes a {@link NaruModelRequest} into the native Gemini
 * {@code generateContent} wire format.
 *
 * <pre>
 * POST {base}/v1beta/models/{model}:generateContent
 * {
 *   "systemInstruction": {"role": "system", "parts": [{"text": "..."}]},
 *   "contents": [
 *     {"role": "user",      "parts": [{"text": "..."}]},
 *     {"role": "model",     "parts": [{"text": "..."}, {"functionCall": {...}}]},
 *     {"role": "user",      "parts": [{"functionResponse": {...}}]}
 *   ],
 *   "tools": [{"functionDeclarations": [{"name":"...","description":"...","parameters":{...}}]}],
 *   "generationConfig": {"temperature":..., "topP":..., "maxOutputTokens":...,
 *                        "stopSequences":[...]}
 * }
 * </pre>
 *
 * <p>Native Gemini differs from every other protocol NARU speaks in three ways
 * that matter here:
 *
 * <ul>
 *   <li>There is no {@code system} role inside {@code contents}; system material
 *       goes in the separate top-level {@code systemInstruction}.</li>
 *   <li>Assistant is called {@code model}, not {@code assistant}.</li>
 *   <li>Tool definitions are grouped in a single {@code tools[]} entry holding a
 *       list, rather than one entry per tool.</li>
 * </ul>
 *
 * <p>Function calling is still two half-messages rather than one message with
 * both directions: the model's {@code functionCall} parts and the following
 * user's {@code functionResponse} parts are separate {@code contents} entries,
 * which is also how the wire protocol models a tool round trip.
 */
public class NaruGeminiNativeRequestSerializer implements NaruCacheAwareRequestSerializer {

    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;

    @Override
    public NElement serialize(NaruModelRequest request, NaruModelConfig model, NaruSession session,
                              NaruCachePlanView plan) {
        NObjectElementBuilder body = NElement.ofObjectBuilder();

        List<NElement> systemParts = new ArrayList<>();
        NArrayElementBuilder contents = NElement.ofArrayBuilder();

        if (request.messages() != null) {
            for (NaruMessage m : request.messages()) {
                if (m.getRole() == NaruRole.system) {
                    addTextPart(systemParts, m.getContent());
                    continue;
                }
                if (m.getRole() == NaruRole.tool) {
                    contents.add(functionResponse(m));
                    continue;
                }
                String role = m.getRole() == NaruRole.assistant ? "model" : "user";
                NObjectElementBuilder content = NElement.ofObjectBuilder();
                content.set("role", role);
                List<NElement> parts = new ArrayList<>();
                if (m.getContent() != null && !m.getContent().isEmpty()) {
                    addTextPart(parts, m.getContent());
                }
                if (m.getImages() != null) {
                    for (String img : m.getImages()) {
                        NObjectElementBuilder inline = NElement.ofObjectBuilder();
                        inline.set("inline_data", inlineData(img).build());
                        parts.add(inline.build());
                    }
                }
                if (m.getToolCalls() != null) {
                    for (NaruToolCall tc : m.getToolCalls()) {
                        NObjectElementBuilder call = NElement.ofObjectBuilder();
                        call.set("name", tc.getName());
                        call.set("args", NElement.of(tc.getArguments() != null
                                ? tc.getArguments() : new LinkedHashMap<>()));
                        NObjectElementBuilder fn = NElement.ofObjectBuilder();
                        fn.set("functionCall", call.build());
                        parts.add(fn.build());
                    }
                }
                if (parts.isEmpty()) {
                    // Gemini rejects a contents entry with no parts, and an empty
                    // assistant turn happens when a model call produced nothing
                    // usable. Send a single space rather than an invalid request.
                    addTextPart(parts, " ");
                }
                NArrayElementBuilder partArr = NElement.ofArrayBuilder();
                for (NElement p : parts) {
                    partArr.add(p);
                }
                content.set("parts", partArr.build());
                contents.add(content.build());
            }
        }
        body.set("contents", contents.build());

        if (!systemParts.isEmpty()) {
            NObjectElementBuilder instruction = NElement.ofObjectBuilder();
            instruction.set("role", "system");
            NArrayElementBuilder arr = NElement.ofArrayBuilder();
            for (NElement p : systemParts) {
                arr.add(p);
            }
            instruction.set("parts", arr.build());
            body.set("systemInstruction", instruction.build());
        }

        List<NaruToolDefinition> tools = request.tools();
        if (tools != null && !tools.isEmpty()) {
            NArrayElementBuilder declarations = NElement.ofArrayBuilder();
            for (NaruToolDefinition t : tools) {
                if (t instanceof NaruToolDefinitionFunction) {
                    declarations.add(declaration((NaruToolDefinitionFunction) t));
                }
            }
            NObjectElementBuilder toolObj = NElement.ofObjectBuilder();
            toolObj.set("functionDeclarations", declarations.build());
            NArrayElementBuilder toolArr = NElement.ofArrayBuilder();
            toolArr.add(toolObj.build());
            body.set("tools", toolArr.build());
        }

        NObjectElementBuilder genConfig = NElement.ofObjectBuilder();
        boolean hasConfig = false;
        if (model.temperature() != null) {
            genConfig.set("temperature", model.temperature());
            hasConfig = true;
        }
        if (model.nucleusThreshold() != null) {
            genConfig.set("topP", model.nucleusThreshold());
            hasConfig = true;
        }
        if (model.candidateCount() != null && model.candidateCount() > 1) {
            genConfig.set("candidateCount", model.candidateCount());
            hasConfig = true;
        }
        if (model.maxTokens() != null) {
            genConfig.set("maxOutputTokens", model.maxTokens());
            hasConfig = true;
        }
        if (model.stop() != null && !model.stop().isEmpty()) {
            NArrayElementBuilder stopArr = NElement.ofArrayBuilder();
            for (String s : model.stop()) {
                stopArr.add(NElement.ofString(s));
            }
            genConfig.set("stopSequences", stopArr.build());
            hasConfig = true;
        }
        if (!hasConfig) {
            // Gemini rejects a generationConfig with no recognisable fields
            genConfig.set("maxOutputTokens", DEFAULT_MAX_OUTPUT_TOKENS);
        }
        body.set("generationConfig", genConfig.build());

        return body.build();
    }

    private static void addTextPart(List<NElement> into, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        NObjectElementBuilder part = NElement.ofObjectBuilder();
        part.set("text", text);
        into.add(part.build());
    }

    private static NObjectElementBuilder inlineData(String base64) {
        NObjectElementBuilder data = NElement.ofObjectBuilder();
        data.set("mime_type", "image/jpeg");
        data.set("data", base64);
        return data;
    }

    /**
     * A tool result is a {@code functionResponse} part on a {@code user} turn.
     * Gemini matches it to the call by name, not by id, so the tool name is what
     * has to survive the translation.
     */
    private static NElement functionResponse(NaruMessage m) {
        NObjectElementBuilder response = NElement.ofObjectBuilder();
        response.set("name", m.getToolName() != null ? m.getToolName() : "tool");
        NObjectElementBuilder wrapper = NElement.ofObjectBuilder();
        wrapper.set("result", m.getContent() != null ? m.getContent() : "");
        response.set("response", wrapper.build());

        NObjectElementBuilder part = NElement.ofObjectBuilder();
        part.set("functionResponse", response.build());

        NObjectElementBuilder content = NElement.ofObjectBuilder();
        content.set("role", "user");
        NArrayElementBuilder partArr = NElement.ofArrayBuilder();
        partArr.add(part.build());
        content.set("parts", partArr.build());
        return content.build();
    }

    private static NElement declaration(NaruToolDefinitionFunction fct) {
        NObjectElementBuilder d = NElement.ofObjectBuilder();
        d.set("name", fct.getName());
        d.set("description", fct.getDescription() != null ? fct.getDescription() : "");

        NObjectElementBuilder properties = NElement.ofObjectBuilder();
        NArrayElementBuilder required = NElement.ofArrayBuilder();
        if (fct.getParams() != null) {
            for (NaruToolParameter p : fct.getParams()) {
                properties.set(p.getName(), NaruOpenApiRequestSerializer.paramToSchema(p));
                if (p.isRequired()) {
                    required.add(NElement.ofString(p.getName()));
                }
            }
        }
        NObjectElementBuilder schema = NElement.ofObjectBuilder();
        schema.set("type", "object");
        schema.set("properties", properties.build());
        if (!required.children().isEmpty()) {
            schema.set("required", required.build());
        }
        d.set("parameters", schema.build());
        return d.build();
    }
}
