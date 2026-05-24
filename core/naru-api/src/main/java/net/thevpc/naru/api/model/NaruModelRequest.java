package net.thevpc.naru.api.model;

import java.util.Collections;
import java.util.List;

public class NaruModelRequest {
    private List<NaruMessage> messages;
    private List<NaruToolDefinition> tools;

    public NaruModelRequest(List<NaruMessage> messages, List<NaruToolDefinition> tools) {
        this.messages = messages;
        this.tools = tools;
    }
    public NaruModelRequest(List<NaruMessage> messages) {
        this.messages = messages;
        this.tools = Collections.emptyList();
    }

    public List<NaruMessage> getMessages() {
        return messages;
    }

    public List<NaruToolDefinition> getTools() {
        return tools;
    }
}
