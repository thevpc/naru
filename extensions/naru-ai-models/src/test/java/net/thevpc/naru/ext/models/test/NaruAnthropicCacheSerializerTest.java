package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.ext.models.anthropic.NaruAnthropicRequestSerializer;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * Covers the cache_control translation in {@link NaruAnthropicRequestSerializer}.
 *
 * <p>The property that matters most is the last one: a request with no
 * segmentation must serialise to exactly the same body as before caching
 * existed. Otherwise every provider that opted in would silently change the
 * wire format for users who never asked for it.
 */
public class NaruAnthropicCacheSerializerTest {

    @BeforeAll
    public static void setUp() {
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            try {
                NWorkspace ws = Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static NaruModelConfig model() {
        return new NaruModelConfig("anthropic-compat", "claude-sonnet-4");
    }

    private static NaruToolDefinition tool(String name) {
        return new NaruToolDefinitionFunction(name, "does " + name,
                Collections.singletonList(NaruToolParameter.string("x", "arg", true).build()));
    }

    private static NaruCachePlanView allHit(NaruCacheableContext ctx) {
        List<NaruContextSegment> segs = ctx.segments();
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < segs.size(); i++) {
            all.add(i);
        }
        return new NaruCachePlanView() {
            @Override
            public List<NaruContextSegment> segments() {
                return segs;
            }

            @Override
            public NaruCachingMode mode() {
                return NaruCachingMode.EXPLICIT_INLINE;
            }

            @Override
            public boolean isCacheHit(int i) {
                return true;
            }

            @Override
            public List<Integer> cacheBreakpoints(int maxBreakpoints) {
                return all.subList(0, Math.min(all.size(), maxBreakpoints));
            }
        };
    }

    private static boolean hasCacheControl(NObjectElement o) {
        return o.get("cache_control").isPresent();
    }

    /** Counts markers and returns the wire blocks that carry them. */
    private static List<String> markersIn(NObjectElement body) {
        List<String> out = new ArrayList<>();
        if (body.get("tools").isPresent()) {
            for (NElement t : body.getArray("tools").get().children()) {
                if (hasCacheControl(t.asObject().get())) {
                    out.add("tools:" + t.asObject().get().getStringValue("name").get());
                }
            }
        }
        NElement system = body.get("system").orNull();
        if (system != null && system.isAnyArray()) {
            for (NElement b : system.asArray().get().children()) {
                if (hasCacheControl(b.asObject().get())) {
                    out.add("system:" + b.asObject().get().getStringValue("text").get());
                }
            }
        }
        NElement msgs = body.get("messages").orNull();
        if (msgs != null) {
            for (NElement m : msgs.asArray().get().children()) {
                NObjectElement mo = m.asObject().get();
                NElement c = mo.get("content").orNull();
                if (c != null && c.isAnyArray()) {
                    for (NElement b : c.asArray().get().children()) {
                        if (hasCacheControl(b.asObject().get())) {
                            out.add("message:" + b.asObject().get().getStringValue("text")
                                    .orElseGet(() -> b.asObject().get().getStringValue("type").get()));
                        }
                    }
                }
            }
        }
        return out;
    }

    @Test
    public void unsegmentedRequestIsByteIdenticalToNonCachingPath() {
        NaruAnthropicRequestSerializer serializer = new NaruAnthropicRequestSerializer();
        List<NaruMessage> messages = Arrays.asList(
                NaruMessage.system("Sys prompt"),
                NaruMessage.user("Hello"),
                NaruMessage.assistant("Hi"));
        NaruModelRequest plain = new NaruModelRequest(messages, Collections.emptyMap());
        NObjectElement a = serializer.serialize(plain, model(), null).asObject().get();

        // same request, but carrying a context that grants no breakpoints
        NaruCacheableContext ctx = NaruCacheableContext.builder()
                .cacheableMessages("sys", Collections.singletonList(NaruMessage.system("Sys prompt")))
                .volatileMessages("chat", Arrays.asList(NaruMessage.user("Hello"), NaruMessage.assistant("Hi")))
                .build();
        NaruModelRequest segmented = plain.withCacheableContext(ctx);
        NObjectElement b = serializer.serialize(segmented, model(), null, NaruCachePlanView.none())
                .asObject().get();

        Assertions.assertEquals(a, b, "caching must not alter the body when no breakpoint is granted");
        Assertions.assertEquals("Sys prompt", b.getStringValue("system").get(),
                "system must stay in compact string form when unmarked");
    }

    @Test
    public void markerOnLastMessageOfMarkedSegmentOnly() {
        NaruAnthropicRequestSerializer serializer = new NaruAnthropicRequestSerializer();
        NaruCacheableContext ctx = NaruCacheableContext.builder()
                .cacheableMessages("chat", Arrays.asList(NaruMessage.user("one"), NaruMessage.user("two")))
                .build();
        NaruModelRequest request = new NaruModelRequest(
                Arrays.asList(NaruMessage.user("one"), NaruMessage.user("two")), Collections.emptyMap())
                .withCacheableContext(ctx);

        NObjectElement body = serializer.serialize(request, model(), null, allHit(ctx)).asObject().get();
        List<String> markers = markersIn(body);

        Assertions.assertEquals(1, markers.size(), "expected exactly one marker, got " + markers);
        // the marker must be on "two": a marker on "one" would cache only half
        // of the segment the caller asked to cache
        Assertions.assertEquals("message:two", markers.get(0));
    }

    /**
     * The subtlety the naive implementation gets wrong: Anthropic evaluates
     * tools before system regardless of segment order, so the marker has to be
     * resolved against wire position.
     */
    @Test
    public void toolMarkerLandsOnLastToolEvenWhenSystemSegmentComesFirst() {
        NaruAnthropicRequestSerializer serializer = new NaruAnthropicRequestSerializer();
        NaruCacheableContext ctx = NaruCacheableContext.builder()
                .cacheableMessages("sys", Collections.singletonList(NaruMessage.system("Sys")))
                .cacheableTools("tools", Arrays.asList(tool("alpha"), tool("beta")))
                .build();
        NaruModelRequest request = ctx.toRequest(Collections.emptyMap());

        NObjectElement body = serializer.serialize(request, model(), null, allHit(ctx)).asObject().get();
        List<String> markers = markersIn(body);

        Assertions.assertTrue(markers.contains("tools:beta"),
                "expected marker on last tool, got " + markers);
        Assertions.assertTrue(markers.stream().anyMatch(m -> m.startsWith("system:")),
                "expected marker on system, got " + markers);
    }

    @Test
    public void markerForSystemForcesBlockForm() {
        NaruAnthropicRequestSerializer serializer = new NaruAnthropicRequestSerializer();
        NaruCacheableContext ctx = NaruCacheableContext.builder()
                .cacheableMessages("sys", Collections.singletonList(NaruMessage.system("Sys prompt")))
                .build();
        NaruModelRequest request = ctx.toRequest(Collections.emptyMap());

        NObjectElement body = serializer.serialize(request, model(), null, allHit(ctx)).asObject().get();

        Assertions.assertTrue(body.get("system").get().isAnyArray(),
                "cache_control cannot ride on a bare JSON string, so block form is mandatory");
        Assertions.assertEquals(Collections.singletonList("system:Sys prompt"), markersIn(body));
    }

    @Test
    public void respectsFourBreakpointLimit() {
        NaruAnthropicRequestSerializer serializer = new NaruAnthropicRequestSerializer();
        List<NaruMessage> msgs = new ArrayList<>();
        NaruCacheableContext.Builder b = NaruCacheableContext.builder();
        for (int i = 0; i < 9; i++) {
            NaruMessage m = NaruMessage.user("m" + i);
            msgs.add(m);
            b.cacheableMessages("s" + i, Collections.singletonList(m));
        }
        NaruCacheableContext ctx = b.build();
        NaruModelRequest request = new NaruModelRequest(msgs, Collections.emptyMap())
                .withCacheableContext(ctx);

        NObjectElement body = serializer.serialize(request, model(), null, allHit(ctx)).asObject().get();

        Assertions.assertEquals(4, markersIn(body).size(),
                "Anthropic rejects more than four breakpoints");
    }

    @Test
    public void toolResultSegmentGetsMarkerOnToolResultBlock() {
        NaruAnthropicRequestSerializer serializer = new NaruAnthropicRequestSerializer();
        NaruMessage toolResult = NaruMessage.tool("search", "call_1", "results here");
        NaruCacheableContext ctx = NaruCacheableContext.builder()
                .cacheableMessages("tools-out", Collections.singletonList(toolResult))
                .build();
        NaruModelRequest request = ctx.toRequest(Collections.emptyMap());

        NObjectElement body = serializer.serialize(request, model(), null, allHit(ctx)).asObject().get();
        NElement msgs = body.get("messages").get();
        NObjectElement first = msgs.asArray().get().children().get(0).asObject().get();
        NObjectElement block = first.getArray("content").get().children().get(0).asObject().get();

        Assertions.assertEquals("tool_result", block.getStringValue("type").get());
        Assertions.assertTrue(hasCacheControl(block), "tool_result block should carry the marker");
    }

    @Test
    public void nonCacheAwareModeEmitsNoMarkers() {
        NaruAnthropicRequestSerializer serializer = new NaruAnthropicRequestSerializer();
        NaruCacheableContext ctx = NaruCacheableContext.builder()
                .cacheableMessages("sys", Collections.singletonList(NaruMessage.system("Sys")))
                .build();
        NaruModelRequest request = ctx.toRequest(Collections.emptyMap());

        // AUTOMATIC_PREFIX on a provider whose serializer speaks explicit
        // inline: must degrade to no markers rather than guess
        NaruCachePlanView automatic = new NaruCachePlanView() {
            @Override
            public List<NaruContextSegment> segments() {
                return ctx.segments();
            }

            @Override
            public NaruCachingMode mode() {
                return NaruCachingMode.AUTOMATIC_PREFIX;
            }

            @Override
            public boolean isCacheHit(int i) {
                return true;
            }

            @Override
            public List<Integer> cacheBreakpoints(int maxBreakpoints) {
                return List.of(0);
            }
        };
        NObjectElement body = serializer.serialize(request, model(), null, automatic).asObject().get();
        Assertions.assertTrue(markersIn(body).isEmpty());
        Assertions.assertTrue(body.get("system").get().isAnyString(),
                "with no markers, system must keep its compact string form");
    }
}
