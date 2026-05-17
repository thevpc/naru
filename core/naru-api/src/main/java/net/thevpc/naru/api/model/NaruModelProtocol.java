package net.thevpc.naru.api.model;

import java.util.List;

public interface NaruModelProtocol {
    /**
     * Send a chat request with optional tool definitions.
     *
     * @param messages conversation history (must include the new user message)
     * @param tools    tool definitions available to the model (may be empty)
     * @return the model's response
     */
    NaruResponse chat(List<NaruMessage> messages, List<NaruToolDefinition> tools);

    NaruModelCapabilities getCapabilities();
}
