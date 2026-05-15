package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruRole;

import java.util.ArrayList;
import java.util.List;

/**
 * A single message in a chat conversation.
 * Roles: "system", "user", "assistant", "tool"
 */
public class NaruMessage {

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
