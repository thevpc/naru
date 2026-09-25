package net.thevpc.naru.api.model;

import net.thevpc.nuts.elem.NElement;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * An outgoing request expressed as an ordered, explicitly-labelled decomposition
 * of its stable and volatile parts.
 *
 * <p>This is an <em>opt-in</em> view of a request. Callers that do not implement
 * it keep passing a plain {@link NaruModelRequest} and providers that do not
 * consume it keep seeing exactly what they saw before; nothing here is on the
 * mandatory path.
 *
 * <p>Its purpose is to let a caller say what is stable without knowing how any
 * provider expresses caching. {@link #toRequest(Map)} collapses the segments
 * back into the ordinary flat request, which is what a provider with no caching
 * support — or any provider whose mode is {@link NaruCachingMode#AUTOMATIC_PREFIX}
 * — actually consumes. Ordering is preserved exactly, because for automatic
 * prefix caching the ordering <em>is</em> the contract.
 *
 * <p>Segments are expected in stable-to-volatile order. NARU does not reorder
 * them: silently moving a caller's "latest user turn" ahead of their indexed
 * context would change the model's view of the conversation, and a caching
 * layer is the last place that should be reordering prompts.
 */
public interface NaruCacheableContext {

    List<NaruContextSegment> segments();

    default boolean isEmpty() {
        return segments() == null || segments().isEmpty();
    }

    /**
     * Collapse to the plain request shape. Cacheability is dropped here on
     * purpose: the flat form carries no cache information at all, so any
     * provider that does not implement caching translation gets byte-identical
     * behaviour to a request built without segmentation.
     */
    default NaruModelRequest toRequest(Map<String, NElement> env) {
        List<NaruMessage> messages = new ArrayList<>();
        List<NaruToolDefinition> tools = new ArrayList<>();
        for (NaruContextSegment segment : segments()) {
            if (segment.content() instanceof NaruSegmentContent.Messages m) {
                messages.addAll(m.messages());
            } else if (segment.content() instanceof NaruSegmentContent.Tools t) {
                tools.addAll(t.tools());
            }
        }
        return new NaruModelRequest(messages, tools, env);
    }

    static NaruCacheableContext of(List<NaruContextSegment> segments) {
        return new DefaultNaruCacheableContext(segments);
    }

    static Builder builder() {
        return new Builder();
    }

    /**
     * Convenience for the common case: a stable head and a volatile tail, with
     * the tool definitions folded in as their own cacheable segment.
     */
    static NaruCacheableContext of(List<NaruContextSegment> segments, List<NaruToolDefinition> tools) {
        List<NaruContextSegment> all = new ArrayList<>(segments);
        all.add(NaruContextSegment.cacheable("tool-defs", NaruSegmentContent.Tools.of(tools)));
        return new DefaultNaruCacheableContext(all);
    }

    final class DefaultNaruCacheableContext implements NaruCacheableContext {
        private final List<NaruContextSegment> segments;

        DefaultNaruCacheableContext(List<NaruContextSegment> segments) {
            this.segments = segments == null ? List.of() : List.copyOf(segments);
        }

        @Override
        public List<NaruContextSegment> segments() {
            return segments;
        }

        @Override
        public String toString() {
            return "NaruCacheableContext" + segments.stream()
                    .map(s -> (s.cacheable() ? "[c]" : "[v]") + s.id())
                    .toList();
        }
    }

    /**
     * Fluent assembler. The cacheable/volatile split is explicit at every call
     * site rather than inferred from a heuristic, because getting it wrong is
     * the one mistake that silently destroys cache effectiveness.
     */
    final class Builder {
        private final List<NaruContextSegment> segments = new ArrayList<>();

        public Builder cacheable(String id, List<NaruMessage> messages) {
            return cacheable(id, messages, null);
        }

        public Builder cacheable(String id, List<NaruMessage> messages, Duration minLifetime) {
            segments.add(new NaruContextSegment(id, NaruSegmentContent.Messages.of(messages), true, minLifetime));
            return this;
        }

        public Builder cacheableTools(String id, List<NaruToolDefinition> tools) {
            segments.add(NaruContextSegment.cacheable(id, NaruSegmentContent.Tools.of(tools)));
            return this;
        }

        public Builder cacheableMessages(String id, List<NaruMessage> messages) {
            return cacheable(id, messages, null);
        }

        public Builder volatileMessages(String id, List<NaruMessage> messages) {
            segments.add(NaruContextSegment.volatileSegment(id, NaruSegmentContent.Messages.of(messages)));
            return this;
        }

        public Builder volatileSingle(String id, NaruMessage message) {
            return volatileMessages(id, List.of(message));
        }

        public Builder add(NaruContextSegment segment) {
            segments.add(segment);
            return this;
        }

        public boolean isEmpty() {
            return segments.isEmpty();
        }

        public List<NaruContextSegment> build$() {
            return Collections.unmodifiableList(segments);
        }

        public NaruCacheableContext build() {
            return new DefaultNaruCacheableContext(segments);
        }
    }
}
