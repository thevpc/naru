package net.thevpc.naru.api.model;

import net.thevpc.nuts.util.NOptional;

import java.util.List;

/**
 * Abstraction over any LLM backend (Ollama, OpenAI, Anthropic, …).
 * Implement this interface to add a new provider.
 */
public interface NaruModelProvider {

    NOptional<NaruModelProtocol> getProtocol(String model);

    /**
     * Provider name for display purposes.
     */
    String getName();

    /**
     * Fetch the list of available models from this provider.
     * @return a list of model names
     */
    List<String> findModelIds();
}
