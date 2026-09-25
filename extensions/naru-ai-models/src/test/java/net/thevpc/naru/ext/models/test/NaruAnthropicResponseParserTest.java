package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.ext.models.anthropic.NaruAnthropicResponseParser;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElementReader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class NaruAnthropicResponseParserTest {

    @BeforeAll
    public static void setUp() {
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            try {
                NWorkspace ws = Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private NaruResponse parse(String json) {
        NElementReader reader = NElementReader.ofJson();
        reader.mapperStore().setDeserializer(NaruResponse.class, new NaruAnthropicResponseParser());
        return reader.read(json, NaruResponse.class);
    }

    @Test
    public void testPlainTextEndTurn() {
        NaruResponse r = parse("{\n" +
                "  \"id\": \"msg_01\",\n" +
                "  \"type\": \"message\",\n" +
                "  \"role\": \"assistant\",\n" +
                "  \"content\": [{\"type\": \"text\", \"text\": \"Hello from Anthropic\"}],\n" +
                "  \"stop_reason\": \"end_turn\",\n" +
                "  \"usage\": {\"input_tokens\": 25, \"output_tokens\": 30}\n" +
                "}");
        Assertions.assertTrue(r.isDone());
        Assertions.assertEquals("end_turn", r.getStopReason());
        Assertions.assertEquals("Hello from Anthropic", r.getMessage().getContent());
        Assertions.assertEquals(25, r.getPromptTokens());
        Assertions.assertEquals(30, r.getEvalTokens());
        Assertions.assertEquals(55, r.getTotalTokens());
        Assertions.assertFalse(r.hasToolCalls());
    }

    @Test
    public void testToolUseStopReason() {
        NaruResponse r = parse("{\n" +
                "  \"id\": \"msg_02\",\n" +
                "  \"type\": \"message\",\n" +
                "  \"role\": \"assistant\",\n" +
                "  \"content\": [\n" +
                "    {\"type\": \"tool_use\", \"id\": \"toolu_1\", \"name\": \"web_search\", \"input\": {\"q\": \"naru\"}}\n" +
                "  ],\n" +
                "  \"stop_reason\": \"tool_use\",\n" +
                "  \"usage\": {\"input_tokens\": 10, \"output_tokens\": 5}\n" +
                "}");
        Assertions.assertTrue(r.isDone());
        Assertions.assertTrue(r.hasToolCalls());
        NaruToolCall call = r.getMessage().getToolCalls().get(0);
        Assertions.assertEquals("toolu_1", call.getId());
        Assertions.assertEquals("web_search", call.getName());
        Assertions.assertEquals("naru", call.getArguments().get("q"));
    }

    @Test
    public void testTextThenToolUseBlocks() {
        NaruResponse r = parse("{\n" +
                "  \"content\": [\n" +
                "    {\"type\": \"text\", \"text\": \"Let me check that.\"},\n" +
                "    {\"type\": \"tool_use\", \"id\": \"toolu_2\", \"name\": \"web_search\", \"input\": {\"q\": \"glm 5.2\"}}\n" +
                "  ],\n" +
                "  \"stop_reason\": \"tool_use\",\n" +
                "  \"usage\": {}\n" +
                "}");
        Assertions.assertEquals("Let me check that.", r.getMessage().getContent());
        Assertions.assertEquals(1, r.getMessage().getToolCalls().size());
    }

    @Test
    public void testMaxTokensNotDone() {
        NaruResponse r = parse("{\n" +
                "  \"content\": [{\"type\": \"text\", \"text\": \"partial\"}],\n" +
                "  \"stop_reason\": \"max_tokens\",\n" +
                "  \"usage\": {\"input_tokens\": 1, \"output_tokens\": 2}\n" +
                "}");
        Assertions.assertFalse(r.isDone());
        Assertions.assertEquals("max_tokens", r.getStopReason());
    }

    @Test
    public void testCacheUsageParsed() {
        // Anthropic excludes both cache reads and cache writes from
        // input_tokens, so the billed input total is the sum of all three.
        NaruResponse r = parse("{\n" +
                "  \"id\": \"msg_c1\",\n" +
                "  \"content\": [{\"type\": \"text\", \"text\": \"hi\"}],\n" +
                "  \"stop_reason\": \"end_turn\",\n" +
                "  \"usage\": {\n" +
                "    \"input_tokens\": 10,\n" +
                "    \"output_tokens\": 5,\n" +
                "    \"cache_creation_input_tokens\": 1200,\n" +
                "    \"cache_read_input_tokens\": 800\n" +
                "  }\n" +
                "}");
        Assertions.assertEquals(10, r.getPromptTokens());
        Assertions.assertEquals(1200, r.getCacheWriteTokens());
        Assertions.assertEquals(800, r.getCacheReadTokens());
        Assertions.assertTrue(r.hasCacheAccounting());
    }

    @Test
    public void testNoCacheFieldsLeavesCacheTokensUnset() {
        NaruResponse r = parse("{\n" +
                "  \"content\": [{\"type\": \"text\", \"text\": \"hi\"}],\n" +
                "  \"stop_reason\": \"end_turn\",\n" +
                "  \"usage\": {\"input_tokens\": 25, \"output_tokens\": 30}\n" +
                "}");
        Assertions.assertFalse(r.hasCacheAccounting(),
                "a provider that does not report cache usage must not be recorded as a zero-cache turn");
    }

    @Test
    public void testCacheReadOnlyTurn() {
        NaruResponse r = parse("{\n" +
                "  \"content\": [{\"type\": \"text\", \"text\": \"hi\"}],\n" +
                "  \"usage\": {\n" +
                "    \"input_tokens\": 15,\n" +
                "    \"output_tokens\": 4,\n" +
                "    \"cache_read_input_tokens\": 2000\n" +
                "  }\n" +
                "}");
        Assertions.assertEquals(0, r.getCacheWriteTokens());
        Assertions.assertEquals(2000, r.getCacheReadTokens());
    }
}
