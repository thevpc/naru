package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.model.NaruChunkKind;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruStreamChunk;
import net.thevpc.naru.api.model.NaruStreamCollector;
import net.thevpc.naru.api.model.NaruThinkingExtraction;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.ext.models.openapi.NaruOpenApiResponseParser;
import net.thevpc.naru.ext.models.openapi.NaruOpenApiStreamParser;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElementReader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wire-protocol tests for the OpenAI-compatible <em>streamed</em> response.
 *
 * <p>The parity tests are the load-bearing ones. Everything downstream in NARU
 * -- the actor loop, the transcript, the budget meter -- was written against the
 * batched response, so a stream that produces a subtly different message is a
 * regression that only shows up as strange behaviour much later, once per
 * provider, in production.
 */
public class NaruOpenApiStreamParserTest {

    @BeforeAll
    public static void setUp() {
        Nuts.require();
    }

    private NaruStreamCollector collector;

    private NaruResponse stream(String... payloads) {
        collector = new NaruStreamCollector();
        NaruOpenApiStreamParser parser =
                new NaruOpenApiStreamParser("groq", collector, new NaruModelConfig("m", "groq"));
        for (String payload : payloads) {
            if (!parser.onEvent(null, payload)) {
                break;
            }
        }
        return parser.finish(false);
    }

    private NaruResponse parseBatch(String json) {
        NElementReader reader = NElementReader.ofJson();
        reader.mapperStore().setDeserializer(NaruResponse.class, new NaruOpenApiResponseParser());
        return reader.read(json, NaruResponse.class);
    }

    private List<String> chunkTexts(NaruChunkKind kind) {
        List<String> out = new ArrayList<>();
        for (NaruStreamChunk chunk : collector.chunks()) {
            if (chunk.kind() == kind) {
                out.add(chunk.text());
            }
        }
        return out;
    }

    // ── basic accumulation ────────────────────────────────────────────────────

    @Test
    public void contentDeltasConcatenateIntoTheAnswer() {
        NaruResponse r = stream(
                "{\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\", world\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"!\"}}]}",
                "[DONE]");
        Assertions.assertEquals("Hello, world!", r.getMessage().getContent());
    }

    @Test
    public void eachDeltaIsEmittedAsItsOwnChunk() {
        // the whole value of streaming: the renderer sees text before the rest
        // of the answer exists
        stream(
                "{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}",
                "[DONE]");
        Assertions.assertEquals(List.of("Hel", "lo"), chunkTexts(NaruChunkKind.ANSWER));
    }

    @Test
    public void chunkIndexesAreSequential() {
        stream(
                "{\"choices\":[{\"delta\":{\"content\":\"a\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"b\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"c\"}}]}",
                "[DONE]");
        List<NaruStreamChunk> chunks = collector.chunks();
        for (int i = 0; i < chunks.size(); i++) {
            Assertions.assertEquals(i, chunks.get(i).index(),
                    "index is what lets a consumer reassemble, so it must be dense and ordered");
        }
    }

