package net.thevpc.naru.desktop.util;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.desktop.model.ModelCallLog;
import net.thevpc.nuts.Nuts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Budget tab's text, headless. The one promise worth testing is the distinction between a zero
 * and a silence: a provider that answers no usage question must never be drawn as a cache that
 * never hits or a call that used no tokens, because only one of those is a problem the reader can
 * act on.
 */
class BudgetTextTest {

    @BeforeEach
    void session() {
        Nuts.require();
    }

    private static NaruModelRequest request(String prompt) {
        return new NaruModelRequest(List.of(NaruMessage.system("sys"), NaruMessage.user(prompt)),
                List.of(), Map.of());
    }

    private static NaruResponse answer(String text, int prompt, int out, int cacheRead, String stop) {
        NaruResponse r = new NaruResponse(NaruMessage.assistant(text), true);
        r.setPromptTokens(prompt);
        r.setEvalTokens(out);
        r.setCacheReadTokens(cacheRead);
        r.setStopReason(stop);
        return r;
    }

    private static ModelCallLog.Call call(long taskId, String provider, String model,
                                          NaruResponse response, long millis) {
        return new ModelCallLog.Call(1, taskId, new NaruModelKey(provider, model),
                request("question"), response, millis, Instant.now());
    }

    /** Aggregate the calls the same way the log does, so the render is the thing under test. */
    private static ModelCallLog.Summary summary(List<ModelCallLog.Call> calls, int dropped) {
        long in = 0, out = 0, read = 0, write = 0, timed = 0, total = 0, slowest = 0;
        boolean cache = false;
        java.util.Set<Long> tasks = new java.util.HashSet<>();
        java.util.Set<String> models = new java.util.HashSet<>();
        for (ModelCallLog.Call c : calls) {
            tasks.add(c.taskId());
            models.add(c.modelLabel());
            in += Math.max(0, c.promptTokens());
            out += Math.max(0, c.completionTokens());
            read += Math.max(0, c.cacheReadTokens());
            write += Math.max(0, c.cacheWriteTokens());
            cache |= c.hasCacheAccounting();
            if (c.durationMillis() >= 0) {
                timed++;
                total += c.durationMillis();
                slowest = Math.max(slowest, c.durationMillis());
            }
        }
        return new ModelCallLog.Summary(calls.size(), tasks.size(), models.size(), in, out, read, write,
                cache, timed == 0 ? -1 : total / timed, timed == 0 ? -1 : slowest, dropped);
    }

    @Test
    void reportsMeasuredCallsInFull() {
        List<ModelCallLog.Call> calls = List.of(
                call(4, "anthropic", "claude-sonnet", answer("one", 100, 20, 25, "end_turn"), 400),
                call(4, "anthropic", "claude-sonnet", answer("two", 300, 40, 150, "tool_use"), 1200));

        String text = BudgetText.render("1 200 tokens used", summary(calls, 0), calls);

        assertTrue(text.contains("Session totals · 1 200 tokens used"), text);
        assertTrue(text.contains("Calls in view · 2 across 1 task, 1 model"), text);
        assertTrue(text.contains("Tokens · 400 in / 60 out"), text);
        assertTrue(text.contains("Cache · read 175, write 0 · 44% of input served from cache"), text);
        assertTrue(text.contains("Latency · avg 800 ms · slowest 1.2 s"), text);
        assertTrue(text.contains("Stop reasons · end_turn 1  tool_use 1"), text);
        assertFalse(text.contains("dropped from the view"), text);
    }

    @Test
    void anUnansweredUsageQuestionIsNeverDrawnAsZero() {
        // ollama answers nothing: response is null and nothing was timed
        List<ModelCallLog.Call> calls = List.of(call(1, "ollama", "qwen2.5-coder:14b", null, -1));

        String text = BudgetText.render("", summary(calls, 0), calls);

        assertTrue(text.contains("Tokens · not reported"), text);
        assertTrue(text.contains("Cache · not reported"), text);
        assertTrue(text.contains("Latency · avg not measured · slowest not measured"), text);
        // a model that answered no usage question at all gets one silence, not a zero per field
        assertTrue(text.contains("ollama / qwen2.5-coder:14b · 1 calls · tokens not reported · avg not measured"), text);
        assertFalse(text.contains(" 0 in / 0 out"), text);
    }

    @Test
    void aCallThatEndedWithoutMovingTheTurnIsCounted() {
        NaruResponse blank = answer("", 50, 5, -1, "end_turn");
        List<ModelCallLog.Call> calls = List.of(call(1, "anthropic", "claude-sonnet", blank, 100),
                call(1, "anthropic", "claude-sonnet", answer("text", 50, 5, -1, "end_turn"), 100));

        String text = BudgetText.render(null, summary(calls, 0), calls);

        assertTrue(text.contains("Outcome · 1 call reported no text (open Requests to see which)"), text);
    }

    @Test
    void droppedCallsAreDeclaredBeforeTheFigures() {
        List<ModelCallLog.Call> calls = List.of(
                call(1, "anthropic", "claude-sonnet", answer("x", 10, 1, 0, "end_turn"), 50));

        String text = BudgetText.render(null, summary(calls, 37), calls);

        assertTrue(text.contains("Earlier calls dropped from the view · 37"), text);
    }

    @Test
    void perModelSplitsAMixedSessionAndCacheShareUsesInput() {
        ModelCallLog.Call a = call(1, "anthropic", "claude-sonnet", answer("x", 1000, 10, 800, "end_turn"), 900);
        ModelCallLog.Call b = call(2, "ollama", "qwen", answer("y", 200, 4, -1, "stop"), 2000);
        List<ModelCallLog.Call> calls = List.of(a, b);
        ModelCallLog.Summary s = summary(calls, 0);

        String text = BudgetText.render(null, s, calls);

        // two tasks, two models, and the aggregate share covers only what was reported
        assertTrue(text.contains("Calls in view · 2 across 2 tasks, 2 models"), text);
        assertTrue(text.contains("anthropic / claude-sonnet · 1 calls · 1,000 in / 10 out · 80% of input served from cache · avg 900 ms"), text);
        assertTrue(text.contains("ollama / qwen · 1 calls · 200 in / 4 out · cache not reported · avg 2.0 s"), text);
    }

    @Test
    void anEmptyViewStillRendersItsHeader() {
        String text = BudgetText.render(null, summary(List.of(), 0), List.of());

        assertTrue(text.contains("Calls in view · 0 across 0 tasks, 0 models"), text);
        assertTrue(text.contains("Tokens · not reported"), text);
        assertFalse(text.contains("Per model"), text);
    }
}
