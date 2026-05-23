package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NOptional;

import java.util.List;
import java.util.Set;

/**
 * Abstraction over any LLM backend (Ollama, OpenAI, Anthropic, …).
 * Implement this interface to add a new provider.
 */
public interface NaruModelProvider {

    NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session);

    /**
     * Provider name for display purposes.
     */
    String getName();

    /**
     * Fetch the list of available models from this provider.
     *
     * @return a list of model names
     */
    List<String> findModelIds(NaruSession session);

    void setParam(String name, String value);
    NOptional<String> getParam(String name);
    Set<String> getParamNames();

    boolean isEnabled() ;

    void setEnabled(boolean enabled) ;
}
