package net.thevpc.naru.ext.models;

import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;

import java.util.*;

public class NaruModelCapabilitiesImpl implements NaruModelCapabilities {
    public static final NaruModelCapabilitiesImpl UNKNOWN = new NaruModelCapabilitiesImpl(false, false, false, false, -1, NaruCachingMode.NONE);
    private final boolean vision;
    private final boolean tools;
    private final boolean thinking;
    private final boolean embedding;
    private final long contextLength;
    private final NaruCachingMode cachingMode;
    private final boolean streaming;

    public NaruModelCapabilitiesImpl(NElement element) {
        NObjectElement o = element.asObject().get();
        vision = o.getBooleanValue("vision").orElse(false);
        tools = o.getBooleanValue("tools").orElse(false);
        thinking = o.getBooleanValue("thinking").orElse(false);
        embedding = o.getBooleanValue("embedding").orElse(false);
        contextLength = o.getLongValue("contextLength").orElse(-1L);
        cachingMode = o.getStringValue("cachingMode")
                .map(NaruCachingMode::valueOf)
                .orElse(NaruCachingMode.NONE);
        // absent means true: capabilities written before streaming existed are
        // models on the same wire protocols, which all stream
        streaming = o.getBooleanValue("streaming").orElse(true);
    }

    /**
     * Convenience for genuinely unknown capabilities, where no caching claim can
     * be justified. Providers that do support caching must use the six-argument
     * constructor so the mode is stated rather than inferred.
     */
    public NaruModelCapabilitiesImpl(boolean vision, boolean tools, boolean thinking, boolean embedding, long contextLength) {
        this(vision, tools, thinking, embedding, contextLength, NaruCachingMode.NONE);
    }

    public NaruModelCapabilitiesImpl(boolean vision, boolean tools, boolean thinking, boolean embedding, long contextLength, NaruCachingMode cachingMode) {
        this(vision, tools, thinking, embedding, contextLength, cachingMode, true);
    }

    public NaruModelCapabilitiesImpl(boolean vision, boolean tools, boolean thinking, boolean embedding, long contextLength, NaruCachingMode cachingMode, boolean streaming) {
        this.vision = vision;
        this.tools = tools;
        this.thinking = thinking;
        this.embedding = embedding;
        this.contextLength = contextLength;
        this.cachingMode = cachingMode == null ? NaruCachingMode.NONE : cachingMode;
        this.streaming = streaming;
    }

    @Override
    public boolean isStreaming() {
        return streaming;
    }

    public long contextLength() {
        return contextLength;
    }

    @Override
    public NaruCachingMode cachingMode() {
        return cachingMode;
    }

    @Override
    public boolean isVision() {
        return vision;
    }

    @Override
    public boolean isTools() {
        return tools;
    }

    @Override
    public boolean isThinking() {
        return thinking;
    }

    @Override
    public boolean isEmbedding() {
        return embedding;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        NaruModelCapabilitiesImpl that = (NaruModelCapabilitiesImpl) o;
        return vision == that.vision && tools == that.tools && thinking == that.thinking && embedding == that.embedding
                && contextLength == that.contextLength && cachingMode == that.cachingMode
                && streaming == that.streaming;
    }

    @Override
    public boolean isTextOnly() {
        return !vision && !tools && !thinking && !embedding;
    }

    @Override
    public Set<String> keys() {
        Set<String> k = new TreeSet<>();
        if (vision) {
            k.add("vision");
        }
        if (tools) {
            k.add("tools");
        }
        if (thinking) {
            k.add("thinking");
        }
        if (embedding) {
            k.add("embedding");
        }
        if (cachingMode != NaruCachingMode.NONE) {
            k.add("cache:" + cachingMode.name().toLowerCase(Locale.ROOT));
        }
        // streaming is deliberately absent: these keys describe what a model can
        // do with the content it is given, and streaming says nothing about that.
        // It is a property of the transport, true for nearly every model, so
        // listing it would add a permanent extra entry to every model's summary
        // without telling the reader anything.
        if (k.isEmpty()) {
            k.add("text-only");
        }
        return Collections.unmodifiableSet(k);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vision, tools, thinking, embedding, contextLength, cachingMode, streaming);
    }

    @Override
    public String toString() {
        return toElement().toString();
    }

    @Override
    public NElement toElement() {
        return NElement.ofTupleBuilder("Capabilities")
                .set("vision", vision)
                .set("tools", tools)
                .set("thinking", thinking)
                .set("embedding", embedding)
                .set("contextLength", contextLength)
                .set("cachingMode", cachingMode.name())
                .set("streaming", streaming)
                .build()
                ;
    }
}
