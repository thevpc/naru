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
 *
 * <h2>Prompt caching</h2>
 *
 * Anthropic's cache is controlled by {@code cache_control} breakpoints on
 * content blocks; a breakpoint caches everything from the start of the request
 * up to and including the block it sits on. Two things follow from that, and
 * both are handled here rather than by the caller:
 *
 * <ul>
 *   <li><b>Block form is required.</b> A {@code cache_control} cannot ride on a
 *       bare string, so any field that carries a marker is emitted as an array
 *       of blocks. Fields with no marker keep the compact string form, so a
 *       request with caching off is byte-identical to one from before this
 *       class learned about it.</li>
 *   <li><b>Wire order is not segment order.</b> Anthropic evaluates the prefix
 *       as tools, then system, then messages — regardless of the order the
 *       caller laid its segments out in. Markers are therefore resolved against
 *       wire position here. Getting this wrong would place every marker one
 *       position earlier than intended, silently caching less than the caller
 *       asked for.</li>
 * </ul>
 */
public class NaruAnthropicRequestSerializer implements NaruCacheAwareRequestSerializer {

    private static final int DEFAULT_MAX_TOKENS = 4096;

    /**
     * Anthropic rejects a request carrying more than four cache breakpoints.
     * The limit is applied when choosing markers, not by silently dropping
     * extras after the fact, so the surviving markers are the useful ones.
     */
    public static final int MAX_CACHE_BREAKPOINTS = 4;