    @Test
    public void finishReasonIsRecorded() {
        NaruResponse r = stream(
                "{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "[DONE]");
        Assertions.assertEquals("stop", r.getStopReason());
        Assertions.assertTrue(r.isDone());
    }

    @Test
    public void noFinishReasonMeansNotDone() {
        NaruResponse r = stream(
                "{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}",
                "[DONE]");
        // [DONE] means the server closed cleanly, but a turn with no
        // finish_reason is still not a generation that terminated on its terms
        Assertions.assertNull(r.getStopReason());
    }

    // ── reasoning ─────────────────────────────────────────────────────────────

    @Test
    public void reasoningContentIsSeparatedFromTheAnswer() {
        NaruResponse r = stream(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"Let me think.\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"42\"}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "[DONE]");
        Assertions.assertEquals("42", r.getMessage().getContent());
        Assertions.assertEquals("Let me think.", r.getMessage().getThinking());
        Assertions.assertEquals(List.of("Let me think."), chunkTexts(NaruChunkKind.THINKING));
    }

    @Test
    public void theReasoningAliasIsAlsoAccepted() {
        NaruResponse r = stream(
                "{\"choices\":[{\"delta\":{\"reasoning\":\"pondering\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}",
                "[DONE]");
        Assertions.assertEquals("pondering", r.getMessage().getThinking());
    }

    @Test
    public void nativeReasoningIsTaggedAsSuch() {
        stream(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"native\"}}]}",
                "[DONE]");
        NaruStreamChunk thinking = collector.chunks().get(0);
        Assertions.assertEquals(NaruThinkingExtraction.NATIVE_FIELD, thinking.extraction());
    }

    @Test
    public void interleavedReasoningAndAnswerKeepTheirOrder() {
        stream(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"a\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"b\"}}]}",
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"c\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"d\"}}]}",
                "[DONE]");
        List<String> order = new ArrayList<>();
        for (NaruStreamChunk chunk : collector.chunks()) {
            order.add(chunk.kind() + ":" + chunk.text());
        }
        Assertions.assertEquals(
                List.of("THINKING:a", "ANSWER:b", "THINKING:c", "ANSWER:d"), order);
    }

    @Test
    public void inlineThinkTagsInContentAreSplitOut() {
        // a model configured for tag-delimited reasoning puts the tags inside
        // content, exactly as the batch parser assumes
        NaruResponse r = stream(
                "{\"choices\":[{\"delta\":{\"content\":\"<think>\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"hmm, \"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"really?</think>\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"The answer is 7.\"}}]}",
                "[DONE]");
        Assertions.assertEquals("The answer is 7.", r.getMessage().getContent());
        Assertions.assertTrue(r.getMessage().getThinking().contains("really?"));
    }

    // ── tool calls ────────────────────────────────────────────────────────────

    @Test
    public void toolCallArgumentsArriveAsFragments() {
        NaruResponse r = stream(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"pa\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"th\\\":\\\"src\\\"}\"}}]}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "[DONE]");
        Assertions.assertTrue(r.hasToolCalls());
        NaruToolCall call = r.getMessage().getToolCalls().get(0);
        Assertions.assertEquals("call_1", call.getId());
        Assertions.assertEquals("read", call.getName());
        Map<String, Object> args = call.getArguments();
        Assertions.assertEquals("src", args.get("path"));
    }

    @Test
    public void twoToolCallsAreNotMerged() {
        // the index is the only thing tying fragments to a call
        NaruResponse r = stream(
                "{\"choices\":[{\"delta\":{\"tool_calls\":["
                        + "{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"one\",\"arguments\":\"{\\\"x\\\":\"}},"
                        + "{\"index\":1,\"id\":\"b\",\"function\":{\"name\":\"two\",\"arguments\":\"{\\\"y\\\":\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":["
                        + "{\"index\":0,\"function\":{\"arguments\":\"1}\"}},"
                        + "{\"index\":1,\"function\":{\"arguments\":\"2}\"}}]}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "[DONE]");
        Assertions.assertEquals(2, r.getMessage().getToolCalls().size());
        NaruToolCall first = r.getMessage().getToolCalls().get(0);
        NaruToolCall second = r.getMessage().getToolCalls().get(1);
        Assertions.assertEquals("one", first.getName());
        Assertions.assertEquals("two", second.getName());
        Assertions.assertEquals(1, first.getArguments().get("x"));
        Assertions.assertEquals(2, second.getArguments().get("y"));
    }

    // ── usage ─────────────────────────────────────────────────────────────────

    @Test
    public void usageFromTheTrailingFrameIsKept() {
        NaruResponse r = stream(
                "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18}}",
                "[DONE]");
        Assertions.assertEquals(11, r.getPromptTokens());
        Assertions.assertEquals(7, r.getEvalTokens());
        Assertions.assertEquals(18, r.getTotalTokens());
    }

    @Test
    public void cachedTokensAreASplitOfThePromptTotalNotAnAddition() {
        NaruResponse r = stream(
                "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":5,\"total_tokens\":105,"
                        + "\"prompt_tokens_details\":{\"cached_tokens\":60}}}",
                "[DONE]");
        Assertions.assertEquals(100, r.getPromptTokens());
        Assertions.assertEquals(60, r.getCacheReadTokens());
        Assertions.assertEquals(40, r.getCacheWriteTokens());
    }

    @Test
    public void absentUsageStaysUnreportedRatherThanZero() {
        // "not reported" and "reported as zero" mean very different things to a
        // cost model
        NaruResponse r = stream(
                "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}",
                "[DONE]");
        Assertions.assertEquals(-1, r.getTotalTokens());
        Assertions.assertFalse(r.hasCacheAccounting());
    }

    // ── robustness ────────────────────────────────────────────────────────────

    @Test
    public void aKeepAliveCommentIsIgnored() {
        NaruResponse r = stream(
                "{\"choices\":[{\"delta\":{\"content\":\"a\"}}]}",
                "[DONE]");
        Assertions.assertEquals("a", r.getMessage().getContent());
    }

    @Test
    public void aNonJsonFrameDoesNotKillTheStream() {
        // some providers interleave non-data events on the same connection
        NaruResponse r = stream(
                "{\"choices\":[{\"delta\":{\"content\":\"a\"}}]}",
                "<not json at all>",
                "{\"choices\":[{\"delta\":{\"content\":\"b\"}}]}",
                "[DONE]");
        Assertions.assertEquals("ab", r.getMessage().getContent());
    }

    @Test
    public void anEmptyDeltaIsHarmless() {
        NaruResponse r = stream(
                "{\"choices\":[{\"delta\":{}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}",
                "[DONE]");
        Assertions.assertEquals("x", r.getMessage().getContent());
        Assertions.assertEquals(1, collector.chunks().size());
    }

    @Test
    public void aUsageOnlyFrameWithNoChoicesIsAccepted() {
        NaruResponse r = stream(
                "{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}",
                "[DONE]");
        Assertions.assertEquals("x", r.getMessage().getContent());
        Assertions.assertEquals(4, r.getTotalTokens());
    }

    @Test
    public void anInterruptedStreamIsNotReportedAsDone() {
        collector = new NaruStreamCollector();
        NaruOpenApiStreamParser parser =
                new NaruOpenApiStreamParser("groq", collector, new NaruModelConfig("m", "groq"));
        parser.onEvent(null, "{\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}");
        Assertions.assertTrue(parser.hasDeliveredContent());
        NaruResponse r = parser.finish(true);
        Assertions.assertFalse(r.isDone());
        Assertions.assertEquals("partial", r.getMessage().getContent());
    }

    @Test
    public void aStreamThatDeliveredNothingReportsNothingDelivered() {
        collector = new NaruStreamCollector();
        NaruOpenApiStreamParser parser =
                new NaruOpenApiStreamParser("groq", collector, new NaruModelConfig("m", "groq"));
        parser.onEvent(null, "{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}");
        // a role-only frame is metadata, not output: retrying is still invisible
        Assertions.assertFalse(parser.hasDeliveredContent());
    }

    // ── parity with the batch parser ──────────────────────────────────────────

    @Test
    public void plainTextStreamMatchesTheBatchParser() {
        NaruResponse batched = parseBatch("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"Hello, world!\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":3,\"total_tokens\":12}}");
        NaruResponse streamed = stream(
                "{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"Hello, \"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"world!\"}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":3,\"total_tokens\":12}}",
                "[DONE]");
        assertSameTurn(batched, streamed);
    }

    @Test
    public void reasoningStreamMatchesTheBatchParser() {
        NaruResponse batched = parseBatch("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"42\",\"reasoning_content\":\"Let me think.\"},\"finish_reason\":\"stop\"}]}");
        NaruResponse streamed = stream(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"Let me think.\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"42\"}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "[DONE]");
        assertSameTurn(batched, streamed);
    }

    @Test
    public void inlineThinkTagsMatchTheBatchParser() {
        // the batch parser strips <think> out of content; the stream must too,
        // or the same model would print its reasoning as the answer
        NaruResponse batched = parseBatch("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"<think>hmm</think>The answer is 7.\"},\"finish_reason\":\"stop\"}]}");
        NaruResponse streamed = stream(
                "{\"choices\":[{\"delta\":{\"content\":\"<think>\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"hmm</think>\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"The answer is 7.\"}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "[DONE]");
        assertSameTurn(batched, streamed);
    }

    @Test
    public void toolCallStreamMatchesTheBatchParser() {
        NaruResponse batched = parseBatch("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\","
                + "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\\\"src\\\"}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}");
        NaruResponse streamed = stream(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"\\\"src\\\"}\"}}]}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "[DONE]");
        assertSameTurn(batched, streamed);
    }

    @Test
    public void cachedTokenAccountingMatchesTheBatchParser() {
        NaruResponse batched = parseBatch("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":2,\"total_tokens\":102,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":70}}}");
        NaruResponse streamed = stream(
                "{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":2,\"total_tokens\":102,"
                        + "\"prompt_tokens_details\":{\"cached_tokens\":70}}}",
                "[DONE]");
        assertSameTurn(batched, streamed);
    }

    /**
     * Asserts the two turn descriptions are interchangeable, field for field.
     */
    private void assertSameTurn(NaruResponse batched, NaruResponse streamed) {
        NaruMessage a = batched.getMessage();
        NaruMessage b = streamed.getMessage();
        Assertions.assertEquals(a.getContent(), b.getContent(), "answer text must match");
        Assertions.assertEquals(a.getThinking(), b.getThinking(), "thinking text must match");
        Assertions.assertEquals(a.hasToolCalls(), b.hasToolCalls(), "tool-call presence must match");
        if (a.hasToolCalls()) {
            Assertions.assertEquals(a.getToolCalls().size(), b.getToolCalls().size());
            for (int i = 0; i < a.getToolCalls().size(); i++) {
                NaruToolCall expected = a.getToolCalls().get(i);
                NaruToolCall actual = b.getToolCalls().get(i);
                Assertions.assertEquals(expected.getId(), actual.getId());
                Assertions.assertEquals(expected.getName(), actual.getName());
                Assertions.assertEquals(expected.getArguments(), actual.getArguments());
            }
        }
        Assertions.assertEquals(batched.isDone(), streamed.isDone(), "done must match");
        Assertions.assertEquals(batched.getStopReason(), streamed.getStopReason(),
                "stop reason must match");
        Assertions.assertEquals(batched.getPromptTokens(), streamed.getPromptTokens());
        Assertions.assertEquals(batched.getEvalTokens(), streamed.getEvalTokens());
        Assertions.assertEquals(batched.getTotalTokens(), streamed.getTotalTokens());
        Assertions.assertEquals(batched.getCacheReadTokens(), streamed.getCacheReadTokens());
        Assertions.assertEquals(batched.getCacheWriteTokens(), streamed.getCacheWriteTokens());
    }
}
