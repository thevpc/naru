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

import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the chat column does while an answer streams. The host flushes at 30 Hz, so every one of
 * those ticks used to reach the pane as a text-only change and end in a full reload of the card
 * column — for a block the chat view filters out (raw {@code MODEL_RESPONSE} output is hidden
 * behind the Activity toggle) that threw away and re-created every visible card for text it never
 * shows, which is the blinking. A refresh now touches only the rows that grew, and reuses the card
 * already on screen when it can.
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
                    NPath.ofTempFolder("naru-desktop-transcript-stream"), host);
            pane = new TranscriptPane(model);
        });
    }

    /** The card column: index 0 of the pane is the filter bar, index 1 the scroll pane. */
    private Container cards() {
        return (Container) ((JScrollPane) pane.getComponent(1)).getViewport().getView();
    }

    @Test
    void aHiddenStreamingBlockLeavesTheVisibleCardsAlone() throws Exception {
        buildPane();
        onEdt(() -> {
            // one visible conversation card — a stored user message — then the first fragment
            // of a model answer, which this pane hides: the block exists, no row shows it
            model.onBatch(List.of(
                    new TranscriptEvent.Message("u1", 1, 1, NaruMessage.user("hello")),
                    new TranscriptEvent.Chunk("m1", NaruLogMode.MODEL_RESPONSE, "one", false)), host);
            assertEquals(1, pane.rowCount());
            Container column = cards();
            Component card = column.getComponent(0);
            int components = column.getComponentCount();

            // the answer keeps streaming: nothing on screen changed, so nothing is rebuilt
            for (int i = 0; i < 20; i++) {
                model.onBatch(List.of(
                        new TranscriptEvent.Chunk("m1", NaruLogMode.MODEL_RESPONSE, "tok" + i, false)), host);
            }
            assertEquals(1, pane.rowCount(), "the hidden block adds no row");
            assertSame(card, cards().getComponent(0),
                    "the visible card is reused rather than destroyed and re-created per tick");
            assertEquals(components, cards().getComponentCount());
            assertTrue(pane.cellTextAt(0).contains("hello"));
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
