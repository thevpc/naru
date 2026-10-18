package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruToolCallEvent;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.desktop.host.TranscriptEvent;
import net.thevpc.naru.desktop.util.EDT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tool card, headless (plan §5, "the untruncated tool card"). The property that matters is the
 * one the console cannot give: a 5 000-character result arrives whole, in one place, and is
 * collapsible rather than cut off.
 */
class TranscriptToolCardTest {

    @BeforeEach
    void strict() {
        EDT.setStrict(true);
    }

    @AfterEach
    void relaxed() {
        EDT.setStrict(false);
    }

    private static void onEdt(ThrowingRunnable body) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                body.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static NaruToolCall call(String id, String name) {
        return new NaruToolCall(id, name, Map.of("path", "notes.txt", "limit", 40));
    }

    private static TranscriptEvent started(long task, String id, String name) {
        return new TranscriptEvent.ToolCall(NaruToolCallEvent.started(task, call(id, name)));
    }

    private static TranscriptEvent result(long task, String id, String name, String result, long ms) {
        return new TranscriptEvent.ToolCall(NaruToolCallEvent.result(task, call(id, name), result, ms));
    }

    @Test
    void aCardHoldsTheWholeResultNotThreeHundredCharacters() throws Exception {
        String big = "x".repeat(5_000);
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(started(12, "call-1", "file_read")));
            assertEquals(1, m.toolCards().size(), "STARTED opens the card where it happened");
            assertFalse(m.toolCards().get(0).isClosed(), "it reads as running until it settles");
            m.apply(List.of(result(12, "call-1", "file_read", big, 1_234)));
            TranscriptBlock card = m.toolCards().get(0);
            assertEquals(1, m.toolCards().size(), "the final event settles that card, it does not add one");
            assertTrue(card.isClosed());
            assertTrue(card.text().contains(big), "the whole 5 000 characters are there");
            assertTrue(card.text().contains("file_read"), "with the tool name");
            assertTrue(card.text().contains("call-1"), "and the call id, so two calls can be told apart");
            assertEquals(12, card.taskId(), "attributed to the task that ran it, not guessed");
            assertTrue(card.isCollapsed(), "and a card this long starts collapsed rather than 5 000 tall");
        });
    }

    @Test
    void aParkedCallAnnotatesItsOwnCard() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(started(3, "call-9", "ask_user")));
            m.apply(List.of(new TranscriptEvent.ToolCall(
                    NaruToolCallEvent.parked(3, call("call-9", "ask_user")))));
            assertEquals(1, m.toolCards().size(), "waiting for an answer is a state, not a second call");
            assertTrue(m.toolCards().get(0).text().contains("waiting for an answer"));
            m.apply(List.of(result(3, "call-9", "ask_user", "answered: red", 50)));
            assertEquals(1, m.toolCards().size());
            assertEquals(0, m.openToolCards());
            assertTrue(m.toolCards().get(0).isClosed());
        });
    }

    @Test
    void twoUnnamedCallsOfOneToolSettleInOrder() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            // some models send no call id: the engine gives us ordering, and that is what we key on
            m.apply(List.of(started(4, null, "bash"), started(4, null, "bash")));
            assertEquals(2, m.openToolCards());
            m.apply(List.of(result(4, null, "bash", "first", 10)));
            m.apply(List.of(result(4, null, "bash", "second", 10)));
            List<TranscriptBlock> cards = m.toolCards();
            assertEquals(2, cards.size());
            assertTrue(cards.get(0).text().contains("first"), "the earlier call settles first");
            assertTrue(cards.get(1).text().contains("second"));
            assertEquals(0, m.openToolCards());
        });
    }

    @Test
    void aFinalEventWithNoStartStillGetsACard() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(new TranscriptEvent.ToolCall(NaruToolCallEvent.failed(
                    2, call("", "no_such_tool"), "ERROR: unknown tool 'no_such_tool'", 0))));
            assertEquals(1, m.toolCards().size());
            TranscriptBlock card = m.toolCards().get(0);
            assertTrue(card.isClosed());
            assertTrue(card.text().contains("ERROR: unknown tool"), "a denial is shown in full");
        });
    }

    @Test
    void aCardSitsBetweenTheLinesThatSurroundIt() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(new TranscriptEvent.Chunk("b1", NaruLogMode.AGENT_RESPONSE,
                    "thinking out loud", true, 8)));
            m.apply(List.of(started(8, "c1", "file_read"),
                    result(8, "c1", "file_read", "the file", 20)));
            m.apply(List.of(new TranscriptEvent.Chunk("b2", NaruLogMode.AGENT_RESPONSE,
                    "and the answer", true, 8)));
            List<TranscriptBlock> all = m.blocks();
            assertEquals(3, all.size(), "answer, card, answer — in the order they happened");
            assertTrue(all.get(1).isToolCard());
            assertFalse(all.get(0).isToolCard());
        });
    }

    @Test
    void cardsAreVisibleWithoutVerboseAndProgressIsNot() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(new TranscriptEvent.Chunk("p1", NaruLogMode.PROGRESS,
                    "Result: ok", true, 1)));
            m.apply(List.of(started(1, "c1", "echo"), result(1, "c1", "echo", "ok", 5)));
            List<TranscriptBlock> visible = m.visible();
            assertEquals(1, visible.size(), "a tool call is not noise: it stays when verbose is off");
            assertTrue(visible.get(0).isToolCard());
            m.setVerbose(true);
            assertEquals(2, m.visible().size(), "and the abbreviated console line is still reachable");
        });
    }

    @Test
    void closingTheHostSettlesACardThatNeverReturned() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(started(1, "c1", "slow_tool")));
            assertEquals(1, m.openToolCards());
            m.apply(List.of(new TranscriptEvent.HostClosed("session closed")));
            assertEquals(0, m.openToolCards(), "no row may claim a tool is still running after the end");
            TranscriptBlock card = m.toolCards().get(0);
            assertTrue(card.isClosed());
            assertTrue(card.text().contains("no result"));
        });
    }
}
