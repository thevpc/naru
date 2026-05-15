package net.thevpc.naru.api.model;

import java.util.List;

/**
 * Abstraction over any LLM backend (Ollama, OpenAI, Anthropic, …).
 * Implement this interface to add a new provider.
 */
public interface NaruModelProvider {

    /**
     * Send a chat request with optional tool definitions.
     *
     * @param model    provider-specific model name (e.g. "qwen2.5-coder:7b")
     * @param messages conversation history (must include the new user message)
     * @param tools    tool definitions available to the model (may be empty)
     * @return the model's response
     */
    NaruResponse chat(String model, List<NaruMessage> messages, List<NaruToolDefinition> tools);

    /**
     * Provider name for display purposes.
     */
    String getName();

    /**
     * Fetch the list of available models from this provider.
     * @return a list of model names
     */
    List<String> listModels();
}
