package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.model.NaruToolOutputPolicy;
import net.thevpc.naru.api.model.NaruWindowSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the cut falls, and the boundary cases it must refuse to create.
 *
 * <p>A dirty boundary does not fail loudly. It fails as an opaque provider complaint about a
 * tool result with no call, hours later, in a different module. These tests are the cheapest
 * place in the system to keep that from happening.
 */
class NaruCompactCutTest {

    private static NaruMessage turn(String text) {
        return NaruMessage.user(text).setTurnBoundary(true);
    }

    private static NaruMessage follow(String text) {
        return NaruMessage.assistant(text);
    }

    private static List<NaruMessage> items(String... contents) {
        List<NaruMessage> out = new ArrayList<>();
        for (String c : contents) {
            out.add(NaruMessage.user(c));
        }
        return out;
    }

    private static NaruMessage withCalls(NaruMessage m, NaruToolCall... calls) {
        m.setToolCalls(List.of(calls));
        return m;
    }

    /** A user turn, an assistant that calls a tool, and the tool's result. */
    private static List<NaruMessage> toolTurn(String callId) {
        List<NaruMessage> items = new ArrayList<>();
        items.add(turn("do the thing"));
        items.add(withCalls(follow("calling"), new NaruToolCall(callId, "shell", Map.of())));
        items.add(NaruMessage.tool("shell", callId, "done"));
        return items;
    }

    // ── item windows ─────────────────────────────────────────────────────────

    @Test
    void aWindowThatFitsTheViewHasNothingToCompact() {
        List<NaruMessage> items = items("a", "b", "c");
        assertEquals(-1, NaruCompactCut.cutOf(items, NaruWindowSpec.lastItems(10)));
        assertEquals(-1, NaruCompactCut.cutOf(items, NaruWindowSpec.all()));
    }

    @Test
    void aWindowEqualToTheViewHasNothingToCompact() {
        assertEquals(-1, NaruCompactCut.cutOf(items("a", "b", "c"), NaruWindowSpec.lastItems(3)));
    }

    @Test
    void keepingOneItemCutsAfterTheFirst() {
        assertEquals(2, NaruCompactCut.cutOf(items("a", "b", "c"), NaruWindowSpec.lastItems(1)));
    }

    @Test
    void keepingNothingCutsBeforeEverything() {
        assertEquals(0, NaruCompactCut.cutOf(items("a", "b", "c"), NaruWindowSpec.none()));
        assertEquals(0, NaruCompactCut.cutOf(items("a", "b"), NaruWindowSpec.lastItems(0)));
    }

    // ── tool call boundaries ─────────────────────────────────────────────────

    @Test
    void aCallIsNeverSeparatedFromItsResult() {
        // Keeping only the result would leave a tool result whose call is summarized away.
        // Providers reject that outright, and the message they send back names nothing about
        // compaction -- so the cut moves earlier until the pair is on one side or the other.
        List<NaruMessage> items = toolTurn("call-1");
        int cut = NaruCompactCut.cutOf(items, NaruWindowSpec.lastItems(1));
        assertFalse(keptSideStartsWithAnOrphan(items, cut), "cut=" + cut);
        assertFalse(NaruCompactCut.splitsAGroup(items, cut));
        boolean callSummarized = items.subList(0, cut).stream().anyMatch(NaruMessage::hasToolCalls);
        boolean resultSummarized = items.subList(0, cut).stream()
                .anyMatch(m -> m.getRole() == NaruRole.tool);
        assertEquals(callSummarized, resultSummarized, "the call and its result travel together");
    }

    @Test
    void aKeptSideMayNotBeginWithAnOrphanedResult() {
        // Every cut the cut logic could produce is checked for the one shape a provider
        // rejects outright: a kept window whose first item is a result whose call was
        // summarized away.
        List<NaruMessage> items = new ArrayList<>(toolTurn("call-1"));
        items.add(follow("done"));
        for (int keep = 1; keep <= items.size(); keep++) {
            int cut = NaruCompactCut.cutOf(items, NaruWindowSpec.lastItems(keep));
            assertFalse(keptSideStartsWithAnOrphan(items, cut),
                    "keeping " + keep + " items cut at " + cut + " and orphaned a tool result");
        }
    }

