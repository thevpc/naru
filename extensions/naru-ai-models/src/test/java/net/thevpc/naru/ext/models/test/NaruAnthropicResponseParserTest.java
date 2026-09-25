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
}