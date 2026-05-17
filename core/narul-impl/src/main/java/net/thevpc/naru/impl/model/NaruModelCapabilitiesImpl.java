package net.thevpc.naru.impl.model;

import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;

import java.util.*;

public class NaruModelCapabilitiesImpl implements NaruModelCapabilities {
    public static final NaruModelCapabilitiesImpl UNKNOWN = new NaruModelCapabilitiesImpl(false, false, false, false,-1);
    private final boolean vision;
    private final boolean tools;
    private final boolean thinking;
    private final boolean embedding;
    private final long contextLength;

    public NaruModelCapabilitiesImpl(NElement element) {
        NObjectElement o = element.asObject().get();
        vision = o.getBooleanValue("vision").orElse(false);
        tools = o.getBooleanValue("tools").orElse(false);
        thinking = o.getBooleanValue("thinking").orElse(false);
        embedding = o.getBooleanValue("embedding").orElse(false);
        contextLength = o.getLongValue("contextLength").orElse(-1L);
    }

    public NaruModelCapabilitiesImpl(boolean vision, boolean tools, boolean thinking, boolean embedding,long contextLength) {
        this.vision = vision;
        this.tools = tools;
        this.thinking = thinking;
        this.embedding = embedding;
        this.contextLength = contextLength;
    }

    public long contextLength() {
        return contextLength;
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
        return vision == that.vision && tools == that.tools && thinking == that.thinking && embedding == that.embedding && contextLength == that.contextLength;
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
        if (k.isEmpty()) {
            k.add("text-only");
        }
        return Collections.unmodifiableSet(k);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vision, tools, thinking, embedding,contextLength);
    }

    @Override
    public String toString() {
        return toElement().toString();
    }

    @Override
    public NElement toElement() {
        return NElement.ofObjectBuilder("Capabilities")
                .set("vision", vision)
                .set("tools", tools)
                .set("thinking", thinking)
                .set("embedding", embedding)
                .set("contextLength", contextLength)
                .build()
                ;
    }
}
