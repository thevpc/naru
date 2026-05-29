package net.thevpc.naru.api.model;

import net.thevpc.nuts.elem.NElement;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class NaruModelRequest {
    private List<NaruMessage> messages;
    private List<NaruToolDefinition> tools;
    private Map<String, NElement> env;

    public NaruModelRequest(List<NaruMessage> messages, List<NaruToolDefinition> tools, Map<String, NElement> env) {
        this.messages = messages;
        this.tools = tools;
        this.env = env;
    }

    public NaruModelRequest(List<NaruMessage> messages, Map<String, NElement> env) {
        this.messages = messages;
        this.tools = Collections.emptyList();
        this.env = env;
    }

    public Map<String, NElement> env() {
        return env;
    }

    public List<NaruMessage> messages() {
        return messages;
    }

    public List<NaruToolDefinition> tools() {
        return tools;
    }
}
