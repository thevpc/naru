package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.model.NaruChunkKind;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruStreamChunk;
import net.thevpc.naru.api.model.NaruStreamCollector;
import net.thevpc.naru.api.model.NaruThinkingExtraction;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.ext.models.ollama.NaruOllamaNativeResponseParser;
import net.thevpc.naru.ext.models.ollama.NaruOllamaNativeStreamParser;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElementReader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Wire-protocol tests for Ollama's streamed {@code /api/chat}.
 *
 * <p>Ollama streams NDJSON, not SSE: one bare JSON document per line, no
 * {@code data:} prefix, no frame separator, and no {@code [DONE]} sentinel --
 * the stream ends when a document says {@code done:true}. Feeding this format to
 * an SSE reader yields zero events rather than an error, which looks exactly
 * like a model with nothing to say, so the framing itself is asserted here.
 */
public class NaruOllamaNativeStreamParserTest {

    @BeforeAll
    public static void setUp() {
        Nuts.require();
    }

    private NaruStreamCollector collector;

    private NaruResponse stream(String... documents) {
        collector = new NaruStreamCollector();
        NaruOllamaNativeStreamParser parser =
                new NaruOllamaNativeStreamParser("ollama", collector, new NaruModelConfig("m", "ollama"));
        for (String document : documents) {
            if (!parser.onLine(document)) {
                break;
            }
        }
        return parser.finish(false);
    }

