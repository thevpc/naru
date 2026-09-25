package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.model.NaruCacheableContext;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruContextSegment;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruSegmentContent;
import net.thevpc.naru.ext.models.cache.NaruCacheBaseline;
import net.thevpc.naru.ext.models.cache.NaruCacheKeyChain;
import net.thevpc.naru.ext.models.cache.NaruCachePlan;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The hash chain is what makes caching safe in a runtime where history is
 * mutable, so these tests are written around the three edit shapes rather than
 * around the API surface.
 */
public class NaruCacheKeyChainTest {

    private static NaruContextSegment sys(String id, String text) {
        return NaruContextSegment.cacheable(id, NaruSegmentContent.Messages.of(
                NaruMessage.system(text)));
    }

    private static NaruContextSegment turn(String id, String text) {
        return NaruContextSegment.volatileSegment(id, NaruSegmentContent.Messages.of(
                NaruMessage.user(text)));
    }

    private static List<String> keysOf(NaruContextSegment... segments) {
        return NaruCacheKeyChain.chain(List.of(segments));
    }

    // ── the chain itself ─────────────────────────────────────────────────────

    @Test
    @DisplayName("keys are deterministic and position-dependent")
    public void keysAreDeterministicAndChained() {
        NaruContextSegment a = sys("a", "system prompt");
        NaruContextSegment b = sys("b", "tool defs");
        NaruContextSegment c = sys("c", "indexed context");

        List<String> first = keysOf(a, b, c);
        List<String> second = keysOf(a, b, c);
        Assertions.assertEquals(first, second, "same content must hash identically");

        List<String> reordered = keysOf(a, c, b);
        Assertions.assertNotEquals(first, reordered, "swapping two segments must change the chain");
    }

    @Test
    @DisplayName("a segment's key depends on everything before it")
    public void keysCommitToPrefix() {
        List<String> base = keysOf(sys("a", "one"), sys("b", "two"));
        List<String> editedFirst = keysOf(sys("a", "ONE"), sys("b", "two"));
        List<String> appended = keysOf(sys("a", "one"), sys("b", "two"), sys("c", "three"));

        // editing segment 0 must shift every later key, even though segment 1 is
        // byte-identical. This is precisely the property independent per-segment
        // hashing lacks.
        Assertions.assertNotEquals(base.get(0), editedFirst.get(0));
        Assertions.assertNotEquals(base.get(1), editedFirst.get(1),
                "segment 1's key must change when segment 0 changes");
        // appending does not disturb existing keys, which is what makes the
        // append case a partial hit rather than a full miss
        Assertions.assertEquals(base.get(0), appended.get(0));
        Assertions.assertEquals(base.get(1), appended.get(1));
    }

    // ── the three mutation shapes ────────────────────────────────────────────

    @Test
    @DisplayName("append: only the new tail segment is a miss")
    public void appendDivergesAtTailOnly() {
        List<String> before = keysOf(sys("sys", "prompt"), sys("tools", "defs"), turn("u1", "hi"));
        List<String> after = keysOf(sys("sys", "prompt"), sys("tools", "defs"),
                turn("u1", "hi"), turn("u2", "next"));

        Assertions.assertEquals(3, NaruCacheKeyChain.validPrefixLength(before, after),
                "the three existing segments are unchanged and in place, so all three remain valid");
    }

    @Test
    @DisplayName("mid-history delete: everything after the deletion is a miss even though content is unchanged")
    public void deleteDivergesAtEditPointNotAtContentChange() {
        List<String> before = keysOf(sys("sys", "prompt"), turn("u1", "first"),
                turn("u2", "remove me"), turn("u3", "third"), turn("u4", "fourth"));

        List<String> after = keysOf(sys("sys", "prompt"), turn("u1", "first"),
                turn("u3", "third"), turn("u4", "fourth"));

        // This is the whole point of the chain. "third" and "fourth" have
        // identical content to before, so a per-segment hash comparison would
        // happily call them cache hits. They are not: the server's cached prefix
        // was "prompt first remove-me third fourth" and the bytes after the
        // deleted message no longer match it. Only the two segments that
        // genuinely precede the edit keep their identity.
        Assertions.assertEquals(2, NaruCacheKeyChain.validPrefixLength(before, after),
                "the two segments before the deletion keep their identity; everything after is a miss");
    }

