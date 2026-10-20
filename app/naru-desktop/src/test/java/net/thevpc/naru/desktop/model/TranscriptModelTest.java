package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.desktop.host.TranscriptEvent;
import net.thevpc.naru.desktop.util.EDT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless transcript tests (plan §8, "coalescer unit tests: ordering preserved, no loss,
 * bounded memory"). Runs on the real EDT via {@code invokeAndWait} so {@link EDT} stays strict
 * and an off-thread mutation is a failure rather than a warning.
 */
class TranscriptModelTest {

    private final List<TranscriptEvent> empty = List.of();

    @BeforeEach
    void strict() {
        EDT.setStrict(true);
    }

    @AfterEach
    void relaxed() {
        EDT.setStrict(false);
    }

    private void onEdt(ThrowingRunnable body) throws Exception {
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

    @Test
    void chunksJoinIntoOneBlockAndCloseOnce() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(new TranscriptEvent.Chunk("b1", NaruLogMode.AGENT_RESPONSE, "Hel", false)));
            m.apply(List.of(new TranscriptEvent.Chunk("b1", NaruLogMode.AGENT_RESPONSE, "lo", true),
                    new TranscriptEvent.Closed("b1")));
            assertEquals(1, m.visibleSize());
            TranscriptBlock b = m.visibleAt(0);
            assertEquals("Hello\n", b.text());
            assertTrue(b.isClosed());
            assertNull(m.openBlock());
        });
    }

    @Test
    void aFinalFragmentDoesNotFreezeTheBlock() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(new TranscriptEvent.Chunk("b1", NaruLogMode.AGENT_RESPONSE, "one", true)));
            m.apply(List.of(new TranscriptEvent.Chunk("b1", NaruLogMode.AGENT_RESPONSE, "two", true),
                    new TranscriptEvent.Closed("b1")));
            assertEquals(1, m.visibleSize(), "both whole messages share the block");
            assertEquals("one\ntwo\n", m.visibleAt(0).text());
            assertTrue(m.visibleAt(0).isClosed());
        });
    }

    @Test
    void modeChangeStartsANewBlock() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(new TranscriptEvent.Chunk("b1", NaruLogMode.MODEL_THINKING, "thinking", true),
                    new TranscriptEvent.Closed("b1")));
            m.apply(List.of(new TranscriptEvent.Chunk("b2", NaruLogMode.AGENT_RESPONSE, "answer", true),
                    new TranscriptEvent.Closed("b2")));
            assertEquals(2, m.visibleSize());
            assertEquals(NaruLogMode.MODEL_THINKING, m.visibleAt(0).mode());
            assertEquals(NaruLogMode.AGENT_RESPONSE, m.visibleAt(1).mode());
        });
    }

    @Test
    void verboseOnlyModesAreFilteredFromTheViewButRetained() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(new TranscriptEvent.Chunk("b1", NaruLogMode.AGENT_RESPONSE, "answer", true),
                    new TranscriptEvent.Closed("b1")));
            m.apply(List.of(new TranscriptEvent.Chunk("b2", NaruLogMode.SCHEDULER, "tick", true),
                    new TranscriptEvent.Closed("b2")));
            assertEquals(1, m.visibleSize(), "scheduler output is noise until asked for");
            assertEquals(2, m.totalBlocks(), "...but never thrown away");
            m.setVerbose(true);
            assertEquals(2, m.visibleSize());
            m.setVerbose(false);
            assertEquals(1, m.visibleSize());
        });
    }

    @Test
    void trimmingKeepsMemoryBoundedAndCountsWhatItDropped() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.setMaxChars(1_024);
            for (int i = 0; i < 200; i++) {
                m.apply(List.of(new TranscriptEvent.Chunk("b" + i, NaruLogMode.SCRIPT,
                        "x".repeat(64), true), new TranscriptEvent.Closed("b" + i)));
            }
            assertTrue(m.retainedChars() <= 1_024, "retained text respects the budget");
            assertTrue(m.trimmedBlocks() > 0, "and the UI can say how much went away");
            assertNotNull(m.visibleAt(m.visibleSize() - 1));
        });
    }

    @Test
    void structuralAndTextChangesAreReportedSeparately() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            List<Boolean> kinds = new ArrayList<>();
            m.addListener(structural -> kinds.add(structural));
            m.apply(List.of(new TranscriptEvent.Chunk("b1", NaruLogMode.AGENT_RESPONSE, "a", false)));
            m.apply(List.of(new TranscriptEvent.Chunk("b1", NaruLogMode.AGENT_RESPONSE, "b", false)));
            m.apply(List.of(new TranscriptEvent.Chunk("b1", NaruLogMode.AGENT_RESPONSE, "c", true),
                    new TranscriptEvent.Closed("b1")));
            assertEquals(List.of(true, false, true), kinds,
                    "one structural notification per new block, then one for the batch that closes it");
        });
    }

    @Test
    void textNeverReachesTheModelAfterTheHostReportsLoss() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            // a Lagged event must not be swallowed: the view has to be able to offer a resync
            m.apply(List.of(new TranscriptEvent.Chunk("b1", NaruLogMode.SCRIPT, "text", true),
                    new TranscriptEvent.Lagged(7)));
            assertEquals(1, m.visibleSize());
            assertFalse(m.visibleAt(0).text().isEmpty());
        });
    }

    @Test
    void emptyBatchIsANoOp() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(empty);
            assertEquals(0, m.visibleSize());
        });
    }

    @Test
    void aTextOnlyBatchNamesTheBlocksThatGrew() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(new TranscriptEvent.Chunk("b1", NaruLogMode.MODEL_THINKING, "think", true)));
            assertEquals(List.of("b1"), ids(m.lastTextChanged()),
                    "the row a text-only batch grew is named, so the view can refresh just it");
            m.apply(List.of(new TranscriptEvent.Chunk("b1", NaruLogMode.MODEL_THINKING, "ing", false)));
            assertEquals(List.of("b1"), ids(m.lastTextChanged()));
            m.apply(List.of(new TranscriptEvent.Closed("b1")));
            assertTrue(m.lastTextChanged().isEmpty(),
                    "a batch that only moves rows names nothing; the view reloads those");
        });
    }

    @Test
    void hasOpenBlockSeesPastANoteThatOpensAndClosesInOneBatch() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(new TranscriptEvent.Chunk("b1", NaruLogMode.MODEL_RESPONSE, "to", false)));
            assertTrue(m.hasOpenBlock());
            // a host note is a block of its own, opened and closed inside one batch: the
            // "currently open" pointer ends up empty while the stream is still flowing
            m.apply(List.of(new TranscriptEvent.Opened("note", NaruLogMode.PROGRESS),
                    new TranscriptEvent.Chunk("note", NaruLogMode.PROGRESS, "stopping", true),
                    new TranscriptEvent.Closed("note")));
            assertNull(m.openBlock());
            assertTrue(m.hasOpenBlock(), "the streamed block is still open");
            m.apply(List.of(new TranscriptEvent.Chunk("b1", NaruLogMode.MODEL_RESPONSE, "ken", true),
                    new TranscriptEvent.Closed("b1")));
            assertFalse(m.hasOpenBlock(), "everything settled");
        });
    }

    @Test
    void theStoredAnswerBecomesTheStreamedBlockInsteadOfASecondOne() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(new TranscriptEvent.Chunk("raw-1", NaruLogMode.MODEL_RESPONSE, "the ", false, 7)));
            m.apply(List.of(new TranscriptEvent.Chunk("raw-1", NaruLogMode.MODEL_RESPONSE, "answer", true, 7)));
            TranscriptBlock streamed = m.blocks().get(0);
            assertFalse(streamed.isChatMessage(), "still the raw stream while it runs");

            // the durable copy arrives with its own id; the streamed row is promoted, not doubled
            m.apply(List.of(new TranscriptEvent.Message("stored-7", 7, 3,
                    net.thevpc.naru.api.model.NaruMessage.assistant("the answer"))));
            assertEquals(1, m.blocks().size(), "one block, not the raw plus the stored card");
            assertEquals("raw-1", m.blocks().get(0).id(), "the block already on screen is the one kept");
            assertTrue(m.blocks().get(0).isChatMessage(), "it is now the model's own card");
            assertEquals("the answer\n", m.blocks().get(0).text(), "the streamed text is not appended a second time");
        });
    }

    @Test
    void anUnstreamedStoredAnswerStillGetsItsOwnCard() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(new TranscriptEvent.Message("stored-7", 7, 3,
                    net.thevpc.naru.api.model.NaruMessage.assistant("no stream for this one"))));
            assertEquals(1, m.blocks().size());
            assertEquals("stored-7", m.blocks().get(0).id());
            assertTrue(m.blocks().get(0).isChatMessage());
        });
    }

    private static List<String> ids(List<TranscriptBlock> blocks) {
        List<String> out = new ArrayList<>();
        for (TranscriptBlock b : blocks) {
            out.add(b.id());
        }
        return out;
    }
}
