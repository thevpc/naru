package net.thevpc.naru.impl.engine.title;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.Nuts;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a titler is allowed to read. The bounds are the point: an unbounded excerpt prices a
 * title at the whole session, and a window that includes tool results titles the session after
 * a stack trace.
 */
public class NaruTitleInputTest {

    @BeforeAll
    public static void setUpWorkspace() {
        // NaruMessage content builds through NText, which needs a Nuts workspace in the context
        Nuts.require();
    }

    /** A task is a history in this test; everything else the titler reads is the session's. */
    private static NaruTask taskWith(List<NaruMessage> history) {
        return (NaruTask) Proxy.newProxyInstance(
                NaruTask.class.getClassLoader(),
                new Class<?>[]{NaruTask.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("history")) {
                        return history;
                    }
                    if (method.getName().equals("toString")) {
                        return "fake-task";
                    }
                    return null;
                });
    }

    private static List<NaruMessage> history(NaruMessage... messages) {
        List<NaruMessage> out = new ArrayList<>();
        for (NaruMessage m : messages) {
            out.add(m);
        }
        return out;
    }

    @Test
    public void firstMessageTakesTheFirstUserTurn() {
        NaruTask task = taskWith(history(
                NaruMessage.system("you are a helper"),
                NaruMessage.user("how do I resize a partition on lvm"),
                NaruMessage.assistant("with lvextend")));
        Assertions.assertEquals("how do I resize a partition on lvm",
                NaruTitleInput.firstMessage(task));
    }

    @Test
    public void firstMessageIsTruncated() {
        String longText = "x".repeat(NaruTitleInput.FIRST_MESSAGE_CAP + 500);
        String out = NaruTitleInput.firstMessage(taskWith(history(NaruMessage.user(longText))));
        Assertions.assertNotNull(out);
        Assertions.assertTrue(out.length() <= NaruTitleInput.FIRST_MESSAGE_CAP + 10,
                "expected a bounded first message but got " + out.length());
        Assertions.assertTrue(out.endsWith("[…]"), "a truncation marker should show the cut");
    }

    @Test
    public void aTaskWithNoUserTurnHasNoFirstMessage() {
        Assertions.assertNull(NaruTitleInput.firstMessage(taskWith(history(
                NaruMessage.system("only a system message")))));
    }

    @Test
    public void excerptKeepsOnlyUserAndAssistantText() {
        NaruTask task = taskWith(history(
                NaruMessage.system("system"),
                NaruMessage.user("first question"),
                NaruMessage.assistantWithToolCalls("running a command",
                        List.of(toolCall())),
                NaruMessage.tool("bash", "call-1", "the whole stack trace of the failure"),
                NaruMessage.engine("engine notice"),
                NaruMessage.directive("/session save"),
                NaruMessage.assistant("an answer")));
        List<String> turns = NaruTitleInput.excerpt(task);
        Assertions.assertEquals(List.of("user: first question", "assistant: an answer"), turns,
                "tool traffic, system lines, engine notices and directives are mechanics, not a topic");
    }

    private static NaruToolCall toolCall() {
        return new NaruToolCall("call-1", "bash", Map.of());
    }

    @Test
    public void excerptTakesTheFirstThreeAndTheLastThreeTurns() {
        List<NaruMessage> messages = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            messages.add(NaruMessage.user("turn " + i));
        }
        List<String> turns = NaruTitleInput.excerpt(taskWith(messages));
        Assertions.assertEquals(List.of("user: turn 1", "user: turn 2", "user: turn 3",
                "user: turn 8", "user: turn 9", "user: turn 10"), turns,
                "the opening states the intent and the end states what the session was doing");
    }

    @Test
    public void overlappingWindowsDoNotRepeatTheMiddle() {
        List<NaruMessage> messages = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            messages.add(NaruMessage.user("turn " + i));
        }
        List<String> turns = NaruTitleInput.excerpt(taskWith(messages));
        Assertions.assertEquals(6, turns.size());
        Assertions.assertEquals(turns.stream().distinct().count(), turns.size(),
                "head and tail must not both hand back the same turn");
    }

    @Test
    public void eachTurnIsCappedOnItsOwn() {
        NaruTask task = taskWith(history(NaruMessage.user("y".repeat(NaruTitleInput.EXCERPT_MESSAGE_CAP + 400))));
        String turn = NaruTitleInput.excerpt(task).get(0);
        Assertions.assertTrue(turn.length() <= NaruTitleInput.EXCERPT_MESSAGE_CAP
                        + "user: ".length() + 10,
                "one long paste must not crowd out the rest: " + turn.length());
    }

    @Test
    public void theWholeExcerptIsCapped() {
        List<NaruMessage> messages = new ArrayList<>();
        String big = "z".repeat(NaruTitleInput.EXCERPT_MESSAGE_CAP);
        for (int i = 0; i < 3; i++) {
            messages.add(NaruMessage.user(big));
            messages.add(NaruMessage.assistant(big));
        }
        List<String> turns = NaruTitleInput.excerpt(taskWith(messages));
        int total = turns.stream().mapToInt(String::length).sum();
        Assertions.assertTrue(total <= NaruTitleInput.EXCERPT_TOTAL_CAP,
                "excerpt cost " + total);
        Assertions.assertTrue(turns.size() < 6, "the cap should have dropped the tail turns");
    }

    @Test
    public void nothingSpeakableIsAnEmptyExcerpt() {
        Assertions.assertEquals(List.of(), NaruTitleInput.excerpt(taskWith(history(
                NaruMessage.system("only mechanics")))));
    }
}
