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

    Set<String> keys();
}
