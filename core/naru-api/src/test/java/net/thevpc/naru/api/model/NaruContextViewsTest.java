package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two views of a task's conversation, and the boundary between them.
 *
 * <p>The properties tested here are the ones compaction depends on and cannot be checked by
 * reading: that history never loses an item, that the view keeps exactly what no summary
 * stands in for, and that a summary leaves as a role a provider will accept.
 */
class NaruContextViewsTest {

    private static NaruSummaryInfo info(String id, int covered) {
        return new NaruSummaryInfo(id, null, null, covered, 100, 10, "hash",
                NaruSummaryLevel.NORMAL, null, null, "test/model", Instant.now(),
                NaruSummaryTrigger.MANUAL, NaruSummaryState.ACTIVE, true);
    }

    private static NaruMessage summary(String text, String id) {
        return NaruMessage.summary(text, info(id, 2));
    }

    private static NaruMessage covered(String text, String summaryId) {
        return NaruMessage.user(text).setExcludedBy(summaryId);
    }

    // ── contextView ──────────────────────────────────────────────────────────

    @Test
    void anUncompactedHistoryIsItsOwnContextView() {
        List<NaruMessage> history = List.of(
                NaruMessage.user("a"), NaruMessage.assistant("b"), NaruMessage.user("c"));
        assertEquals(3, NaruContextViews.contextView(history).size());
    }

    @Test
    void excludedItemsLeaveTheViewButStayInHistory() {
        NaruMessage s = summary("earlier", "S1");
        List<NaruMessage> history = List.of(
                covered("old-1", "S1"), covered("old-2", "S1"), s,
                NaruMessage.user("recent"));
        List<NaruMessage> view = NaruContextViews.contextView(history);
        assertEquals(2, view.size(), "only the summary and the recent item are sent");
        assertEquals(4, history.size(), "history keeps every item");
    }

    @Test
    void anUndoneSummaryLeavesTheViewWithItsCoveredItems() {
        NaruMessage undone = summary("earlier", "S1")
                .setSummary(info("S1", 2).withState(NaruSummaryState.UNDONE));
        List<NaruMessage> history = List.of(
                NaruMessage.user("a"), NaruMessage.user("b"), undone, NaruMessage.user("c"));
        List<NaruMessage> view = NaruContextViews.contextView(history);
        assertEquals(3, view.size(), "a and b come back, the summary does not");
        assertTrue(view.stream().noneMatch(NaruMessage::isSummary));
    }

    @Test
    void orderIsPreserved() {
        List<NaruMessage> history = List.of(
                covered("x", "S1"), summary("s", "S1"), NaruMessage.user("y"));
        List<NaruMessage> view = NaruContextViews.contextView(history);
        assertEquals("s", view.get(0).getContent());
        assertEquals("y", view.get(1).getContent());
    }

    @Test
    void emptyAndNullHistoriesAreHandled() {
        assertTrue(NaruContextViews.contextView(null).isEmpty());
        assertTrue(NaruContextViews.contextView(List.of()).isEmpty());
    }

    // ── rendering ────────────────────────────────────────────────────────────

    @Test
    void aRenderedSummaryIsDelimitedAndSaysWhatItIs() {
        String text = NaruContextViews.renderSummary(summary("the gist", "S1"));
        assertNotNull(text);
        assertTrue(text.contains(NaruContextViews.SUMMARY_BEGIN));
        assertTrue(text.contains(NaruContextViews.SUMMARY_END));
        assertTrue(text.contains("the gist"));
        // The delimiters are the whole point: without them the model cannot tell a report
        // about earlier conversation from conversation that actually happened.
        assertTrue(text.indexOf(NaruContextViews.SUMMARY_BEGIN) < text.indexOf("the gist"));
    }

    @Test
    void anUndoneSummaryRendersAsNothing() {
        assertNull(NaruContextViews.renderSummary(
                summary("x", "S1").setSummary(info("S1", 1).withState(NaruSummaryState.UNDONE))));
    }

