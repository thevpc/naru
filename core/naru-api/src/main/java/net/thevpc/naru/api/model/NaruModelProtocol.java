package net.thevpc.naru.api.model;

import net.thevpc.naru.api.task.NaruTask;

public interface NaruModelProtocol {
    /**
     * Send a chat request with optional tool definitions.
     *
     * @return the model's response
     */
    NaruResponse chat(NaruModelRequest request, NaruTask task);

    NaruModelCapabilities getCapabilities();
}
