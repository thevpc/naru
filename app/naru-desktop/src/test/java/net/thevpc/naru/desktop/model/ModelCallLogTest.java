package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.desktop.util.EDT;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.time.NDuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Requests tab's store, headless. Two promises matter here: the newest call is always the first
 * row, and the cap evicts whole calls — it never keeps a prefix of a request and calls that the
 * history. Token accounting keeps the engine's other promise: {@code -1} is "the provider did not
 * say", not zero.
 */
class ModelCallLogTest {

    @BeforeEach
    void strict() {
        Nuts.require();
        EDT.setStrict(true);
    }

    @AfterEach
    void relaxed() {
        EDT.setStrict(false);
    }

    private static NaruModelRequest request(String... prompts) {
        List<NaruMessage> messages = new ArrayList<>();
        messages.add(NaruMessage.system("you are a helpful agent"));
        for (String p : prompts) {
            messages.add(NaruMessage.user(p));
        }
        return new NaruModelRequest(messages, List.of(), Map.of());
    }

    private static NaruResponse answer(String text, int promptTokens, int cacheRead) {
        NaruResponse r = new NaruResponse(NaruMessage.engine(text), true);
        r.setPromptTokens(promptTokens);
        r.setEvalTokens(20);
        r.setCacheReadTokens(cacheRead);
        r.setStopReason("end_turn");
        return r;
    }

    /** Recorded off the EDT, the way the engine delivers it. */
    private static void record(ModelCallLog log, NaruModelRequest req, NaruResponse res, long millis) {
        log.onModelCall(null, new NaruModelKey("anthropic", "claude-sonnet"), req, res, NDuration.ofMillis(millis));
    }

    /** The mutation is posted to the EDT; flush it before reading the log back. */
    private static void drain() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    @Test
    void newestFirstAndContentKeptWhole() throws Exception {
        ModelCallLog log = new ModelCallLog();
        record(log, request("first question"), answer("one", 100, 0), 500);
        record(log, request("second question"), answer("two", 150, 50), 900);
        drain();

        List<ModelCallLog.Call> calls = log.calls();
        assertEquals(2, calls.size());
        assertEquals("second question", calls.get(0).lastUserPreview());
        assertEquals("two", calls.get(0).answerText());
        assertEquals("one", calls.get(1).answerText());
        assertEquals(2, calls.get(0).messageCount());
        assertEquals("anthropic / claude-sonnet", calls.get(0).modelLabel());
        assertTrue(calls.get(0).filterKey().contains("second question"));
    }

    @Test
    void capEvictsWholeCallsAndCountsThem() throws Exception {
        ModelCallLog log = new ModelCallLog();
        for (int i = 0; i < ModelCallLog.MAX_CALLS + 5; i++) {
            record(log, request("q" + i), answer("a" + i, 10, -1), 10);
        }
        drain();

        List<ModelCallLog.Call> calls = log.calls();
        assertEquals(ModelCallLog.MAX_CALLS, calls.size());
        assertEquals(5, log.summary().droppedCalls());
        // the five oldest went whole: q0..q4 are gone and q5 is now the tail of the history
        assertEquals("q204", calls.get(0).lastUserPreview());
        assertEquals("q5", calls.get(calls.size() - 1).lastUserPreview());
    }

    @Test
    void unreportedCacheIsNotZero() throws Exception {
        ModelCallLog log = new ModelCallLog();
        record(log, request("hi"), answer("yo", 100, -1), 300);
        drain();

        ModelCallLog.Call c = log.calls().get(0);
        assertEquals(-1, c.cacheReadTokens());
        assertFalse(c.hasCacheAccounting());
        assertEquals(-1, c.cacheReadShare());
        assertFalse(log.summary().cacheAccounted());
        assertEquals(-1, log.summary().cacheReadShare());
    }

    @Test
    void reportedZeroCacheIsALowShare() throws Exception {
        ModelCallLog log = new ModelCallLog();
        record(log, request("hi"), answer("yo", 100, 0), 300);
        drain();

        assertTrue(log.summary().cacheAccounted());
        assertEquals(0, log.summary().cacheReadShare(), 1e-9);
    }

    @Test
    void summaryTotalsTokensAndLatency() throws Exception {
        ModelCallLog log = new ModelCallLog();
        record(log, request("a"), answer("x", 100, 25), 400);
        record(log, request("b"), answer("y", 300, 150), 1200);
        drain();

        ModelCallLog.Summary s = log.summary();
        assertEquals(2, s.calls());
        assertEquals(400, s.promptTokens());
        assertEquals(175, s.cacheReadTokens());
        assertEquals(0.4375, s.cacheReadShare(), 1e-9);
        assertEquals(800, s.averageMillis());
        assertEquals(1200, s.slowestMillis());
        assertEquals(1, s.models());
    }

    @Test
    void producedNothingFlagsACallThatMovedTheTurnNowhere() throws Exception {
        ModelCallLog log = new ModelCallLog();
        NaruResponse toolCall = new NaruResponse(
                NaruMessage.assistantWithToolCalls("", List.of(new NaruToolCall("c1", "bash", Map.of("cmd", "ls")))), true);
        toolCall.setPromptTokens(40);
        record(log, request("a"), answer("   ", 50, 0), 100);
        record(log, request("b"), toolCall, 100);
        drain();

        List<ModelCallLog.Call> calls = log.calls();
        assertTrue(calls.get(1).producedNothing());
        assertFalse(calls.get(0).producedNothing());
        assertEquals(1, calls.get(0).toolCallCount());
    }

    @Test
    void clearForgetsTheDropsToo() throws Exception {
        ModelCallLog log = new ModelCallLog();
        for (int i = 0; i < ModelCallLog.MAX_CALLS + 2; i++) {
            record(log, request("q" + i), answer("a", 1, -1), 5);
        }
        drain();
        SwingUtilities.invokeAndWait(log::clear);

        assertTrue(log.calls().isEmpty());
        assertEquals(0, log.summary().droppedCalls());
    }

    @Test
    void listenerHearsEveryBatch() throws Exception {
        ModelCallLog log = new ModelCallLog();
        int[] fired = {0};
        SwingUtilities.invokeAndWait(() -> log.addListener(() -> fired[0]++));
        record(log, request("a"), answer("x", 1, -1), 5);
        record(log, request("b"), answer("y", 1, -1), 5);
        drain();

        assertEquals(2, fired[0]);
    }
}