    @Override
    public NElement serialize(NaruModelRequest request, NaruModelConfig model, NaruSession session,
                              NaruCachePlanView plan) {
        NObjectElementBuilder body = NElement.ofObjectBuilder();

        body.set("model", model.model());

        // max_tokens is mandatory in the Anthropic Messages API
        int maxTokens = model.maxTokens() != null ? model.maxTokens() : DEFAULT_MAX_TOKENS;
        body.set("max_tokens", maxTokens);

        Set<Integer> marked = markedSegments(plan);

        // System messages are hoisted to the top-level "system" field
        List<String> systemParts = new ArrayList<>();
        NArrayElementBuilder systemBlocks = NElement.ofArrayBuilder();
        NArrayElementBuilder msgList = NElement.ofArrayBuilder();

        // message index -> index of the segment that message came from, or -1
        int[] owner = segmentOwners(plan, request.messages());

        if (request.messages() != null) {
            for (int i = 0; i < request.messages().size(); i++) {
                NaruMessage m = request.messages().get(i);
                if (m.getRole() == NaruRole.system) {
                    if (m.getContent() != null && !m.getContent().isBlank()) {
                        systemParts.add(m.getContent());
                        systemBlocks.add(textBlock(m.getContent(), isLastOfSegment(owner, i, marked)).build());
                    }
                } else {
                    msgList.add(messageToElement(m, isLastOfSegment(owner, i, marked)));
                }
            }
        }
        if (!systemParts.isEmpty()) {
            if (marked.isEmpty()) {
                body.set("system", String.join("\n\n", systemParts));
            } else {
                // block form is mandatory once any marker rides on the system field
                body.set("system", systemBlocks.build());
            }
        }
        body.set("messages", msgList.build());

        // Tools (Anthropic native schema without the OpenAI "function" wrapper)
        List<NaruToolDefinition> tools = request.tools();
        if (tools != null && !tools.isEmpty()) {
            NArrayElementBuilder toolList = NElement.ofArrayBuilder();
            int toolSegIndex = toolSegmentIndex(plan);
            boolean markLastTool = marked.contains(toolSegIndex);
            int i = 0;
            for (NaruToolDefinition t : tools) {
                if (t instanceof NaruToolDefinitionFunction) {
                    boolean last = markLastTool && i == tools.size() - 1;
                    toolList.add(toAnthropicToolDefinition((NaruToolDefinitionFunction) t, last));
                }
                i++;
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

    /**
     * Segment indices that must carry a marker, already capped to Anthropic's
     * limit. Empty means "emit exactly the pre-caching body".
     */
    private static Set<Integer> markedSegments(NaruCachePlanView plan) {
        if (plan == null || plan.mode() != NaruCachingMode.EXPLICIT_INLINE) {
            return Collections.emptySet();
        }
        return new LinkedHashSet<>(plan.cacheBreakpoints(MAX_CACHE_BREAKPOINTS));
    }

    /**
     * Maps each message of the flat request to the segment it came from.
     *
     * <p>Relies on the documented invariant that a context's message segments
     * concatenate to {@code request.messages()} in order. When the caller sent
     * no segmentation the array is all -1 and nothing is ever marked, which is
     * what keeps the unsegmented path unchanged.
     */
    private static int[] segmentOwners(NaruCachePlanView plan, List<NaruMessage> messages) {
        if (messages == null) {
            return new int[0];
        }
        int[] owner = new int[messages.size()];
        Arrays.fill(owner, -1);
        if (plan == null) {
            return owner;
        }
        List<NaruContextSegment> segments = plan.segments();
        if (segments.isEmpty()) {
            return owner;
        }
        int cursor = 0;
        for (int s = 0; s < segments.size(); s++) {
            if (segments.get(s).content() instanceof NaruSegmentContent.Messages segMessages) {
                for (int k = 0; k < segMessages.messages().size() && cursor < owner.length; k++) {
                    owner[cursor++] = s;
                }
            }
        }
        return owner;
    }

    /**
     * A message is the last block of its segment if no later message belongs to
     * the same segment. Only the final block of a segment can carry the marker,
     * because the marker caches everything up to and including where it sits.
     */
    private static boolean isLastOfSegment(int[] owner, int index, Set<Integer> marked) {
        int segment = owner[index];
        if (segment < 0 || !marked.contains(segment)) {
            return false;
        }
        for (int j = index + 1; j < owner.length; j++) {
            if (owner[j] == segment) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tools live in their own top-level field, which Anthropic evaluates
     * <em>before</em> system. The segment holding them is the one whose marker
     * lands on the last tool definition.
     */
    private static int toolSegmentIndex(NaruCachePlanView plan) {
        if (plan == null) {
            return -1;
        }
        List<NaruContextSegment> segments = plan.segments();
        for (int s = 0; s < segments.size(); s++) {
            if (segments.get(s).content() instanceof NaruSegmentContent.Tools) {
                return s;
            }
        }
        return -1;
    }

    private static NObjectElementBuilder textBlock(String text, boolean markCache) {
        NObjectElementBuilder block = NElement.ofObjectBuilder();
        block.set("type", "text");
        block.set("text", text != null ? text : "");
        if (markCache) {
            markCache(block);
        }
        return block;
    }

    private static void markCache(NObjectElementBuilder block) {
        NObjectElementBuilder cacheControl = NElement.ofObjectBuilder();
        cacheControl.set("type", "ephemeral");
        block.set("cache_control", cacheControl.build());
    }

    private NElement toAnthropicToolDefinition(NaruToolDefinitionFunction fct, boolean markCache) {
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
        if (markCache) {
            markCache(tool);
        }
        return tool.build();
    }

    private NElement messageToElement(NaruMessage m, boolean markCache) {
        NObjectElementBuilder msgObj = NElement.ofObjectBuilder();

        // 1. Tool execution output -> a "user" message carrying a tool_result block
        if (m.getRole() == NaruRole.tool) {
            msgObj.set("role", "user");
            NArrayElementBuilder contentArr = NElement.ofArrayBuilder();
            NObjectElementBuilder tr = NElement.ofObjectBuilder();
            tr.set("type", "tool_result");
            tr.set("tool_use_id", m.getToolCallId() != null ? m.getToolCallId() : "toolu_" + m.getToolName());
            tr.set("content", m.getContent() != null ? m.getContent() : "");
            if (markCache) {
                markCache(tr);
            }
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
                contentArr.add(textBlock(m.getContent(), false).build());
            }
            int lastCall = m.getToolCalls().size() - 1;
            int c = 0;
            for (NaruToolCall tc : m.getToolCalls()) {
                NObjectElementBuilder tu = NElement.ofObjectBuilder();
                tu.set("type", "tool_use");
                tu.set("id", tc.getId() != null ? tc.getId() : "toolu_" + tc.getName());
                tu.set("name", tc.getName());
                tu.set("input", NElement.of(tc.getArguments() != null ? tc.getArguments() : new LinkedHashMap<>()));
                if (markCache && c == lastCall) {
                    markCache(tu);
                }
                contentArr.add(tu.build());
                c++;
            }
            msgObj.set("content", contentArr.build());
            return msgObj.build();
        }

        // 3. Multimodal content (user / assistant with base64 images)
        if (m.getImages() != null && !m.getImages().isEmpty()) {
            List<NObjectElementBuilder> blocks = new ArrayList<>();
            blocks.add(textBlock(m.getContent(), false));
            for (String img : m.getImages()) {
                NObjectElementBuilder image = NElement.ofObjectBuilder();
                image.set("type", "image");
                NObjectElementBuilder source = NElement.ofObjectBuilder();
                source.set("type", "base64");
                source.set("media_type", "image/jpeg");
                source.set("data", img);
                image.set("source", source.build());
                blocks.add(image);
            }
            if (markCache) {
                // the marker must sit on the final block to cover the whole message
                markCache(blocks.get(blocks.size() - 1));
            }
            NArrayElementBuilder contentArr = NElement.ofArrayBuilder();
            for (NObjectElementBuilder b : blocks) {
                contentArr.add(b.build());
            }
            msgObj.set("content", contentArr.build());
            return msgObj.build();
        }

        // 4. Plain text. A marker forces the block form, since cache_control
        //    cannot be attached to a bare JSON string.
        if (markCache) {
            msgObj.set("content", contentArrOf(textBlock(m.getContent(), true).build()));
            return msgObj.build();
        }
        msgObj.set("content", m.getContent() != null ? m.getContent() : "");
        return msgObj.build();
    }

    private static NElement contentArrOf(NElement block) {
        NArrayElementBuilder arr = NElement.ofArrayBuilder();
        arr.add(block);
        return arr.build();
    }
}
