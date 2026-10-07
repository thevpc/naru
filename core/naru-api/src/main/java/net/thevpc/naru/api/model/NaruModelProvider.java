package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.spi.NComponent;
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
public interface NaruModelProvider extends NComponent {

    NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session);

    /**
     * Provider name for display purposes: the <b>instance id</b> this provider is
     * registered under ({@code gemini}, or {@code personal} for a registration of
     * the gemini type).
     */
    String name();

    /**
     * The implementation type behind this instance: {@code gemini}, {@code ollama},
     * ... Defaults to {@link #name()} — a built-in provider is its own type — while
     * an instance created by {@link #newInstance(String)} keeps reporting the type
     * it was cloned from. Type-scoped behaviour (listing filters, lifecycle
     * commands, probe caching) reads this instead of {@link #name()}.
     */
    default String type() {
        return name();
    }

    /**
     * A new instance of the same implementation addressed as {@code id}. All its
     * configuration keys are scoped by {@link #name()} ({@code <id>.apiKey},
     * {@code <id>.url}, {@code <id>.timeout}, ...), so N instances of one type
     * never collide.
     *
     * <p>Default rejects: a provider that does not extend
     * {@link AbstractNaruModelProvider} (and therefore has no reusable
     * construction) cannot be instantiated this way.
     */
    default NaruModelProvider newInstance(String id) {
        throw new NIllegalArgumentException(NMsg.ofC("provider '%s' cannot be instantiated multiple times", name()));
    }

    NOptional<String> apiKey(NaruSession session);
    /**
     * Fetch the list of available models from this provider.
     *
     * @return a list of model names
     */
    List<String> findModelIds(NaruSession session);

    /**
     * Whether this provider is currently reachable/usable.
     * Used to hide unavailable providers from model listings
     * (e.g. {@code /model list} should not display a local server that is down).
     *
     * <p>Default is {@code true} (assume available). Providers backed by a
     * remote/local HTTP server may override this to probe reachability.
     */
    default boolean isAvailable(NaruSession session) {
        return true;
    }

    void setParam(String name, String value);

    NOptional<String> getParam(String name);

    /**
     * The parameter's stored value, never masked — the config resolution path
     * (design doc §6: the wire layer reads {@code url}, {@code apiKey}, ... from
     * the instance's own params first) must see the real credential, while
     * listings go through {@link #getParam(String)} and may show a mask.
     */
    default NOptional<String> rawParam(String name) {
        return getParam(name);
    }

    Set<String> getParamNames();

    /**
     * The wire protocol ids this provider can honour for {@code --protocol=<wire>}
     * (design doc §8): {@code openapi}, {@code anthropic}, {@code gemini}, ...
     *
     * <p>An empty set means the provider's wire shape is fixed, so
     * {@code /model add --protocol=} must reject the flag instead of silently
     * ignoring it. The registry of ids is the one the provider's protocol
     * factory actually resolves against.
     */
    default Set<String> supportedProtocols() {
        return Set.of();
    }

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
