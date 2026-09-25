package net.thevpc.naru.ext.models;

import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.ext.models.anthropic.NaruModelProtocolAnthropicCompat;
import net.thevpc.naru.ext.models.gemini.NaruModelProtocolGeminiNative;
import net.thevpc.naru.ext.models.openapi.NaruModelProtocolOpenAICompat;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry of wire-protocol types available to config-driven providers.
 *
 * <p>Built-in types: {@link #OPENAPI openapi} (OpenAI-compatible),
 * {@link #ANTHROPIC anthropic} (Anthropic Messages API) and {@link #GEMINI
 * gemini} (native Google {@code generateContent}). Additional types can be
 * registered programmatically via {@link #register(NaruModelProtocolType)} before
 * the custom provider is used; there is no need to add a provider Java class.
 */
public final class NaruModelProtocolTypes {

    public static final String OPENAPI = "openapi";
    public static final String ANTHROPIC = "anthropic";
    /**
     * Google's first-class {@code generateContent} API, as opposed to the
     * OpenAI-compatible route. Needed for {@code CachedContent} resource
     * caching, which the compatibility layer does not expose.
     */
    public static final String GEMINI = "gemini";

    private static final Map<String, NaruModelProtocolType> REGISTRY = new LinkedHashMap<>();

    static {
        register(new NaruModelProtocolType() {
            @Override
            public String name() {
                return OPENAPI;
            }

            @Override
            public NaruModelProtocol create(NaruModelProvider provider, NaruModelConfig model, String configPrefix,
                                            String chatPath, NaruModelCapabilities capabilities, String defaultBaseUrl) {
                return new NaruModelProtocolOpenAICompat(provider, model, configPrefix, chatPath, capabilities, defaultBaseUrl);
            }
        });
        register(new NaruModelProtocolType() {
            @Override
            public String name() {
                return GEMINI;
            }

            @Override
            public NaruModelProtocol create(NaruModelProvider provider, NaruModelConfig model, String configPrefix,
                                            String chatPath, NaruModelCapabilities capabilities, String defaultBaseUrl) {
                return new NaruModelProtocolGeminiNative(provider, model, configPrefix, chatPath, capabilities, defaultBaseUrl);
            }
        });
        register(new NaruModelProtocolType() {
            @Override
            public String name() {
                return ANTHROPIC;
            }

            @Override
            public NaruModelProtocol create(NaruModelProvider provider, NaruModelConfig model, String configPrefix,
                                            String chatPath, NaruModelCapabilities capabilities, String defaultBaseUrl) {
                return new NaruModelProtocolAnthropicCompat(provider, model, configPrefix, chatPath, capabilities, defaultBaseUrl);
            }
        });
    }

    private NaruModelProtocolTypes() {
    }

    public static void register(NaruModelProtocolType type) {
        if (type != null && !NBlankable.isBlank(type.name())) {
            REGISTRY.put(type.name().trim().toLowerCase(), type);
        }
    }

    public static NaruModelProtocolType defaultType() {
        return REGISTRY.get(OPENAPI);
    }

    /**
     * Resolves a protocol type by its id ({@code openapi}, {@code anthropic},
     * {@code gemini}, ...).
     * Case-insensitive; unknown ids return an empty optional.
     */
    public static NOptional<NaruModelProtocolType> of(String type) {
        if (NBlankable.isBlank(type)) {
            return NOptional.ofEmpty();
        }
        NaruModelProtocolType t = REGISTRY.get(type.trim().toLowerCase());
        if (t == null) {
            return NOptional.ofEmpty();
        }
        return NOptional.of(t);
    }

    public static Set<String> names() {
        return Collections.unmodifiableSet(REGISTRY.keySet());
    }
}