    @Test
    @DisplayName("a per-segment hash would have been wrong for mid-history delete")
    public void independentHashesWouldMisjudgeDelete() {
        NaruContextSegment thirdBefore = turn("u3", "third");
        NaruContextSegment thirdAfter = turn("u3", "third");

        // identical content, so any content-only comparison calls this a hit
        Assertions.assertEquals(NaruCacheKeyChain.chain(List.of(thirdBefore)).get(0),
                NaruCacheKeyChain.chain(List.of(thirdAfter)).get(0));

        // ...but once each is placed after its real prefix, the keys differ,
        // because "third" no longer follows "first" — it follows "remove me"
        List<String> before = keysOf(turn("u1", "first"), turn("u2", "remove me"), thirdBefore);
        List<String> after = keysOf(turn("u1", "first"), thirdAfter);
        Assertions.assertNotEquals(before.get(2), after.get(1),
                "the chain is what catches the re-positioning that content hashing misses");
    }

    @Test
    @DisplayName("mid-history inject: divergence at the insertion")
    public void injectDivergesAtInsertionPoint() {
        List<String> before = keysOf(sys("sys", "prompt"), turn("u1", "first"), turn("u2", "second"));
        List<String> after = keysOf(sys("sys", "prompt"), turn("u1", "first"),
                turn("injected", "remember this"), turn("u2", "second"));

        Assertions.assertEquals(2, NaruCacheKeyChain.validPrefixLength(before, after),
                "the segments before the inserted message stay valid; the inserted message and "
                        + "everything after it are at a new position and must be resent");
    }

    @Test
    @DisplayName("tail delete keeps the surviving prefix valid")
    public void tailDeleteKeepsPrefix() {
        List<String> before = keysOf(sys("sys", "prompt"), sys("tools", "defs"), turn("u1", "hi"));
        List<String> after = keysOf(sys("sys", "prompt"), sys("tools", "defs"));
        Assertions.assertEquals(2, NaruCacheKeyChain.validPrefixLength(before, after));
    }

    @Test
    @DisplayName("no baseline means no trust")
    public void noBaselineMeansFullMiss() {
        List<String> current = keysOf(sys("sys", "prompt"), sys("tools", "defs"));
        Assertions.assertEquals(0, NaruCacheKeyChain.validPrefixLength(null, current));
        Assertions.assertEquals(0, NaruCacheKeyChain.validPrefixLength(List.of(), current));
    }

    // ── plan-level behaviour ─────────────────────────────────────────────────

    @Test
    @DisplayName("plan reports a full miss without a matching baseline")
    public void planWithoutBaselineIsFullMiss() {
        NaruCacheableContext ctx = NaruCacheableContext.builder()
                .cacheableMessages("sys", List.of(NaruMessage.system("prompt")))
                .cacheableMessages("tools", List.of(NaruMessage.system("defs")))
                .volatileSingle("u1", NaruMessage.user("hi"))
                .build();

        NaruCachePlan plan = NaruCachePlan.of(ctx, null, "anthropic", "claude", NaruCachingMode.EXPLICIT_INLINE);
        Assertions.assertEquals(0, plan.validPrefix());
        Assertions.assertEquals(List.of(), plan.cacheBreakpoints(4));
    }

    @Test
    @DisplayName("plan reuses the cached prefix and appends a fresh baseline")
    public void planReusesPrefixAndStoresNewBaseline() {
        List<NaruContextSegment> first = List.of(
                sys("sys", "prompt"), sys("tools", "defs"), turn("u1", "hi"));
        List<NaruContextSegment> second = new ArrayList<>(first);
        second.add(turn("u2", "next"));

        NaruCacheBaseline baseline = new NaruCacheBaseline("anthropic", "claude",
                NaruCachingMode.EXPLICIT_INLINE, NaruCacheKeyChain.chain(first), null, -1, 0);

        NaruCachePlan plan = NaruCachePlan.of(
                NaruCacheableContext.of(second), baseline, "anthropic", "claude", NaruCachingMode.EXPLICIT_INLINE);

        Assertions.assertEquals(3, plan.validPrefix());
        Assertions.assertEquals(List.of(0, 1), plan.cacheBreakpoints(4),
                "only the two cacheable segments inside the valid prefix get markers");
        Assertions.assertEquals(second.size(), plan.segmentKeys().size(),
                "the new key sequence covers every segment, including the volatile tail");
    }

