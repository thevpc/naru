package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
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
    String name();

    /**
     * Fetch the list of available models from this provider.
     *
     * @return a list of model names
     */
    List<String> findModelIds(NaruSession session);

    void setParam(String name, String value);

    NOptional<String> getParam(String name);

    Set<String> getParamNames();

    boolean isEnabled();

    void setEnabled(boolean enabled);

    default boolean isSupportedInstallModel() {
        return false;
    }

    default void installModel(NaruModelKey key, NaruSession session) {
        throw new NIllegalArgumentException(NMsg.ofC("not supported install for %s", NLiteral.of(name())));
    }

    default boolean isSupportedUninstallModel() {
        return false;
    }

    default void uninstallModel(NaruModelKey key, NaruSession session) {
        throw new NIllegalArgumentException(NMsg.ofC("not supported uninstall for %s", NLiteral.of(name())));
    }

    default boolean isSupportedUnloadModel() {
        return false;
    }

    default void unloadModel(NaruModelKey key, NaruSession session) {
        throw new NIllegalArgumentException(NMsg.ofC("not supported unload for %s", NLiteral.of(name())));
    }

    default boolean isSupportedPsModel() {
        return false;
    }

    default List<NaruModelPsResult> psModel(NaruSession session) {
        throw new NIllegalArgumentException(NMsg.ofC("not supported unload for %s", NLiteral.of(name())));
    }
}
