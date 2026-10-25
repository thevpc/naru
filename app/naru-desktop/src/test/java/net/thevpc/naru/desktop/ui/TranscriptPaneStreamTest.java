package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.desktop.host.InteractionHost;
import net.thevpc.naru.desktop.host.TranscriptEvent;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.util.EDT;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the chat column does while an answer streams. The host flushes at 30 Hz, so every one of
 * those ticks reaches the pane as a text-only change. A refresh touches only the rows that grew,
 * and reuses the card already on screen when it can — the model's answer is on screen as it is
 * written, not only once it is finished.
 * <p>
 * Unstarted {@link NaruSessionModel} on purpose, like {@link TaskMonitorTest}: the pane talks to
 * the model and the transcript, and a booted engine would only add 3 minutes to prove the same
 * thing.
 */
class TranscriptPaneStreamTest {

    @BeforeAll
    static void requireNuts() {
        Nuts.require();
    }

    @BeforeEach
    void strict() {
        EDT.setStrict(true);
    }

    @AfterEach
    void relaxed() {
        EDT.setStrict(false);
    }

    private NaruSessionModel model;
    private InteractionHost host;
    private TranscriptPane pane;

    @AfterEach
    void dispose() throws Exception {
        if (pane != null) {
            onEdt(() -> pane.dispose());
        }
    }

    private void buildPane() throws Exception {
        onEdt(() -> {
            host = new InteractionHost();
            model = new NaruSessionModel(() -> null,
                    NPath.ofTempFolder("naru-desktop-transcript-stream-" + System.nanoTime()), host);
            pane = new TranscriptPane(model);
        });
    }

    /** The card column: index 0 of the pane is the filter bar, index 1 the scroll pane. */
    private Container cards() {
        return (Container) ((JScrollPane) pane.getComponent(1)).getViewport().getView();
    }

    @Test
    void aStreamingAnswerIsShownAndStaysTheSameCardWhileItGrows() throws Exception {
        buildPane();
        onEdt(() -> {
            // one visible conversation card — a stored user message — then the first fragment
            // of a model answer, which this pane shows: the answer is the conversation
            model.onBatch(List.of(
                    new TranscriptEvent.Message("u1", 1, 1, NaruMessage.user("hello")),
                    new TranscriptEvent.Chunk("m1", NaruLogMode.MODEL_RESPONSE, "one", false, 1)), host);
            assertEquals(2, pane.rowCount(), "the streamed answer is a row of its own, by default");
            Component card = cards().getComponent(2);
            int components = cards().getComponentCount();

            // the answer keeps streaming: the row grows in place, nothing else is rebuilt
            for (int i = 0; i < 20; i++) {
                model.onBatch(List.of(
                        new TranscriptEvent.Chunk("m1", NaruLogMode.MODEL_RESPONSE, "tok" + i, false, 1)), host);
            }
            assertEquals(2, pane.rowCount());
            assertSame(card, cards().getComponent(2),
                    "the streamed answer card is reused rather than destroyed and re-created per tick");
            assertEquals(components, cards().getComponentCount());
            assertTrue(pane.cellTextAt(1).contains("tok19"),
                    "every fragment lands in the visible answer row");
        });
    }

    @Test
    void theStoredAnswerPromotesTheStreamedRowInsteadOfAddingASecondCard() throws Exception {
        buildPane();
        onEdt(() -> {
            model.onBatch(List.of(
                    new TranscriptEvent.Chunk("m1", NaruLogMode.MODEL_RESPONSE, "the answer", true, 1)), host);
            assertEquals(1, pane.rowCount());
            // the durable copy of the same answer arrives with a different id: the streamed row
            // becomes the model's card instead of the text appearing twice
            model.onBatch(List.of(
                    new TranscriptEvent.Message("stored-1-9", 1, 9, NaruMessage.assistant("the answer"))), host);
            assertEquals(1, pane.rowCount(), "one answer, one card");
            assertTrue(pane.cellTextAt(0).contains("the answer"));
        });
    }