    @Test
    @DisplayName("a baseline from another model is not reused")
    public void baselineFromAnotherModelIsIgnored() {
        List<NaruContextSegment> segments = List.of(sys("sys", "prompt"), sys("tools", "defs"));
        NaruCacheBaseline baseline = new NaruCacheBaseline("openrouter", "some-model",
                NaruCachingMode.AUTOMATIC_PREFIX, NaruCacheKeyChain.chain(segments), null, -1, 0);

        NaruCachePlan plan = NaruCachePlan.of(NaruCacheableContext.of(segments), baseline,
                "anthropic", "claude", NaruCachingMode.EXPLICIT_INLINE);
        Assertions.assertEquals(0, plan.validPrefix(),
                "cache identity from one provider says nothing about another's cache");
    }

    @Test
    @DisplayName("NONE mode never treats anything as a hit")
    public void noneModeIsAlwaysFullMiss() {
        List<NaruContextSegment> segments = List.of(sys("sys", "prompt"), sys("tools", "defs"));
        NaruCacheBaseline baseline = new NaruCacheBaseline("ollama", "qwen",
                NaruCachingMode.AUTOMATIC_PREFIX, NaruCacheKeyChain.chain(segments), null, -1, 0);

        NaruCachePlan plan = NaruCachePlan.of(NaruCacheableContext.of(segments), baseline,
                "ollama", "qwen", NaruCachingMode.NONE);
        Assertions.assertEquals(0, plan.validPrefix());
    }

    @Test
    @DisplayName("breakpoints collapse to the limit, always keeping the tail marker")
    public void breakpointsCollapseKeepingTail() {
        List<NaruContextSegment> many = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            many.add(sys("s" + i, "content " + i));
        }
        NaruCacheBaseline baseline = new NaruCacheBaseline("anthropic", "claude",
                NaruCachingMode.EXPLICIT_INLINE, NaruCacheKeyChain.chain(many), null, -1, 0);

        NaruCachePlan plan = NaruCachePlan.of(NaruCacheableContext.of(many), baseline,
                "anthropic", "claude", NaruCachingMode.EXPLICIT_INLINE);

        List<Integer> breakpoints = plan.cacheBreakpoints(4);
        Assertions.assertEquals(4, breakpoints.size(), "must respect the provider limit");
        Assertions.assertTrue(breakpoints.contains(8),
                "the last cacheable segment is the only marker covering the whole prefix");
        for (int i = 1; i < breakpoints.size(); i++) {
            Assertions.assertTrue(breakpoints.get(i) > breakpoints.get(i - 1),
                    "markers must be emitted in document order");
        }
    }

    @Test
    @DisplayName("resource prefix stops at the last breakpoint")
    public void resourcePrefixExcludesVolatileTail() {
        List<NaruContextSegment> segments = List.of(
                sys("sys", "prompt"), sys("tools", "defs"), turn("u1", "hi"));
        NaruCacheBaseline baseline = new NaruCacheBaseline("gemini", "pro",
                NaruCachingMode.EXPLICIT_RESOURCE, NaruCacheKeyChain.chain(segments), null, -1, 0);

        NaruCachePlan plan = NaruCachePlan.of(NaruCacheableContext.of(segments), baseline,
                "gemini", "pro", NaruCachingMode.EXPLICIT_RESOURCE);
        List<Integer> breakpoints = plan.cacheBreakpoints(4);

        Assertions.assertEquals(2, plan.resourcePrefix(breakpoints).size(),
                "a cached resource must not contain the volatile user turn");
    }
}
