package net.thevpc.naru.ext.models;

import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelProvider;

/**
 * Factory that builds a {@link NaruModelProtocol} for a given wire-protocol type
 * (currently {@code openapi} and {@code anthropic}).
 *
 * <p>This is the extension hook for config-driven providers ({@code custom.*}
 * endpoints): registering a new {@code NaruModelProtocolType} makes it usable via
 * {@code custom.endpoints.<name>.type=<type>} with zero new Java provider classes.
 */
public interface NaruModelProtocolType {

    /**
     * Protocol type id, e.g. {@code openapi} or {@code anthropic}.
     */
    String name();

    /**
     * @param provider        the provider owning the protocol
     * @param model           the wire model config (real model name)
     * @param configPrefix    env prefix used to read settings (e.g. {@code custom.endpoints.foo})
     * @param chatPath        relative chat endpoint path (e.g. {@code v1/chat/completions})
     * @param capabilities    resolved capabilities
     * @param defaultBaseUrl  fallback base url when {@code <configPrefix>.url} is not set
     */
    NaruModelProtocol create(NaruModelProvider provider, NaruModelConfig model, String configPrefix,
                             String chatPath, NaruModelCapabilities capabilities, String defaultBaseUrl);
}