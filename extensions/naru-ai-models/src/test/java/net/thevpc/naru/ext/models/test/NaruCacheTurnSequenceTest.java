package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.ext.models.cache.NaruCacheBaseline;
import net.thevpc.naru.ext.models.cache.NaruCacheKeyChain;
import net.thevpc.naru.ext.models.cache.NaruCachePlan;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The behaviour the whole feature rests on: across a real conversation, a
 * growing history must keep hitting the cached prefix instead of invalidating
 * it.
 *
 * <p>These simulate the segmentation {@code NaruTaskImpl} produces, where a
 * turn segment is closed only when the next turn begins, and the turn currently
 * in flight is volatile. The property being asserted is not "the chain works" —
 * {@link NaruCacheKeyChainTest} covers that — but "the shape the task builds
 * actually yields hits as the conversation grows".
 */
public class NaruCacheTurnSequenceTest {

    private static NaruContextSegment seg(String id, boolean cacheable, NaruMessage... msgs) {
        List<NaruMessage> l = Arrays.asList(msgs);
        return cacheable
                ? NaruContextSegment.cacheable(id, NaruSegmentContent.Messages.of(l))
                : NaruContextSegment.volatileSegment(id, NaruSegmentContent.Messages.of(l));
    }

    private static NaruContextSegment tools(List<NaruToolDefinition> t) {
        return NaruContextSegment.cacheable("tool-defs", NaruSegmentContent.Tools.of(t));
    }

    private static final List<NaruToolDefinition> TOOLS =
            List.of(new NaruToolDefinitionFunction("search", "search the web",
                    List.of(NaruToolParameter.string("q", "query", true).build())));

    private static final NaruMessage SYS = NaruMessage.system("You are NARU.");
    private static final NaruMessage U1 = NaruMessage.user("first");
    private static final NaruMessage A1 = NaruMessage.assistant("answer one");
    private static final NaruMessage U2 = NaruMessage.user("second");
    private static final NaruMessage A2 = NaruMessage.assistant("answer two");
    private static final NaruMessage U3 = NaruMessage.user("third");
    private static final NaruMessage A3 = NaruMessage.assistant("answer three");

    /** First model call of turn 1: only the user message exists so far. */
    private static List<NaruContextSegment> turn1FirstCall() {
        return List.of(tools(TOOLS), seg("system", true, SYS), seg("turn-0", false, U1));
    }

    /** Turn 1 complete. */
    private static List<NaruContextSegment> turn1Done() {
        return List.of(tools(TOOLS), seg("system", true, SYS), seg("turn-0", true, U1, A1));
    }

    /** First model call of turn 2, before the answer exists. */
    private static List<NaruContextSegment> turn2FirstCall() {
        return List.of(tools(TOOLS), seg("system", true, SYS), seg("turn-0", true, U1, A1),
                seg("turn-1", false, U2));
    }

    /** Turn 2 complete. */
    private static List<NaruContextSegment> turn2Done() {
        return List.of(tools(TOOLS), seg("system", true, SYS), seg("turn-0", true, U1, A1),
                seg("turn-1", true, U2, A2));
    }

    private static NaruCacheBaseline baselineOf(List<NaruContextSegment> segs) {
        return new NaruCacheBaseline("p", "m", NaruCachingMode.EXPLICIT_INLINE,
                NaruCacheKeyChain.chain(segs), null, -1, 0L);
    }

    private static int validPrefixOf(List<NaruContextSegment> current, List<NaruContextSegment> previous) {
        return NaruCachePlan.of(NaruCacheableContext.of(current), baselineOf(previous),
                "p", "m", NaruCachingMode.EXPLICIT_INLINE).validPrefix();
    }

    @Test
    public void systemPromptIsHitOnTheVeryFirstTurn() {
        // nothing cached yet: full miss, which is correct
        Assertions.assertEquals(0, validPrefixOf(turn1FirstCall(), List.of()));
    }

