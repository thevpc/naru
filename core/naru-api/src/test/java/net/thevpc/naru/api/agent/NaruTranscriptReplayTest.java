package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruThinkingSegment;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.text.NMsg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a stored conversation turns into when a host redraws it.
 *
 * <p>The property under test is that a reload says the same things the live turn said, in the same
 * voices. A desktop that had to guess used the role as the whole answer and so redrew the model's
 * reasoning, the think tool's scratchpad, a tool's {@code ok} and every progress line as one
 * generic agent message — a reloaded session that read as if the agent had narrated itself.
 */
class NaruTranscriptReplayTest {

    private static final long TASK = 12L;

    /**
     * Reading a message's role, level or tool payload goes through the Nuts services, which a plain
     * unit test does not have on the thread until one is required.
     */
    @BeforeAll
    static void requireWorkspace() {
        Nuts.require();
    }

    private static NaruToolCall call(String id, String name) {
        return new NaruToolCall(id, name, Map.of("thought", "plan the fix"));
    }

    private static List<NaruReplayItem> replay(NaruMessage... messages) {
        return NaruTranscriptReplay.replay(TASK, List.of(messages));
    }

    @Test
    void oneAssistantTurnBecomesItsReasoningItsAnswerAndItsCall() {
        NaruMessage assistant = NaruMessage.assistantWithToolCalls("I will read the file first.",
                        List.of(call("c1", "file_read")))
                .setThinkingSegments(List.of(new NaruThinkingSegment(
                        0, "the user wants the log tail", null, null)));
        List<NaruReplayItem> items = replay(assistant,
                NaruMessage.tool("file_read", "c1", "120 lines"));

        assertEquals(3, items.size(), "reasoning, answer and card are three items, not one message");
        assertEquals(NaruLogMode.MODEL_THINKING, items.get(0).mode());
        assertEquals("the user wants the log tail", items.get(0).text().toString());
        assertNull(items.get(0).message(), "reasoning is a part of a turn, not a turn of its own");

        assertEquals(NaruLogMode.MODEL_RESPONSE, items.get(1).mode());
        assertSame(items.get(1).message(), assistant, "the answer is authored, so it keeps its message");

        assertTrue(items.get(2).isToolCall());
        assertEquals("c1", items.get(2).toolCall().callId());
    }

    @Test
    void aToolResultFoldsIntoItsCardInsteadOfBecomingProse() {
        NaruMessage assistant = NaruMessage.assistantWithToolCalls("", List.of(call("c1", "think")));
        List<NaruReplayItem> items = replay(assistant, NaruMessage.tool("think", "c1", "ok"));

        assertEquals(1, items.size(), "a call and its result draw one card, not a card and an 'ok'");
        NaruToolCallEvent card = items.get(0).toolCall();
        assertTrue(card.isFinal(), "a restored call is finished; it must not read as still running");
        assertEquals("ok", card.result());
        assertEquals(NaruToolCallEvent.UNKNOWN_DURATION, card.durationMillis(),
                "a stored result records no dispatch time, so the card must not invent one");
    }

    @Test
    void aCallNobodyAnsweredStillDrawsAClosedCard() {
        List<NaruReplayItem> items = replay(
                NaruMessage.assistantWithToolCalls((String) null, List.of(call("c1", "file_read"))));

        assertEquals(1, items.size());
        assertTrue(items.get(0).isToolCall());
        assertTrue(items.get(0).toolCall().isFinal(),
                "an interrupted session shows a tool that ran, not one that is forever running");
        assertNull(items.get(0).toolCall().result());
    }

    @Test
    void aResultWithNoRecordedCallIsStillAToolThatRan() {
        List<NaruReplayItem> items = replay(NaruMessage.tool("shell", "c9", "exit 0"));

        assertEquals(1, items.size(), "an orphan result is a card, never agent prose");
        assertEquals("shell", items.get(0).toolCall().toolName());
        assertEquals("exit 0", items.get(0).toolCall().result());
    }

    @Test
    void systemContextIsNotConversation() {
        assertTrue(replay(NaruMessage.system("You are a helpful agent")).isEmpty(),
                "a message the model was given is not something the user was told");
    }

    @Test
    void theThinkToolScratchpadIsReasoningNotAnAgentTurn() {
        List<NaruReplayItem> items = replay(NaruMessage.engine(
                NMsg.ofC("Goal: correct the email").withIntent(NaruTranscriptReplay.THINKING)));

        assertEquals(NaruLogMode.MODEL_THINKING, items.get(0).mode());
        assertNull(items.get(0).message(), "reasoning gets the reasoning channel, not a message card");
    }

    @Test
    void anEngineLineKeepsTheChannelItsLevelGaveItLive() {
        List<NaruReplayItem> narration = replay(NaruMessage.engine(NMsg.ofC("running tool").withLevel(
                Level.INFO)));
        assertEquals(NaruLogMode.PROGRESS, narration.get(0).mode());
        assertNull(narration.get(0).message(),
                "narration is superseded by the card it describes, so it stays transient");

        List<NaruReplayItem> failure = replay(NaruMessage.engine(
                NMsg.ofC("Error : model unavailable").withLevel(Level.WARNING)));
        assertEquals(NaruLogMode.AGENT_RESPONSE, failure.get(0).mode());
        assertNotNull(failure.get(0).message(), "a warning is something the user was told");
    }

