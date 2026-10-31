package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruReplayItem;
import net.thevpc.naru.api.agent.NaruToolCallEvent;
import net.thevpc.naru.api.agent.NaruTranscriptReplay;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruThinkingSegment;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.desktop.host.TranscriptEvent;
import net.thevpc.naru.desktop.util.EDT;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.text.NMsg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reloaded session says the same things the live turn said, in the same voices (items 2, 3, 4
 * and 5 of the restart report).
 *
 * <p>The bug was a host that had to guess: it attached every stored message to every restored block,
 * so reasoning, a tool's {@code ok} and an engine progress line all arrived as a message a
 * participant authored and were labelled as the agent speaking. These tests read the transcript back
 * the way the pane does — {@link TranscriptBlock#isChatMessage()} is exactly the predicate that
 * chooses a message card over a plain row — and assert that the guess is gone.
 */
class TranscriptRestoreFidelityTest {

    private static final long TASK = 7L;

    @BeforeAll
    static void nuts() {
        // NMsg.ofC formats through the Nuts runtime, and a bare JVM has no workspace to ask it in
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

    /** The conversation the report was made from: a question, a think call, and an answer. */
    private static List<NaruMessage> oneTurn() {
        NaruToolCall think = new NaruToolCall("call-1", "think",
                Map.of("thought", "##{p9:fix the grammar}##"));
        NaruMessage assistantTurn = NaruMessage.assistantWithToolCalls(
                        "Voici une version corrigée.", List.of(think))
                .setThinkingSegments(List.of(new NaruThinkingSegment(
                        0, "the user wants the email corrected", null, null)));
        List<NaruMessage> history = new ArrayList<>();
        history.add(NaruMessage.system("You are a helpful agent."));
        history.add(NaruMessage.user("peux tu corriger cet email"));
        history.add(NaruMessage.engine(NMsg.ofC("Goal: correct the email")
                .withIntent(NaruTranscriptReplay.THINKING)));
        history.add(NaruMessage.engine(NMsg.ofC("Tool: think").withLevel(Level.INFO)));
        history.add(assistantTurn);
        history.add(NaruMessage.tool("think", "call-1", "ok"));
        history.add(NaruMessage.assistant("17h30, pas 16h."));
        return history;
    }

    private static List<TranscriptEvent> restored(List<NaruMessage> history) {
        List<TranscriptEvent> events = new ArrayList<>();
        List<NaruReplayItem> items = NaruTranscriptReplay.replay(TASK, history);
        for (int i = 0; i < items.size(); i++) {
            events.add(new TranscriptEvent.Replay("restored-1-" + i, TASK, i, items.get(i)));
        }
        return events;
    }

    @Test
    void nothingIsForgottenAndNothingIsPlainAgentProse() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(restored(oneTurn()));
            List<TranscriptBlock> blocks = visible(m);

            assertTrue(blocks.stream().anyMatch(b -> b.mode() == NaruLogMode.SCRIPT
                            && "peux tu corriger cet email".equals(b.text().strip())),
                    "the user line survives the reload, on the user's own channel");
            assertTrue(blocks.stream().anyMatch(b -> b.mode() == NaruLogMode.MODEL_THINKING
                            && b.text().contains("the user wants the email corrected")),
                    "the model's reasoning reloads as reasoning");
            assertTrue(blocks.stream().anyMatch(b -> b.mode() == NaruLogMode.MODEL_THINKING
                            && b.text().contains("Goal: correct the email")),
                    "the think tool's scratchpad reloads as reasoning, not as the agent talking");
            assertEquals(1, m.toolCards().size(), "one call in, one card — the result folds into it");
            assertTrue(m.toolCards().get(0).text().contains("ok"),
                    "the card holds the result instead of a bare ok on its own line");
        });
    }

    @Test
    void onlyAnAuthoredTurnBecomesAMessageCard() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(restored(oneTurn()));
            for (TranscriptBlock b : visible(m)) {
                if (b.mode() == NaruLogMode.MODEL_THINKING) {
                    assertFalse(b.isChatMessage(),
                            "reasoning drawn as a message card is the NARU-everywhere bug");
                }
                if (b.mode() == NaruLogMode.PROGRESS) {
                    assertFalse(b.isChatMessage(), "narration is not something the agent said");
                }
            }
            assertTrue(m.blocks().stream()
                            .anyMatch(b -> b.isChatMessage()
                                    && "17h30, pas 16h.".equals(b.message().getFilteredContent())),
                    "an answer is a message card, and keeps its own message for the role label");
        });
    }

    @Test
    void noRowLeaksStyleMarkup() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(restored(List.of(NaruMessage.engine(
                    NMsg.ofC("Selected model : %s", "##{p9:ollama}####{separator:/}####{p6:nemotron}##")
                            .withLevel(Level.WARNING)))));
            String shown = m.blocks().get(0).text();
            assertFalse(shown.contains("#"), "the token markup is not content: " + shown);
            assertEquals("Selected model : ollama/nemotron", shown.strip(),
                    "the text the markup decorated survives, in the order it was written");
        });
    }

    @Test
    void aToolCallWatchedLiveIsNotDrawnTwiceByItsCheckpoint() throws Exception {
        NaruToolCall read = new NaruToolCall("call-9", "file_read", Map.of("path", "a.txt"));
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(new TranscriptEvent.ToolCall(
                    NaruToolCallEvent.started(TASK, read))));
            m.apply(List.of(new TranscriptEvent.ToolCall(
                    NaruToolCallEvent.result(TASK, read, "42 lines", 80))));
            assertEquals(1, m.toolCards().size(), "the live call opens one card and settles it");

            // the store now echoes the same turn: the assistant message that asked for the call,
            // then the message carrying its result
            m.apply(restored(List.of(NaruMessage.assistantWithToolCalls((String) null,
                    List.of(read)))));
            m.apply(restored(List.of(NaruMessage.tool("file_read", "call-9", "42 lines"))));
            assertEquals(1, m.toolCards().size(),
                    "the checkpoint of a call the user watched run adds no second card");
        });
    }

    @Test
    void aStreamedAnswerKeepsItsPlaceWhenTheStoreEchoesIt() throws Exception {
        String answer = "Voici une version corrigée.";
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(new TranscriptEvent.Opened("live", NaruLogMode.MODEL_RESPONSE, TASK, false),
                    new TranscriptEvent.Chunk("live", NaruLogMode.MODEL_RESPONSE, answer, true, TASK),
                    new TranscriptEvent.Closed("live")));
            int before = m.blocks().size();

            m.apply(restored(List.of(NaruMessage.assistant(answer))));
            assertEquals(before, m.blocks().size(),
                    "the durable copy settles onto the block already on screen, it does not repeat it");
            assertTrue(m.blocks().get(before - 1).isChatMessage(),
                    "and it still becomes a message card, with the role the pane labels by");
        });
    }

    @Test
    void anEmptyAssistantEchoDrawsNothing() throws Exception {
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(restored(List.of(NaruMessage.assistant(""))));
            assertTrue(m.blocks().isEmpty(), "no empty agent bubble for a turn with no content");
        });
    }

    @Test
    void aHostNoticeStillGetsARow() throws Exception {
        // NaruSessionModel raises a system message for a failure the host saw itself; the expander
        // classifies system content as not-conversation, and the host still has to show it.
        onEdt(() -> {
            TranscriptModel m = new TranscriptModel();
            m.apply(List.of(new TranscriptEvent.Message("note-1", TASK, 3,
                    NaruMessage.system("session failed to start: no such model"))));
            assertEquals(1, m.blocks().size(), "a message the host raised is shown, not classified away");
            assertTrue(m.blocks().get(0).isChatMessage());
            assertEquals("session failed to start: no such model",
                    m.blocks().get(0).text().strip());
        });
    }

    /** The rows the conversation shows: narration the next durable block supersedes is already gone. */
    private static List<TranscriptBlock> visible(TranscriptModel m) {
        return m.visible();
    }
}
