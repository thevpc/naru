package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.util.NCopiable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A single message in a chat conversation.
 * Roles: "system", "user", "assistant", "tool"
 */
public class NaruMessage implements NToElement, NCopiable,Cloneable {

    private String sourceName;
    private NaruSource source=NaruSource.USER;
    private NaruRole role;
    private String content;
    /**
     * Base64-encoded images (for multimodal/vision messages)
     */
    private List<String> images;
    /**
     * Tool call ID (used when role == "tool")
     */
    private String toolCallId;
    /**
     * Tool name (used when role == "tool")
     */
    private String toolName;
    /**
     * Tool calls requested by the assistant
     */
    private List<NaruToolCall> toolCalls;

    public NaruMessage() {
    }

    public NaruMessage(String sourceName, NaruSource source, NaruRole role, String content, List<String> images, String toolCallId, String toolName, List<NaruToolCall> toolCalls) {
        this.sourceName = sourceName;
        this.source = source;
        this.role = role;
        this.content = content;
        this.images = images;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.toolCalls = toolCalls;
    }

    public String getSourceName() {
        return sourceName;
    }

    public NaruMessage setSourceName(String sourceName) {
        this.sourceName = sourceName;
        return this;
    }

    public NaruSource getSource() {
        return source;
    }

    public NaruMessage setSource(NaruSource source) {
        this.source = source;
        return this;
    }

    @Override
    public NaruMessage copy() {
        return clone();
    }

    @Override
    protected NaruMessage clone() {
        NaruMessage e = null;
        try {
            e = (NaruMessage) super.clone();
            if(e.images!=null){
               e.images=new ArrayList<>(images);
            }
            if(e.toolCalls!=null){
               e.toolCalls=toolCalls.stream().map(x->x.copy()).collect(Collectors.toList());
            }
        } catch (CloneNotSupportedException ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    public static NaruMessage of(NElement element) {
        if(element==null){
            return null;
        }
        if(element.isNull()){
            return null;
        }
        return new NaruMessage(element);
    }
    public NaruMessage(NElement element) {
        NObjectElement o = element.asObject().get();
        this.role = NaruRole.valueOf(o.getStringValue("role").get());
        this.content = o.getStringValue("content").orNull();
        this.toolCallId = o.getStringValue("toolCallId").orNull();
        this.toolName = o.getStringValue("toolName").orNull();
        NElement images1 = o.get("images").orNull();
        if (images1 != null && images1.isAnyArray()) {
            images = new ArrayList<>();
            for (NElement nElement : images1.asArray().get()) {
                images.add(nElement.asStringValue().orNull());
            }
        }
        NElement toolCalls1 = o.get("toolCalls").orNull();
        if (toolCalls1 != null && toolCalls1.isAnyArray()) {
            toolCalls = new ArrayList<>();
            for (NElement nElement : toolCalls1.asArray().get()) {
                toolCalls.add(new NaruToolCall(nElement));
            }
        }
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder o = NObjectElementBuilder.of();
        o.set("role", role.name());
        o.set("content", content);
        o.set("content", content);
        o.set("toolName", toolName);
        if (images != null) {
            o.set("images", NElement.ofStringArray(images.toArray(new String[0])));
        }
        if (toolCalls != null) {
            NArrayElementBuilder _toolCalls = NArrayElementBuilder.of();
            for (NaruToolCall call : toolCalls) {
                _toolCalls.add(call.toElement());
            }
            o.set("toolCalls", _toolCalls.build());
        }
        return o.build();
    }

    private NaruMessage(NaruRole role, String content) {
        this.role = role;
        this.content = content;
    }

    // ── factory helpers ──────────────────────────────────────────────────────

    public static NaruMessage system(String content) {
        return new NaruMessage(NaruRole.system, content);
    }

    public static NaruMessage user(String content) {
        return new NaruMessage(NaruRole.user, content);
    }

    public static NaruMessage userWithImages(String content, List<String> base64Images) {
        NaruMessage m = new NaruMessage(NaruRole.user, content);
        m.images = new ArrayList<>(base64Images);
        return m;
    }

    public static NaruMessage assistant(String content) {
        return new NaruMessage(NaruRole.assistant, content);
    }

    public static NaruMessage assistantWithToolCalls(String content, List<NaruToolCall> calls) {
        NaruMessage m = new NaruMessage(NaruRole.assistant, content);
        m.toolCalls = new ArrayList<>(calls);
        return m;
    }

    public static NaruMessage tool(String toolName, String callId, String result) {
        NaruMessage m = new NaruMessage(NaruRole.tool, result);
        m.toolName = toolName;
        m.toolCallId = callId;
        return m;
    }

    // ── getters / setters ────────────────────────────────────────────────────

    public NaruRole getRole() {
        return role;
    }

    public void setRole(NaruRole role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<String> getImages() {
        return images;
    }

    public void setImages(List<String> images) {
        this.images = images;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public List<NaruToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<NaruToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    @Override
    public String toString() {
        return "[" + role + "] " + (content != null ? content : "(tool-calls=" + toolCalls + ")");
    }
}