    @Test
    void aVisibleStreamingBlockIsRefreshedInPlace() throws Exception {
        buildPane();
        onEdt(() -> {
            // thinking is not filtered out of the conversation, so its row must grow
            model.onBatch(List.of(
                    new TranscriptEvent.Chunk("t1", NaruLogMode.MODEL_THINKING, "think", false)), host);
            assertEquals(1, pane.rowCount());
            Component card = cards().getComponent(0);

            for (int i = 0; i < 20; i++) {
                model.onBatch(List.of(
                        new TranscriptEvent.Chunk("t1", NaruLogMode.MODEL_THINKING, "-tick", false)), host);
            }
            assertSame(card, cards().getComponent(0),
                    "the streaming row is updated in place, not re-created");
            assertTrue(pane.cellTextAt(0).contains("think-tick-tick"),
                    "every fragment lands in the row");
        });
    }

    @Test
    void theLogsTabKeepsTheStoredConversationInsteadOfGoingBlank() throws Exception {
        // A restarted session has only durable messages — the raw engine output the Logs tab
        // normally shows is not persisted. Hiding messages there left the tab empty after a
        // restart, which read as "the log was lost". It is the whole record, so it keeps them.
        onEdt(() -> {
            host = new InteractionHost();
            model = new NaruSessionModel(() -> null,
                    NPath.ofTempFolder("naru-desktop-transcript-logs"), host);
            pane = new TranscriptPane(model, false);
        });
        onEdt(() -> {
            model.onBatch(List.of(
                    new TranscriptEvent.Message("restored-1-1", 1, 1, NaruMessage.user("hi")),
                    new TranscriptEvent.Message("restored-1-2", 1, 2, NaruMessage.assistant("hello"))), host);
            assertEquals(2, pane.rowCount(), "the restored conversation is in the log");
            assertTrue(pane.cellTextAt(0).contains("hi"));
            assertTrue(pane.cellTextAt(1).contains("hello"));
        });
    }

    @Test
    void aLongThinkingItemStartsCollapsedAndTheUserCanSeeItAll() throws Exception {
        buildPane();
        onEdt(() -> {
            model.onBatch(List.of(new TranscriptEvent.Chunk("t1",
                    NaruLogMode.MODEL_THINKING, "line 0", false)), host);
            StringBuilder rest = new StringBuilder();
            for (int i = 1; i < 300; i++) {
                rest.append('\n').append("line ").append(i);
            }
            model.onBatch(List.of(new TranscriptEvent.Chunk("t1",
                    NaruLogMode.MODEL_THINKING, rest.toString(), false)), host);
            JPanel card = bodyCard();
            JEditorPane body = (JEditorPane) card.getClientProperty("transcript.body");
            assertTrue(body.getText().contains("line 5"), "the short preview is on screen");
            assertFalse(body.getText().contains("line 6"),
                    "thinking is collapsed by default: only the preview is measured");
            JButton more = (JButton) card.getClientProperty("transcript.more");
            assertTrue(more.isVisible());
            assertTrue(more.getText().contains("294 more lines"), more.getText());
            more.doClick();
            assertTrue(body.getText().contains("line 299"), "the control unfolds the whole answer");
            assertTrue(scrollsBody(card),
                    "a body this long is hosted in its own capped scrollport");
            more.doClick();
            assertFalse(body.getText().contains("line 6"), "and folds it back to the preview");
            assertFalse(scrollsBody(card));
        });
    }

    @Test
    void aBodyThatFitsIsNotFolded() throws Exception {
        buildPane();
        onEdt(() -> {
            model.onBatch(List.of(new TranscriptEvent.Chunk("t1",
                    NaruLogMode.MODEL_THINKING, "one line", false)), host);
            JButton more = (JButton) bodyCard().getClientProperty("transcript.more");
            assertTrue(!more.isVisible(), "a short body has nothing to unfold");
        });
    }

    @Test
    void anOutputItemFoldsAtFortyLinesAndScrollsOnlyWhenExpanded() throws Exception {
        buildPane();
        onEdt(() -> {
            model.onBatch(List.of(new TranscriptEvent.Chunk("s1",
                    NaruLogMode.SCRIPT, lines(60), false)), host);
            JPanel card = bodyCard();
            JEditorPane body = (JEditorPane) card.getClientProperty("transcript.body");
            assertTrue(body.getText().contains("line 39"), "the folded head is on screen");
            assertFalse(body.getText().contains("line 40"), "the tail is measured only on request");
            JButton more = (JButton) card.getClientProperty("transcript.more");
            assertTrue(more.getText().contains("20 more lines"), more.getText());
            assertFalse(scrollsBody(card), "a folded head is small enough to stay inline");

            more.doClick();
            assertTrue(body.getText().contains("line 59"));
            assertTrue(scrollsBody(card), "the full 60-line body gets its own scrollport");
            more.doClick();
            assertFalse(body.getText().contains("line 40"), "and folds back to the head");
        });
    }