    @Test
    public void closedTurnStaysValidAsTheConversationGrows() {
        // turn 1 done -> turn 2's first call: tools, system and turn-0 all survive
        Assertions.assertEquals(3, validPrefixOf(turn2FirstCall(), turn1Done()));
    }

    @Test
    public void historyGrowingMidTurnDoesNotInvalidateEarlierTurns() {
        // a turn with several model calls (tool use) keeps hitting the prefix
        // even though the in-flight segment changes on every call
        List<NaruContextSegment> midTool = List.of(
                tools(TOOLS), seg("system", true, SYS), seg("turn-0", true, U1, A1),
                seg("turn-1", false, U2, NaruMessage.tool("search", "c1", "results")));
        Assertions.assertEquals(3, validPrefixOf(midTool, turn1Done()));
    }

    @Test
    public void everyCompletedTurnAccumulates() {
        Assertions.assertEquals(3, validPrefixOf(turn2FirstCall(), turn1Done()));

        List<NaruContextSegment> turn3FirstCall = List.of(
                tools(TOOLS), seg("system", true, SYS), seg("turn-0", true, U1, A1),
                seg("turn-1", true, U2, A2), seg("turn-2", false, U3));
        Assertions.assertEquals(4, validPrefixOf(turn3FirstCall, turn2Done()));

        List<NaruContextSegment> turn3Done = List.of(
                tools(TOOLS), seg("system", true, SYS), seg("turn-0", true, U1, A1),
                seg("turn-1", true, U2, A2), seg("turn-2", true, U3, A3));
        // turn-2 gained the assistant reply, so it is a new segment: tools,
        // system, turn-0 and turn-1 are all still valid
        Assertions.assertEquals(4, validPrefixOf(turn3Done, turn3FirstCall),
                "every completed turn should still be a hit");
    }

    /**
     * Tools lead the wire prefix, so a change to the tool set must invalidate
     * the conversation cache rather than being quietly served from a stale
     * prefix. This is the case that gets the caching wrong if segments are
     * laid out in the order the request is built rather than the order the
     * provider evaluates.
     */
    @Test
    public void toolSetChangeInvalidatesEverythingAfterIt() {
        List<NaruToolDefinition> moreTools = new ArrayList<>(TOOLS);
        moreTools.add(new NaruToolDefinitionFunction("write", "write a file", List.of()));

        List<NaruContextSegment> changed = List.of(
                tools(moreTools), seg("system", true, SYS), seg("turn-0", true, U1, A1),
                seg("turn-1", true, U2, A2));

        Assertions.assertEquals(0, validPrefixOf(changed, turn2Done()),
                "tools lead the wire prefix, so changing them invalidates everything after them");
    }

    @Test
    public void editingAnEarlierTurnInvalidatesFromThatPointOn() {
        // the user rewords an old question: turn-0 changes, so nothing after it
        // may be treated as cached
        NaruMessage U1_EDITED = NaruMessage.user("first, but with more detail");
        List<NaruContextSegment> edited = List.of(
                tools(TOOLS), seg("system", true, SYS), seg("turn-0", true, U1_EDITED, A1),
                seg("turn-1", true, U2, A2));

        // tools and system survive; turn-0 changed, so turn-1 cannot be claimed
        Assertions.assertEquals(2, validPrefixOf(edited, turn2Done()));
    }

    @Test
    public void noBreakpointIsEverPlacedOnTheVolatileTail() {
        NaruCachePlan plan = NaruCachePlan.of(
                NaruCacheableContext.of(turn2FirstCall()), baselineOf(turn1Done()),
                "p", "m", NaruCachingMode.EXPLICIT_INLINE);
        List<Integer> breakpoints = plan.cacheBreakpoints(4);
        int volatileIndex = 3; // turn-1, still being appended to (0=tools, 1=system, 2=turn-0)
        Assertions.assertFalse(breakpoints.contains(volatileIndex),
                "caching content that is about to change wastes the whole breakpoint");
    }
}