    @Test
    void aTruncatedSummarySaysSo() {
        NaruMessage truncated = summary("cut off", "S1").setSummary(
                new NaruSummaryInfo("S1", null, null, 2, 100, 10, "hash",
                        NaruSummaryLevel.NORMAL, null, null, "m", Instant.now(),
                        NaruSummaryTrigger.MANUAL, NaruSummaryState.ACTIVE, false));
        assertTrue(NaruContextViews.renderSummary(truncated).contains("truncated"));
    }

    // ── wireContextView ──────────────────────────────────────────────────────

    @Test
    void aSummaryGoesOutAsUserContentNotAsASummaryRole() {
        // The failure this prevents: a serializer mapping roles by name sends
        // {"role":"summary"}, which no provider accepts, and the rejection names nothing
        // about compaction.
        List<NaruMessage> wire = NaruContextViews.wireContextView(List.of(
                covered("old", "S1"), summary("the gist", "S1"), NaruMessage.user("recent")));
        for (NaruMessage m : wire) {
            assertFalse(m.getRole() == NaruRole.summary,
                    "no message in a provider request may carry the internal summary role");
        }
        NaruMessage summaryOnWire = wire.get(0);
        assertEquals(NaruRole.user, summaryOnWire.getRole());
        assertTrue(summaryOnWire.getContent().contains(NaruContextViews.SUMMARY_BEGIN));
        assertTrue(summaryOnWire.getContent().contains("the gist"));
    }

    @Test
    void aSummaryOnTheWireCarriesNoToolIdentity() {
        // An orphaned tool result -- a tool call id with no preceding call -- is rejected by
        // several providers outright.
        List<NaruMessage> wire = NaruContextViews.wireContextView(List.of(
                NaruMessage.tool("shell", "call-1", "out"),
                summary("the gist", "S1")));
        NaruMessage sent = wire.get(1);
        assertNull(sent.getToolCallId());
        assertNull(sent.getToolName());
    }

    @Test
    void aSummaryOnTheWireHasNoStructuredMetadataLeft() {
        List<NaruMessage> wire = NaruContextViews.wireContextView(List.of(summary("g", "S1")));
        assertNull(wire.get(0).getSummary());
        // The rendered text already states the item count and trigger, so keeping the
        // structured copy would put the same facts on the wire twice.
        assertNull(wire.get(0).getExcludedBy());
    }

    @Test
    void theWireViewLeavesOrdinaryMessagesAlone() {
        NaruMessage user = NaruMessage.user("hello");
        NaruMessage assistant = NaruMessage.assistant("hi");
        List<NaruMessage> wire = NaruContextViews.wireContextView(List.of(user, assistant));
        assertEquals(2, wire.size());
        assertEquals("hello", wire.get(0).getContent());
        assertEquals(NaruRole.assistant, wire.get(1).getRole());
    }

    @Test
    void theWireViewDoesNotMutateTheHistory() {
        NaruMessage original = summary("g", "S1");
        NaruContextViews.wireContextView(List.of(original));
        assertEquals(NaruRole.summary, original.getRole(),
                "rendering a view must not rewrite the stored item");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @Test
    void coveredItemsAreThoseFlaggedAsExcluded() {
        List<NaruMessage> history = List.of(
                NaruMessage.user("a"), covered("b", "S1"), covered("c", "S1"),
                summary("s", "S1"), NaruMessage.user("d"));
        List<NaruMessage> coveredItems = NaruContextViews.coveredItems(history);
        assertEquals(2, coveredItems.size());
        assertEquals("b", coveredItems.get(0).getContent());
    }

    @Test
    void activeSummariesAreListedInHistoryOrder() {
        List<NaruMessage> history = List.of(
                summary("one", "S1"), NaruMessage.user("x"), summary("two", "S2"));
        List<NaruMessage> active = NaruContextViews.activeSummaries(history);
        assertEquals(2, active.size());
        assertEquals("one", active.get(0).getContent());
        assertEquals("two", active.get(1).getContent());
    }
}