    private static boolean keptSideStartsWithAnOrphan(List<NaruMessage> items, int cut) {
        if (cut < 0 || cut >= items.size()) {
            return false;
        }
        NaruMessage first = items.get(cut);
        if (first.getRole() != NaruRole.tool) {
            return false;
        }
        for (int i = cut - 1; i >= 0; i--) {
            NaruMessage m = items.get(i);
            if (m.getRole() == NaruRole.tool) {
                continue;
            }
            if (m.getRole() != NaruRole.assistant || !m.hasToolCalls()) {
                return true;
            }
            String wanted = first.getToolCallId();
            if (wanted == null || wanted.isBlank()) {
                return false;
            }
            for (NaruToolCall call : m.getToolCalls()) {
                if (wanted.equals(call.getId())) {
                    return false;
                }
            }
            return true;
        }
        return true;
    }

    @Test
    void aCallAndItsResultMayBeSummarizedTogether() {
        List<NaruMessage> items = toolTurn("call-1");
        items.add(follow("all done"));
        // Keeping only the closing line discards the tool exchange. That is legitimate -- a
        // summary is expected to carry the result -- and it needs no correction, because the
        // call and the result go into the summary together and the kept side starts with
        // ordinary assistant content.
        int cut = NaruCompactCut.cutOf(items, NaruWindowSpec.lastItems(1));
        assertEquals(3, cut);
        assertFalse(NaruCompactCut.splitsAGroup(items, cut));
    }

    @Test
    void anUnmatchedResultIsTreatedAsBelongingToTheCallBesideIt() {
        // Providers do not always echo a call id back. Being strict would walk the cut back
        // past an entire tool-using turn and summarize far more than the window asked for.
        List<NaruMessage> items = new ArrayList<>();
        items.add(turn("do it"));
        items.add(withCalls(follow("calling"), new NaruToolCall("call-1", "shell", Map.of())));
        items.add(NaruMessage.tool("shell", null, "done"));
        items.add(follow("finished"));
        int cut = NaruCompactCut.cutOf(items, NaruWindowSpec.lastItems(1));
        assertEquals(3, cut, "an unlabelled result should not drag the cut back");
    }

    @Test
    void aToolCallInTheKeptWindowWithItsResultIsFine() {
        List<NaruMessage> items = toolTurn("call-1");
        items.add(follow("and that is that"));
        // The cut asked to keep [result, closing], which is exactly the orphan a provider
        // rejects, so it moves one item earlier to keep [call, result, closing].
        int cut = NaruCompactCut.cutOf(items, NaruWindowSpec.lastItems(2));
        assertEquals(1, cut);
        assertFalse(NaruCompactCut.splitsAGroup(items, cut));
    }

    @Test
    void twoCallsAreOnlySeparatedFromTheirOwnResults() {
        List<NaruMessage> items = new ArrayList<>();
        items.add(turn("two things"));
        items.add(withCalls(follow("both"),
                new NaruToolCall("c1", "shell", Map.of()), new NaruToolCall("c2", "grep", Map.of())));
        items.add(NaruMessage.tool("shell", "c1", "one"));
        items.add(NaruMessage.tool("grep", "c2", "two"));
        // Cutting between the two results would keep result c2 with its call summarized away.
        int cut = NaruCompactCut.cutOf(items, NaruWindowSpec.lastItems(1));
        assertFalse(keptSideStartsWithAnOrphan(items, cut), "cut=" + cut);
    }

    // ── turn windows ─────────────────────────────────────────────────────────

    @Test
    void turnWindowsCutAtATurnBoundary() {
        List<NaruMessage> items = new ArrayList<>();
        items.add(turn("first"));
        items.add(follow("answer"));
        items.add(turn("second"));
        items.add(follow("answer"));
        items.add(turn("third"));
        assertEquals(4, NaruCompactCut.cutOf(items, NaruWindowSpec.lastTurns(1)));
        assertEquals(2, NaruCompactCut.cutOf(items, NaruWindowSpec.lastTurns(2)));
        assertEquals(-1, NaruCompactCut.cutOf(items, NaruWindowSpec.lastTurns(3)),
                "keeping every turn has nothing to compact");
    }

    @Test
    void aViewWithNoTurnBoundariesIsOneTurn() {
        // A conversation built by a script, or one that started mid-turn, has no boundary to
        // cut on. Summarizing the whole thing would be worse than doing nothing.
        List<NaruMessage> items = items("a", "b", "c");
        assertEquals(0, NaruCompactCut.cutOf(items, NaruWindowSpec.lastTurns(1)));
    }