    @Test
    void aWheelOverAnExpandedBodyContinuesInTheColumnAtItsEnd() throws Exception {
        buildPane();
        onEdt(() -> {
            model.onBatch(List.of(new TranscriptEvent.Chunk("s1",
                    NaruLogMode.SCRIPT, lines(60), false)), host);
            JPanel card = bodyCard();
            ((JButton) card.getClientProperty("transcript.more")).doClick();
            assertTrue(scrollsBody(card));
            JScrollPane inner = (JScrollPane) ((JPanel) card
                    .getClientProperty("transcript.bodyHost")).getComponent(0);
            JScrollBar bodyBar = inner.getVerticalScrollBar();
            // The scroller is laid out by a real window; here its bar has no extent yet, so give
            // it a stable model that puts the end of the body at the cursor.
            bodyBar.setModel(new javax.swing.DefaultBoundedRangeModel(90, 10, 0, 100));
            JScrollBar columnBar = ((JScrollPane) pane.getComponent(1)).getVerticalScrollBar();
            columnBar.setModel(new javax.swing.DefaultBoundedRangeModel(0, 10, 0, 100));
            // The pane is laid out by hand in these tests, so the scroll pane has not decided
            // yet that its bar is needed; in a real window it is visible by the time a wheel runs.
            columnBar.setVisible(true);
            int step = 3 * columnBar.getUnitIncrement();
            assertTrue(step > 0, "the column's wheel step must be positive");
            inner.dispatchEvent(new java.awt.event.MouseWheelEvent(
                    inner, java.awt.event.MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(),
                    0, 10, 10, 1, false, 0, 3, 1));

            assertEquals(step, columnBar.getValue(),
                    "a wheel at the body's end moves the transcript column instead of stopping");
        });
    }

    @Test
    void aCollapsedItemThatFitsWhenExpandedStillOffersViewLess() throws Exception {
        buildPane();
        onEdt(() -> {
            model.onBatch(List.of(new TranscriptEvent.Chunk("t1",
                    NaruLogMode.MODEL_THINKING, lines(30), false)), host);
            JPanel card = bodyCard();
            JEditorPane body = (JEditorPane) card.getClientProperty("transcript.body");
            JButton more = (JButton) card.getClientProperty("transcript.more");
            assertTrue(more.isVisible());
            assertTrue(more.getText().contains("24 more lines"), more.getText());

            more.doClick();
            assertTrue(body.getText().contains("line 29"), "all 30 lines fit once unfolded");
            assertTrue(more.isVisible(), "a 30-line item still needs a labelled way back");
            assertTrue(more.getText().contains("view less"), more.getText());

            more.doClick();
            assertFalse(body.getText().contains("line 6"), "and it returns to the short preview");
        });
    }

    private static String lines(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) text.append('\n');
            text.append("line ").append(i);
        }
        return text.toString();
    }

    /** The one card in the column that owns a foldable transcript body. */
    private JPanel bodyCard() {
        Container column = cards();
        for (int i = 0; i < column.getComponentCount(); i++) {
            if (column.getComponent(i) instanceof JPanel card
                    && card.getClientProperty("transcript.body") != null) {
                return card;
            }
        }
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < column.getComponentCount(); i++) {
            seen.append(column.getComponent(i).getClass().getSimpleName()).append(' ');
        }
        throw new AssertionError("no transcript card with a body was built; rows="
                + pane.rowCount() + " window=" + pane.visibleRange() + " components=" + seen);
    }

    /** True while the card hosts its body in the capped vertical scroller. */
    private static boolean scrollsBody(JPanel card) {
        if (card.getClientProperty("transcript.bodyHost") instanceof JPanel host
                && host.getComponentCount() == 1) {
            return host.getComponent(0) instanceof JScrollPane;
        }
        return false;
    }

    private void onEdt(ThrowingRunnable body) throws Exception {
        Exception[] failure = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                body.run();
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