    private NaruResponse parseBatch(String json) {
        NElementReader reader = NElementReader.ofJson();
        reader.mapperStore().setDeserializer(NaruResponse.class, new NaruOllamaNativeResponseParser());
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

    // ── framing ───────────────────────────────────────────────────────────────

    @Test
    public void bareJsonLinesAreAcceptedWithNoSseFraming() {
        // the whole point: no "data:" prefix, no blank line between documents
        NaruResponse r = stream(
                "{\"model\":\"qwen2.5-coder:7b\",\"message\":{\"role\":\"assistant\",\"content\":\"Hel\"},\"done\":false}",
                "{\"model\":\"qwen2.5-coder:7b\",\"message\":{\"role\":\"assistant\",\"content\":\"lo\"},\"done\":false}",
                "{\"model\":\"qwen2.5-coder:7b\",\"message\":{\"role\":\"assistant\",\"content\":\"!\"},\"done\":true}");
        Assertions.assertEquals("Hello!", r.getMessage().getContent());
    }

    @Test
    public void aSseFramedBodyWouldProduceNothing() {
        // documents the failure mode this parser exists to avoid
        collector = new NaruStreamCollector();
        NaruOllamaNativeStreamParser parser =
                new NaruOllamaNativeStreamParser("ollama", collector, new NaruModelConfig("m", "ollama"));
        boolean keepGoing = parser.onLine("data: {\"message\":{\"content\":\"hi\"},\"done\":false}");
        Assertions.assertTrue(keepGoing, "a line is not an error");
        Assertions.assertEquals(0, collector.chunks().size(),
                "a data: prefix is not part of this protocol, so nothing is extracted");
    }

    @Test
    public void aBlankLineIsSkipped() {
        NaruResponse r = stream(
                "",
                "{\"message\":{\"content\":\"a\"},\"done\":false}",
                "   ",
                "{\"message\":{\"content\":\"b\"},\"done\":true}");
        Assertions.assertEquals("ab", r.getMessage().getContent());
    }

    @Test
    public void aMalformedLineIsSkippedWithoutLosingTheAnswer() {
        NaruResponse r = stream(
                "{\"message\":{\"content\":\"a\"},\"done\":false}",
                "this is not json",
                "{\"message\":{\"content\":\"b\"},\"done\":true}");
        Assertions.assertEquals("ab", r.getMessage().getContent());
    }

    @Test
    public void readingStopsAtTheDoneDocument() {
        collector = new NaruStreamCollector();
        NaruOllamaNativeStreamParser parser =
                new NaruOllamaNativeStreamParser("ollama", collector, new NaruModelConfig("m", "ollama"));
        Assertions.assertFalse(parser.onLine("{\"message\":{\"content\":\"a\"},\"done\":true}"),
                "done:true is the end of stream and the caller must stop reading");
        Assertions.assertFalse(parser.onLine("{\"message\":{\"content\":\"never\"},\"done\":false}"),
                "a document after done is refused, not merged into the answer");
        Assertions.assertEquals("a", parser.finish(false).getMessage().getContent());
    }

    // ── content ───────────────────────────────────────────────────────────────

    @Test
    public void eachDocumentIsEmittedAsItsOwnChunk() {
        stream(
                "{\"message\":{\"content\":\"a\"},\"done\":false}",
                "{\"message\":{\"content\":\"b\"},\"done\":false}",
                "{\"message\":{\"content\":\"c\"},\"done\":true}");
        Assertions.assertEquals(List.of("a", "b", "c"), chunkTexts(NaruChunkKind.ANSWER));
    }

    @Test
    public void anEmptyContentDocumentEmitsNoChunk() {
        // Ollama sends an empty message on the final document
        stream(
                "{\"message\":{\"content\":\"a\"},\"done\":false}",
                "{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true}");
        Assertions.assertEquals(List.of("a"), chunkTexts(NaruChunkKind.ANSWER));
    }

    // ── thinking ──────────────────────────────────────────────────────────────

    @Test
    public void thinkingIsSeparatedFromTheAnswer() {
        NaruResponse r = stream(
                "{\"message\":{\"content\":\"\",\"thinking\":\"Let me consider\"},\"done\":false}",
                "{\"message\":{\"content\":\"the answer\",\"thinking\":\" the options.\"},\"done\":false}",
                "{\"done\":true}");
        Assertions.assertEquals("the answer", r.getMessage().getContent());
        Assertions.assertEquals("Let me consider the options.", r.getMessage().getThinking());
        Assertions.assertEquals(List.of("Let me consider", " the options."),
                chunkTexts(NaruChunkKind.THINKING));
    }

    @Test
    public void nativeThinkingIsTaggedAsSuch() {
        stream(
                "{\"message\":{\"thinking\":\"hmm\"},\"done\":false}",
                "{\"done\":true}");
        Assertions.assertEquals(NaruThinkingExtraction.NATIVE_FIELD,
                collector.chunks().get(0).extraction());
    }

    @Test
    public void interleavedThinkingAndAnswerKeepTheirOrder() {
        stream(
                "{\"message\":{\"thinking\":\"a\"},\"done\":false}",
                "{\"message\":{\"content\":\"b\"},\"done\":false}",
                "{\"message\":{\"thinking\":\"c\"},\"done\":false}",
                "{\"message\":{\"content\":\"d\"},\"done\":true}");
        List<String> order = new ArrayList<>();
        for (NaruStreamChunk chunk : collector.chunks()) {
            order.add(chunk.kind() + ":" + chunk.text());
        }
        Assertions.assertEquals(
                List.of("THINKING:a", "ANSWER:b", "THINKING:c", "ANSWER:d"), order);
    }

    @Test
    public void inlineThinkTagsInContentAreSplitOut() {
        NaruResponse r = stream(
                "{\"message\":{\"content\":\"<think>\"},\"done\":false}",
                "{\"message\":{\"content\":\"weighing it up</think>\"},\"done\":false}",
                "{\"message\":{\"content\":\"7\"},\"done\":true}");
        Assertions.assertEquals("7", r.getMessage().getContent());
        Assertions.assertTrue(r.getMessage().getThinking().contains("weighing it up"));
    }

    // ── tool calls ────────────────────────────────────────────────────────────

    @Test
    public void toolCallsArriveWholeAndAreNotSplit() {
        NaruResponse r = stream(
                "{\"message\":{\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"read\","
                        + "\"arguments\":{\"path\":\"src/main.java\"}}}]},\"done\":false}",
                "{\"done\":true,\"done_reason\":\"stop\"}");
        Assertions.assertTrue(r.hasToolCalls());
        NaruToolCall call = r.getMessage().getToolCalls().get(0);
        Assertions.assertEquals("read", call.getName());
        Assertions.assertEquals("src/main.java", call.getArguments().get("path"),
                "an argument value must be the plain string, not its TSON rendering");
    }

    // ── termination and usage ─────────────────────────────────────────────────

    @Test
    public void doneReasonIsRecorded() {
        NaruResponse r = stream(
                "{\"message\":{\"content\":\"a\"},\"done\":true,\"done_reason\":\"stop\"}");
        Assertions.assertEquals("stop", r.getStopReason());
        Assertions.assertTrue(r.isDone());
    }

    @Test
    public void tokenCountsOnTheFinalDocumentAreKept() {
        NaruResponse r = stream(
                "{\"message\":{\"content\":\"a\"},\"done\":false}",
                "{\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":31,\"eval_count\":12}");
        Assertions.assertEquals(31, r.getPromptTokens());
        Assertions.assertEquals(12, r.getEvalTokens());
        Assertions.assertEquals(43, r.getTotalTokens());
    }

    @Test
    public void absentCountsStayUnreported() {
        NaruResponse r = stream(
                "{\"message\":{\"content\":\"a\"},\"done\":true,\"done_reason\":\"stop\"}");
        Assertions.assertEquals(-1, r.getTotalTokens());
    }

    @Test
    public void anInterruptedStreamIsNotReportedAsDone() {
        collector = new NaruStreamCollector();
        NaruOllamaNativeStreamParser parser =
                new NaruOllamaNativeStreamParser("ollama", collector, new NaruModelConfig("m", "ollama"));
        parser.onLine("{\"message\":{\"content\":\"partial\"},\"done\":false}");
        Assertions.assertTrue(parser.hasDeliveredContent());
        NaruResponse r = parser.finish(true);
        Assertions.assertFalse(r.isDone());
        Assertions.assertEquals("partial", r.getMessage().getContent());
    }

    @Test
    public void aRoleOnlyDocumentCountsAsNoOutput() {
        collector = new NaruStreamCollector();
        NaruOllamaNativeStreamParser parser =
                new NaruOllamaNativeStreamParser("ollama", collector, new NaruModelConfig("m", "ollama"));
        parser.onLine("{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":false}");
        Assertions.assertFalse(parser.hasDeliveredContent(),
                "metadata is not output: retrying must stay invisible to the user");
    }

    // ── parity with the batch parser ──────────────────────────────────────────

    @Test
    public void plainTextStreamMatchesTheBatchParser() {
        NaruResponse batched = parseBatch("{\"model\":\"qwen2.5-coder:7b\",\"message\":{\"role\":\"assistant\","
                + "\"content\":\"Hello, world!\"},\"done\":true,\"done_reason\":\"stop\","
                + "\"prompt_eval_count\":9,\"eval_count\":3}");
        NaruResponse streamed = stream(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"Hello, \"},\"done\":false}",
                "{\"message\":{\"content\":\"world!\"},\"done\":false}",
                "{\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":9,\"eval_count\":3}");
        assertSameTurn(batched, streamed);
    }

    @Test
    public void thinkingStreamMatchesTheBatchParser() {
        NaruResponse batched = parseBatch("{\"message\":{\"role\":\"assistant\",\"content\":\"42\","
                + "\"thinking\":\"Let me think.\"},\"done\":true,\"done_reason\":\"stop\"}");
        NaruResponse streamed = stream(
                "{\"message\":{\"thinking\":\"Let me think.\"},\"done\":false}",
                "{\"message\":{\"content\":\"42\"},\"done\":false}",
                "{\"done\":true,\"done_reason\":\"stop\"}");
        assertSameTurn(batched, streamed);
    }

    @Test
    public void inlineThinkTagsMatchTheBatchParser() {
        NaruResponse batched = parseBatch("{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"<think>hmm</think>The answer is 7.\"},\"done\":true,\"done_reason\":\"stop\"}");
        NaruResponse streamed = stream(
                "{\"message\":{\"content\":\"<think>hmm</think>\"},\"done\":false}",
                "{\"message\":{\"content\":\"The answer is 7.\"},\"done\":true,\"done_reason\":\"stop\"}");
        assertSameTurn(batched, streamed);
    }

    @Test
    public void toolCallStreamMatchesTheBatchParser() {
        String call = "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"c1\","
                + "\"function\":{\"name\":\"read\",\"arguments\":{\"path\":\"src\"}}}]},\"done\":true,"
                + "\"done_reason\":\"stop\"}";
        NaruResponse batched = parseBatch(call);
        NaruResponse streamed = stream(call);
        assertSameTurn(batched, streamed);
    }

    private void assertSameTurn(NaruResponse batched, NaruResponse streamed) {
        NaruMessage a = batched.getMessage();
        NaruMessage b = streamed.getMessage();
        Assertions.assertNotNull(b, "a streamed turn must always produce a message");
        Assertions.assertEquals(a.getContent(), b.getContent(), "answer text must match");
        Assertions.assertEquals(a.getThinking(), b.getThinking(), "thinking text must match");
        Assertions.assertEquals(a.hasToolCalls(), b.hasToolCalls(), "tool-call presence must match");
        if (a.hasToolCalls()) {
            Assertions.assertEquals(a.getToolCalls().size(), b.getToolCalls().size());
            for (int i = 0; i < a.getToolCalls().size(); i++) {
                NaruToolCall expected = a.getToolCalls().get(i);
                NaruToolCall actual = b.getToolCalls().get(i);
                Assertions.assertEquals(expected.getName(), actual.getName());
                Assertions.assertEquals(expected.getArguments(), actual.getArguments());
            }
        }
        Assertions.assertEquals(batched.getStopReason(), streamed.getStopReason());
        Assertions.assertEquals(batched.getPromptTokens(), streamed.getPromptTokens());
        Assertions.assertEquals(batched.getEvalTokens(), streamed.getEvalTokens());
        Assertions.assertEquals(batched.getTotalTokens(), streamed.getTotalTokens());
    }
}
