package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruTask;

public interface NaruModelProtocol {
    /**
     * Send a chat request with optional tool definitions.
     *
     * @return the model's response
     */
    NaruResponse chat(NaruModelRequest request, NaruTask task);

    NaruModelCapabilities getCapabilities();
}