    @Test
    void aUserLineIsDrawnAsTheMessageItIs() {
        NaruMessage user = NaruMessage.user("correct my email");
        List<NaruReplayItem> items = replay(user);

        assertEquals(1, items.size());
        assertEquals(NaruLogMode.SCRIPT, items.get(0).mode());
        assertSame(user, items.get(0).message(), "a user line keeps its own message");
    }

    @Test
    void legacyReasoningSurvivesAsOneSegment() {
        List<NaruReplayItem> items = replay(NaruMessage.assistant("answer").setThinking("older shape"));

        assertEquals(NaruLogMode.MODEL_THINKING, items.get(0).mode(),
                "a session written before segments existed still reloads with its reasoning apart");
        assertEquals("older shape", items.get(0).text().toString());
    }

    @Test
    void aTurnWithNothingToShowContributesNothing() {
        assertTrue(replay(NaruMessage.assistant("")).isEmpty(),
                "an empty assistant message is not an empty agent bubble");
    }

    @Test
    void modeOfAgreesWithTheReplayForASingleMessage() {
        assertEquals(NaruLogMode.MODEL_RESPONSE,
                NaruTranscriptReplay.modeOf(NaruMessage.assistant("the answer")));
        assertEquals(NaruLogMode.SCRIPT, NaruTranscriptReplay.modeOf(NaruMessage.user("the question")));
        assertTrue(NaruTranscriptReplay.replay(TASK, List.of()).isEmpty());
    }

    @Test
    void publishingRoutesEveryItemToTheChannelTheExpanderChose() {
        List<Line> lines = new ArrayList<>();
        List<NaruToolCallEvent> cards = new ArrayList<>();
        NaruMessage assistant = NaruMessage.assistantWithToolCalls("I will read the file.",
                        List.of(call("c1", "file_read")))
                .setThinkingSegments(List.of(new NaruThinkingSegment(
                        0, "the answer is in the tail", null, null)));

        NaruTranscriptReplay.publishTo(recording(lines, cards), TASK,
                List.of(assistant, NaruMessage.tool("file_read", "c1", "120 lines")));

        assertEquals(List.of(new Line(NaruLogMode.MODEL_THINKING, "the answer is in the tail", TASK),
                        new Line(NaruLogMode.MODEL_RESPONSE, "I will read the file.", TASK)), lines,
                "a terminal host gets the reasoning apart from the answer, exactly as live");
        assertEquals(1, cards.size(),
                "the call and its result are one closed card, never an 'ok' line in the prose");
        NaruToolCallEvent card = cards.get(0);
        assertEquals("file_read", card.toolName());
        assertEquals("c1", card.callId());
        assertTrue(card.isFinal());
        assertEquals("120 lines", card.result());
    }

    @Test
    void aHostThatTakesTheResultAloneStillSeesACardAndNoProse() {
        List<Line> lines = new ArrayList<>();
        List<NaruToolCallEvent> cards = new ArrayList<>();

        NaruTranscriptReplay.publishTo(recording(lines, cards), TASK,
                List.of(NaruMessage.tool("think", "c1", "ok")));

        assertTrue(lines.isEmpty(), "a lone result never becomes an agent turn saying 'ok'");
        assertEquals(1, cards.size());
    }

    @Test
    void publishingWithoutAHostIsANoOp() {
        NaruTranscriptReplay.publishTo(null, TASK, List.of(NaruMessage.user("hello")));
    }

    /** One {@code log(...)} call a host was asked to draw, with the task it was attributed to. */
    private record Line(NaruLogMode mode, String text, long taskId) {
    }

    /**
     * A session that only records what a host was asked to draw. {@code NaruSession} is far too
     * large to implement for a test, and both methods this path touches return void, so a proxy that
     * keeps those two is enough — a call to anything else fails here rather than quietly returning
     * null, which is how a replay that wrote back into history would be caught.
     */
    private static NaruSession recording(List<Line> lines, List<NaruToolCallEvent> cards) {
        return (NaruSession) java.lang.reflect.Proxy.newProxyInstance(
                NaruTranscriptReplayTest.class.getClassLoader(), new Class<?>[]{NaruSession.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "log" -> {
                            assertTrue(args.length == 3 && TASK == (Long) args[2],
                                    "publishing attributes the line to its task");
                            lines.add(new Line((NaruLogMode) args[0], String.valueOf(args[1]), TASK));
                        }
                        case "reportToolCall" -> cards.add((NaruToolCallEvent) args[0]);
                        default -> throw new AssertionError("replay touched " + method.getName());
                    }
                    return null;
                });
    }

    private static void assertSame(NaruMessage expected, NaruMessage actual, String why) {
        assertTrue(actual == expected, why);
    }
}
