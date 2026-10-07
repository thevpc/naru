package net.thevpc.naru.ext.models;

import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelProvider;

/**
 * Factory that builds a {@link NaruModelProtocol} for a given wire-protocol type
 * (currently {@code openapi} and {@code anthropic}).
 *
 * <p>This is the extension hook for wire providers: registering a new
 * {@code NaruModelProtocolType} makes it selectable via the registration's
 * {@code --protocol} flag ({@code /model add <id> --provider=wire
 * --protocol=<type>}) with zero new Java provider classes.
 */
public interface NaruModelProtocolType {

    /**
     * Protocol type id, e.g. {@code openapi} or {@code anthropic}.
     */
    String name();

    /**
     * @param provider        the provider owning the protocol
     * @param model           the wire model config (real model name)
     * @param configPrefix    instance id used to read the instance's config params
     * @param chatPath        relative chat endpoint path (e.g. {@code v1/chat/completions})
     * @param capabilities    resolved capabilities
     * @param defaultBaseUrl  fallback base url when the instance has no {@code url} param
     */
    NaruModelProtocol create(NaruModelProvider provider, NaruModelConfig model, String configPrefix,
                             String chatPath, NaruModelCapabilities capabilities, String defaultBaseUrl);
}