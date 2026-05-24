package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruSession;

import java.util.List;

public interface NaruModelProtocol {
    /**
     * Send a chat request with optional tool definitions.
     *
     * @return the model's response
     */
    NaruResponse chat(NaruModelRequest request, NaruSession session);

    NaruModelCapabilities getCapabilities();
}
