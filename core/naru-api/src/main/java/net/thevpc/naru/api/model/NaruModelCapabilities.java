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
     * Whether this model can be called with incremental delivery.
     *
     * <p>Defaults to true because streaming is a property of the wire protocol
     * rather than of the model, and every protocol NARU speaks supports it. A
     * provider only needs to say false for a specific endpoint or model that
     * does not, which is why this is a default rather than a required field.
     *
     * <p>Callers must treat a false here as a reason to use the batch path, not
     * as an error: the batch and streaming paths are required to produce the
     * same result, so falling back is always correct and never lossy.
     */
    default boolean isStreaming() {
        return true;
    }

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

    /**
     * Whether this model is free to use (no per-token charge): a model served
     * by a local engine, an OpenRouter {@code :free} variant, or one whose
     * pricing is zero. Reported here so a catalog entry carries the answer
     * instead of every caller re-deriving it from the provider name or id.
     *
     * <p>Defaults to {@code false}: a provider that does not say otherwise is
     * assumed to be paid, which is the safe default for billing.
     */
    default boolean isFree() {
        return false;
    }

    /**
     * Whether this model runs on a local endpoint. Mirrors
     * {@link NaruModelProvider#isLocal(NaruSession)} at the model level so a
     * catalog entry can be filtered without re-asking the provider. Defaults to
     * {@code false}.
     */
    default boolean isLocal() {
        return false;
    }

    Set<String> keys();
}
