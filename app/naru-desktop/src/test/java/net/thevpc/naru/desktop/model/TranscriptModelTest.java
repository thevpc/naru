package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.desktop.host.TranscriptEvent;
import net.thevpc.naru.desktop.util.EDT;
import net.thevpc.nuts.Nuts;
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
        Nuts.require();
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

    // ── the three axes: audience, lifecycle, collapse ─────────────────────────

    @Test
    void progressNarrationIsSupersededByTheMessageItAnnounced() throws Exception {
        // "Model: gpt-4o…" is worth seeing while the turn runs, and pointless afterwards. Once the
        // real answer lands it drops from the conversation; the record still has it.
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(
                    new TranscriptEvent.Chunk("p1", NaruLogMode.PROGRESS, "Model: gpt-4o…", true, 3),
                    new TranscriptEvent.Closed("p1")));
            assertEquals(1, m.visibleSize(), "narration shows while it is the only thing there");
            m.apply(List.of(
                    new TranscriptEvent.Chunk("a1", NaruLogMode.MODEL_RESPONSE, "the answer", true, 3),
                    new TranscriptEvent.Closed("a1")));
            assertEquals(1, m.visibleSize(), "the answer supersedes the narration that announced it");
            assertEquals("a1", m.visible().get(0).id());
            assertEquals(2, m.totalBlocks(), "the narration is retained, only hidden in the conversation");
        });
    }

    @Test
    void progressNarrationSurvivesWhileTheTurnIsStillRunning() throws Exception {
        // Two narration lines in flight with no answer yet: the first must not hide behind the
        // second, or the row would flicker as the engine works.
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(
                    new TranscriptEvent.Chunk("p1", NaruLogMode.PROGRESS, "Model: gpt-4o…", true, 3),
                    new TranscriptEvent.Chunk("p1", NaruLogMode.PROGRESS, "Tool: file_read", true, 3),
                    new TranscriptEvent.Closed("p1")));
            assertEquals(1, m.visibleSize());
        });
    }

    @Test
    void aTurnFatalErrorLoggedAsProgressStays() throws Exception {
        // The engine logs "ERROR calling model" in the same mode as its narration. A failure the
        // user never saw is a failure they cannot act on, so severity overrides the transient rule.
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(
                    new TranscriptEvent.Chunk("p1", NaruLogMode.PROGRESS, "ERROR calling model", true, 3, false, true),
                    new TranscriptEvent.Closed("p1"),
                    new TranscriptEvent.Chunk("a1", NaruLogMode.MODEL_RESPONSE, "later answer", true, 3),
                    new TranscriptEvent.Closed("a1")));
            assertEquals(2, m.visibleSize(), "the error outlives the message that supersedes narration");
            assertEquals("p1", m.visible().get(0).id());
        });
    }

    @Test
    void aDirectiveResultIsLabeledACommandAndKeptInTheConversation() throws Exception {
        // A directive's output shares AGENT_RESPONSE with plain errors; the mark is what makes the
        // conversation read it as a reply to what was typed.
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(
                    new TranscriptEvent.Chunk("d1", NaruLogMode.AGENT_RESPONSE, "/home/user", true, 3, true),
                    new TranscriptEvent.Closed("d1")));
            TranscriptBlock b = m.visible().get(0);
            assertTrue(b.isDirectiveResult());
            assertEquals("Command", BlockStyle.forBlock(b).label());
            assertEquals(BlockStyle.Audience.CONVERSATION, BlockStyle.forBlock(b).audience());
        });
    }

    @Test
    void thinkingIsConversationContentAndCollapsedByDefault() throws Exception {
        // Thinking is long and the answer is the point, so it shows folded — not hidden, not open.
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(
                    new TranscriptEvent.Chunk("t1", NaruLogMode.MODEL_THINKING, "let me think…", true, 3),
                    new TranscriptEvent.Closed("t1")));
            assertEquals(1, m.visibleSize(), "thinking is shown without Activity");
            assertTrue(m.visible().get(0).isCollapsed(), "but folded, so it does not bury the answer");
        });
    }

    @Test
    void engineDetailIsHiddenUntilVerboseEvenThoughTheBlockIsDurable() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(
                    new TranscriptEvent.Chunk("s1", NaruLogMode.SCHEDULER, "fire", true, 3),
                    new TranscriptEvent.Closed("s1")));
            assertEquals(0, m.visibleSize());
            m.setVerbose(true);
            assertEquals(1, m.visibleSize());
            assertFalse(BlockStyle.of(NaruLogMode.SCHEDULER).lifecycle() == BlockStyle.Lifecycle.TRANSIENT,
                    "engine detail is a visibility question, not a retention one");
        });
    }
}