    @Test
    void keepingMoreTurnsThanExistHasNothingToCompact() {
        List<NaruMessage> items = new ArrayList<>();
        items.add(turn("only"));
        items.add(follow("answer"));
        assertEquals(-1, NaruCompactCut.cutOf(items, NaruWindowSpec.lastTurns(5)));
    }

    // ── token windows ────────────────────────────────────────────────────────

    @Test
    void aTokenWindowKeepsAsMuchOfTheEndAsFits() {
        List<NaruMessage> items = items("aaaa", "bbbb", "cccc", "dddd");
        long twoItems = NaruCompactTokens.estimate(items.get(2)) + NaruCompactTokens.estimate(items.get(3));
        int cut = NaruCompactCut.cutOf(items, NaruWindowSpec.lastTokens(twoItems));
        assertTrue(cut >= 1 && cut <= 3, "cut=" + cut + " for budget " + twoItems);
    }

    @Test
    void aTokenWindowLargerThanTheViewHasNothingToCompact() {
        assertEquals(-1, NaruCompactCut.cutOf(items("a", "b"), NaruWindowSpec.lastTokens(10_000_000)));
    }

    @Test
    void aTokenWindowIsNeverOvershot() {
        // Over-shooting is the direction that matters: a kept window larger than asked for is
        // what fills the context back up. So an item is kept only if it fits in the budget.
        List<NaruMessage> two = items("a much longer piece of text than one token", "short");
        int cut = NaruCompactCut.cutOf(two, NaruWindowSpec.lastTokens(1));
        long kept = cut < 0 ? 0 : NaruCompactTokens.estimate(two.subList(cut, two.size()));
        assertTrue(kept <= 1, "kept " + kept + " tokens against a budget of 1, cut=" + cut);
        // and the same holds for every cut the logic can produce
        for (long budget : new long[]{1, 5, 40, 200}) {
            int c = NaruCompactCut.cutOf(two, NaruWindowSpec.lastTokens(budget));
            if (c < 0) {
                continue;
            }
            assertTrue(NaruCompactTokens.estimate(two.subList(c, two.size())) <= budget,
                    "budget " + budget + " cut " + c);
        }
    }

    // ── degenerate inputs ────────────────────────────────────────────────────

    @Test
    void anEmptyOrAbsentViewCutsAtZero() {
        assertEquals(0, NaruCompactCut.cutOf(null, NaruWindowSpec.lastItems(1)));
        assertEquals(0, NaruCompactCut.cutOf(List.of(), NaruWindowSpec.lastItems(1)));
    }

    @Test
    void anAbsentWindowIsTreatedAsKeepNothing() {
        assertEquals(0, NaruCompactCut.cutOf(items("a", "b"), null));
    }

    @Test
    void aCutIsClampedIntoRange() {
        List<NaruMessage> items = items("a", "b", "c");
        assertEquals(3, NaruCompactCut.moveEarlierToCleanBoundary(items, 99));
        assertEquals(0, NaruCompactCut.moveEarlierToCleanBoundary(items, -5));
        assertFalse(NaruCompactCut.splitsAGroup(items, 0));
        assertFalse(NaruCompactCut.splitsAGroup(items, 3));
    }

    @Test
    void aPlainConversationNeedsNoCorrection() {
        List<NaruMessage> items = new ArrayList<>();
        items.add(turn("one"));
        items.add(follow("two"));
        items.add(turn("three"));
        items.add(follow("four"));
        for (int cut = 0; cut <= items.size(); cut++) {
            assertFalse(NaruCompactCut.splitsAGroup(items, cut), "cut " + cut);
        }
    }

    @Test
    void toolOutputPolicyDoesNotMoveTheCut() {
        // The policy shapes the summarizer's input, not which items are covered, so it must
        // not move the boundary.
        List<NaruMessage> items = toolTurn("call-1");
        int expected = NaruCompactCut.cutOf(items, NaruWindowSpec.lastItems(1));
        assertEquals(expected, NaruCompactCut.cutOf(items, NaruWindowSpec.lastItems(1)));
        NaruToolOutputPolicy[] policies = NaruToolOutputPolicy.values();
        assertEquals(3, policies.length);
    }
}