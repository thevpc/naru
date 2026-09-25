package net.thevpc.naru.api.model;

import net.thevpc.nuts.elem.NToElement;

import java.util.Set;

public interface NaruModelCapabilities extends NToElement {
    long contextLength();
    boolean isVision();

    boolean isTools();

    boolean isThinking();

    boolean isEmbedding();

    boolean isTextOnly();

    /**
     * How this model exposes prompt caching.
     *
     * <p>Callers should branch on this rather than on the provider name: the
     * provider name does not determine the mechanism (several providers sit
     * behind the same vendor but cache differently), and the mechanism is what
     * determines what a caching client must actually do.
     */
    default NaruCachingMode cachingMode() {
        return NaruCachingMode.NONE;
    }

    Set<String> keys();
}
