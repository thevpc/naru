package net.thevpc.naru.api.model;

import net.thevpc.naru.api.registry.NaruToolParameter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One stable-to-volatile slice of the outgoing request context.
 *
 * <p>Callers build the request as an ordered list of segments, most stable
 * first: system prompt, tool definitions, project/indexed context, older
 * conversation turns, and finally the volatile tail (latest user turn, latest
 * tool results). Marking a segment {@link #cacheable()} is a claim that its
 * serialized bytes will not change on the next turn.
 *
 * <p>That claim is the whole basis of every provider's prefix cache, so NARU
 * verifies it rather than trusting it: the caching layer compares
 * {@link #canonical()} values through a hash chain, precisely because NARU
 * permits mid-history edits and a segment that keeps its own hash can still sit
 * at a different position in the prefix.
 *
 * @param id           stable logical identity, e.g. {@code system-prompt},
 *                     {@code tool-defs}, {@code code-context:moduleX}. Used for
 *                     diagnostics and for pairing against persisted state; cache
 *                     validity is decided by content, never by id.
 * @param content      what the segment carries
 * @param cacheable    whether this segment is a caching candidate
 * @param minLifetime  hint for how long the caller expects this segment to stay
 *                     stable. Advisory: it never causes NARU to cache a segment
 *                     the caller marked volatile, and it never rescues one whose
 *                     content actually changed.
 */
public record NaruContextSegment(
        String id,
        NaruSegmentContent content,
        boolean cacheable,
        Duration minLifetime
) {

    /**
     * Field separator. A non-printable so it cannot occur in agent text and
     * silently merge two fields into one.
     */
    private static final char UNIT = '\u0001';

    /**
     * Record separator, one level stronger than {@link #UNIT}.
     */
    private static final char RECORD = '\u0002';

    public NaruContextSegment {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("segment id is required");
        }
        if (content == null) {
            throw new IllegalArgumentException("segment content is required for '" + id + "'");
        }
    }

    /**
     * A cacheable segment with no particular stability horizon.
     */
    public static NaruContextSegment cacheable(String id, NaruSegmentContent content) {
        return new NaruContextSegment(id, content, true, null);
    }

    /**
     * A segment that must be re-sent as fresh content every turn.
     */
    public static NaruContextSegment volatileSegment(String id, NaruSegmentContent content) {
        return new NaruContextSegment(id, content, false, null);
    }

    public NaruContextSegment withCacheable(boolean newCacheable) {
        return new NaruContextSegment(id, content, newCacheable, minLifetime);
    }

    /**
     * A deterministic textual rendering of exactly the content that will be
     * serialized for this segment.
     *
     * <p>Determinism matters more than prettiness: the value feeds a hash chain
     * whose only job is to answer "did this exact prefix change?". Map keys are
     * sorted so two structurally identical segments cannot hash differently
     * because of {@code HashMap} iteration order. Fields the serializer would
     * not emit ({@code source}, {@code sourceName}) are excluded, because they
     * are bookkeeping and must not spuriously invalidate a cache.
     *
     * <p>{@link #id()} is excluded for the same reason. It is a label for
     * diagnostics and for pairing against persisted state, and it is not part
     * of what the provider receives. Hashing it would mean a caller who derives
     * ids dynamically — a counter, a timestamp, a path that moves — misses on
     * every single turn while the bytes on the wire are byte-identical. Excluding
     * it cannot cause a false hit, because the content that follows is exactly
     * what gets sent.
     */
    public String canonical() {
        StringBuilder sb = new StringBuilder();
        if (content instanceof NaruSegmentContent.Messages messages) {
            for (NaruMessage m : messages.messages()) {
                sb.append("msg").append(UNIT).append(m.getRole()).append(UNIT);
                append(sb, m.getContent());
                sb.append(UNIT).append(m.getToolName()).append(UNIT).append(m.getToolCallId());
                sb.append(UNIT).append(m.getThinking()).append(UNIT);
                if (m.getImages() != null) {
                    for (String img : m.getImages()) {
                        sb.append("img").append(UNIT).append(img.length()).append(':').append(img).append(UNIT);
                    }
                }
                if (m.getToolCalls() != null) {
                    for (NaruToolCall tc : m.getToolCalls()) {
                        sb.append("call").append(UNIT).append(tc.getId()).append(UNIT)
                                .append(tc.getName()).append(UNIT);
                        appendArgs(sb, tc.getArguments());
                        sb.append(UNIT);
                    }
                }
                sb.append(RECORD);
            }
        } else if (content instanceof NaruSegmentContent.Tools tools) {
            for (NaruToolDefinition t : tools.tools()) {
                sb.append("tool").append(UNIT).append(t.getName()).append(UNIT);
                append(sb, t.getDescription());
                sb.append(UNIT);
                if (t instanceof NaruToolDefinitionFunction f) {
                    for (NaruToolParameter p : f.getParams()) {
                        sb.append("param").append(UNIT).append(p.getName()).append(UNIT);
                        append(sb, p.getDescription());
                        sb.append(UNIT).append(p.getType()).append(UNIT).append(p.isRequired()).append(UNIT);
                        appendEnum(sb, p.getEnumValues());
                        sb.append(UNIT);
                    }
                }
                sb.append(RECORD);
            }
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("<null>");
            return;
        }
        // length-prefixed so "ab"+"c" and "a"+"bc" cannot collide
        sb.append(s.length()).append(':').append(s);
    }

    private static void appendEnum(StringBuilder sb, List<Object> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<String> sorted = new ArrayList<>();
        for (Object o : values) {
            sorted.add(String.valueOf(o));
        }
        sorted.sort(String::compareTo);
        sb.append("enum").append(UNIT).append(sorted).append(UNIT);
    }

    private static void appendArgs(StringBuilder sb, Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> e : new TreeMap<>(args).entrySet()) {
            sb.append(e.getKey()).append(UNIT).append(e.getValue()).append(UNIT);
        }
    }
}
