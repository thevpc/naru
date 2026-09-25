package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.ext.models.openapi.NaruOpenApiResponseParser;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElementReader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Wire-protocol tests for the OpenAI-compatible ({@code openapi}) response shape,
 * exercising the same coverage as {@link NaruAnthropicResponseParserTest}.
 */
public class NaruOpenApiResponseParserTest {

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
        reader.mapperStore().setDeserializer(NaruResponse.class, new NaruOpenApiResponseParser());
        return reader.read(json, NaruResponse.class);
    }

    @Test
    public void testPlainTextStop() {
        NaruResponse r = parse("{\n" +
                "  \"id\": \"chatcmpl-01\",\n" +
                "  \"object\": \"chat.completion\",\n" +
                "  \"choices\": [{\n" +
                "    \"index\": 0,\n" +
                "    \"finish_reason\": \"stop\",\n" +
                "    \"message\": {\"role\": \"assistant\", \"content\": \"Hello from OpenAI\"}\n" +
                "  }],\n" +
                "  \"usage\": {\"prompt_tokens\": 25, \"completion_tokens\": 30, \"total_tokens\": 55}\n" +
                "}");
        Assertions.assertTrue(r.isDone());
        Assertions.assertEquals("stop", r.getStopReason());
        Assertions.assertEquals("Hello from OpenAI", r.getMessage().getContent());
        Assertions.assertEquals(25, r.getPromptTokens());
        Assertions.assertEquals(30, r.getEvalTokens());
        Assertions.assertEquals(55, r.getTotalTokens());
        Assertions.assertFalse(r.hasToolCalls());
    }

    @Test
    public void testToolCallsFinishReason() {
        NaruResponse r = parse("{\n" +
                "  \"id\": \"chatcmpl-02\",\n" +
                "  \"object\": \"chat.completion\",\n" +
                "  \"choices\": [{\n" +
                "    \"index\": 0,\n" +
                "    \"finish_reason\": \"tool_calls\",\n" +
                "    \"message\": {\n" +
                "      \"role\": \"assistant\",\n" +
                "      \"content\": null,\n" +
                "      \"tool_calls\": [{\n" +
                "        \"id\": \"call_1\",\n" +
                "        \"type\": \"function\",\n" +
                "        \"function\": {\"name\": \"web_search\", \"arguments\": \"{\\\"q\\\":\\\"naru\\\"}\"}\n" +
                "      }]\n" +
                "    }\n" +
                "  }],\n" +
                "  \"usage\": {\"prompt_tokens\": 10, \"completion_tokens\": 5, \"total_tokens\": 15}\n" +
                "}");
        Assertions.assertTrue(r.isDone());
        Assertions.assertEquals("tool_calls", r.getStopReason());
        Assertions.assertTrue(r.hasToolCalls());
        NaruToolCall call = r.getMessage().getToolCalls().get(0);
        Assertions.assertEquals("call_1", call.getId());
        Assertions.assertEquals("web_search", call.getName());
        Assertions.assertEquals("naru", call.getArguments().get("q"));
    }

    @Test
    public void testLengthNotDone() {
        NaruResponse r = parse("{\n" +
                "  \"id\": \"chatcmpl-03\",\n" +
                "  \"object\": \"chat.completion\",\n" +
                "  \"choices\": [{\n" +
                "    \"index\": 0,\n" +
                "    \"finish_reason\": \"length\",\n" +
                "    \"message\": {\"role\": \"assistant\", \"content\": \"partial answer\"}\n" +
                "  }],\n" +
                "  \"usage\": {\"prompt_tokens\": 1, \"completion_tokens\": 2, \"total_tokens\": 3}\n" +
                "}");
        Assertions.assertFalse(r.isDone());
        Assertions.assertEquals("length", r.getStopReason());
        Assertions.assertEquals("partial answer", r.getMessage().getContent());
    }

    @Test
    public void testReasoningContentExtracted() {
        NaruResponse r = parse("{\n" +
                "  \"id\": \"chatcmpl-04\",\n" +
                "  \"object\": \"chat.completion\",\n" +
                "  \"choices\": [{\n" +
                "    \"index\": 0,\n" +
                "    \"finish_reason\": \"stop\",\n" +
                "    \"message\": {\n" +
                "      \"role\": \"assistant\",\n" +
                "      \"content\": \"final answer\",\n" +
                "      \"reasoning_content\": \"think step by step\"\n" +
                "    }\n" +
                "  }],\n" +
                "  \"usage\": {}\n" +
                "}");
        Assertions.assertTrue(r.isDone());
        Assertions.assertEquals("final answer", r.getMessage().getContent());
        Assertions.assertEquals("think step by step", r.getMessage().getThinking());
    }

    @Test
    public void testCachedTokensArePartOfPromptTotal() {
        // OpenAI reports cached tokens as a breakdown INSIDE prompt_tokens, so
        // they must not be added on top of it.
        NaruResponse r = parse("{\n" +
                "  \"choices\": [{\"index\": 0, \"message\": {\"role\": \"assistant\", \"content\": \"ok\"}, \"finish_reason\": \"stop\"}],\n" +
                "  \"usage\": {\"prompt_tokens\": 1000, \"completion_tokens\": 20, \"total_tokens\": 1020,\n" +
                "    \"prompt_tokens_details\": {\"cached_tokens\": 750}}\n" +
                "}");
        Assertions.assertEquals(1000, r.getPromptTokens());
        Assertions.assertEquals(750, r.getCacheReadTokens());
        Assertions.assertEquals(250, r.getCacheWriteTokens(),
                "whatever was not served from cache was processed at full rate");
        Assertions.assertEquals(1020, r.getTotalTokens(), "total must not double-count cached tokens");
    }

    @Test
    public void testNoPromptDetailsLeavesCacheUnreported() {
        NaruResponse r = parse("{\n" +
                "  \"choices\": [{\"index\": 0, \"message\": {\"role\": \"assistant\", \"content\": \"ok\"}, \"finish_reason\": \"stop\"}],\n" +
                "  \"usage\": {\"prompt_tokens\": 100, \"completion_tokens\": 20, \"total_tokens\": 120}\n" +
                "}");
        Assertions.assertFalse(r.hasCacheAccounting());
    }
}
