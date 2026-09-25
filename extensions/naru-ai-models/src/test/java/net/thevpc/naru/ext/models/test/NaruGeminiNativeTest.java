package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.ext.models.NaruModelProtocolTypes;
import net.thevpc.naru.ext.models.gemini.NaruGeminiNativeRequestSerializer;
import net.thevpc.naru.ext.models.gemini.NaruGeminiNativeResponseParser;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NArrayElement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NObjectElement;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Wire-format tests for the native Gemini protocol.
 *
 * <p>These are fixture-based: the JSON shapes are the ones Google's
 * {@code generateContent} API specifies. They cannot prove the API accepts
 * them, but they do pin the three places native Gemini departs from every other
 * protocol NARU speaks — system instruction, {@code model} role, and grouped
 * function declarations — which is exactly where a translation bug would hide.
 */
public class NaruGeminiNativeTest {

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

    private static NaruModelConfig model() {
        return new NaruModelConfig("gemini", "gemini-2.5-pro");
    }

    private static NObjectElement serialize(NaruModelRequest request) {
        return new NaruGeminiNativeRequestSerializer()
                .serialize(request, model(), null, NaruCachePlanView.none())
                .asObject().get();
    }

    @Test
    public void systemMessagesGoToSystemInstructionNotContents() {
        NObjectElement body = serialize(new NaruModelRequest(Arrays.asList(
                NaruMessage.system("You are NARU."),
                NaruMessage.user("hello")), Collections.emptyMap()));

        NArrayElement contents = body.getArray("contents").get();
        Assertions.assertEquals(1, contents.children().size(),
                "system material must not appear as a user turn in contents");
        Assertions.assertEquals("user", contents.children().get(0).asObject().get()
                .getStringValue("role").get());

        NObjectElement instruction = body.getObject("systemInstruction").get();
        Assertions.assertEquals("system", instruction.getStringValue("role").get());
        Assertions.assertEquals("You are NARU.", instruction.getArray("parts").get()
                .children().get(0).asObject().get().getStringValue("text").get());
    }

    @Test
    public void assistantRoleBecomesModel() {
        NObjectElement body = serialize(new NaruModelRequest(Arrays.asList(
                NaruMessage.user("hi"),
                NaruMessage.assistant("hello")), Collections.emptyMap()));

        NArrayElement contents = body.getArray("contents").get();
        Assertions.assertEquals("user", contents.children().get(0).asObject().get()
                .getStringValue("role").get());
        Assertions.assertEquals("model", contents.children().get(1).asObject().get()
                .getStringValue("role").get(),
                "Gemini calls the assistant 'model'");
    }

    @Test
    public void toolDefinitionsAreGroupedUnderOneFunctionDeclarationsList() {
        List<NaruToolDefinition> tools = Arrays.asList(
                new NaruToolDefinitionFunction("search", "search", List.of(
                        NaruToolParameter.string("q", "query", true).build())),
                new NaruToolDefinitionFunction("write", "write", List.of()));
        NObjectElement body = serialize(new NaruModelRequest(
                Collections.singletonList(NaruMessage.user("hi")), tools, Collections.emptyMap()));

        NArrayElement toolsArr = body.getArray("tools").get();
        Assertions.assertEquals(1, toolsArr.children().size(),
                "Gemini groups all declarations in a single tools entry");
        NArrayElement decls = toolsArr.children().get(0).asObject().get()
                .getArray("functionDeclarations").get();
        Assertions.assertEquals(2, decls.children().size());
        Assertions.assertEquals("search", decls.children().get(0).asObject().get()
                .getStringValue("name").get());
    }

    @Test
    public void toolResultBecomesFunctionResponseOnAUserTurn() {
        NObjectElement body = serialize(new NaruModelRequest(
                Collections.singletonList(NaruMessage.tool("search", "call_1", "the answer")),
                Collections.emptyMap()));

        NObjectElement content = body.getArray("contents").get().children().get(0).asObject().get();
        Assertions.assertEquals("user", content.getStringValue("role").get());
        NObjectElement part = content.getArray("parts").get().children().get(0).asObject().get();
        NObjectElement response = part.getObject("functionResponse").get();
        Assertions.assertEquals("search", response.getStringValue("name").get(),
                "Gemini matches a response to its call by name, not by id");
        Assertions.assertEquals("the answer", response.getObject("response").get()
                .getStringValue("result").get());
    }

    @Test
    public void assistantToolCallsBecomeFunctionCallParts() {
        NaruToolCall call = new NaruToolCall("c1", "search",
                Collections.singletonMap("q", "naru"));
        NObjectElement body = serialize(new NaruModelRequest(
                Collections.singletonList(NaruMessage.assistantWithToolCalls("", List.of(call))),
                Collections.emptyMap()));

        NObjectElement part = body.getArray("contents").get().children().get(0).asObject().get()
                .getArray("parts").get().children().get(0).asObject().get();
        NObjectElement fnCall = part.getObject("functionCall").get();
        Assertions.assertEquals("search", fnCall.getStringValue("name").get());
        Assertions.assertEquals("naru", fnCall.getObject("args").get().getStringValue("q").get());
    }

    @Test
    public void emptyAssistantTurnStillProducesAValidContent() {
        NObjectElement body = serialize(new NaruModelRequest(
                Collections.singletonList(NaruMessage.assistant("")), Collections.emptyMap()));
        NArrayElement parts = body.getArray("contents").get().children().get(0).asObject().get()
                .getArray("parts").get();
        Assertions.assertFalse(parts.children().isEmpty(),
                "Gemini rejects a contents entry with no parts");
    }

    @Test
    public void generationConfigIsAlwaysPresent() {
        NObjectElement body = serialize(new NaruModelRequest(
                Collections.singletonList(NaruMessage.user("hi")), Collections.emptyMap()));
        Assertions.assertTrue(body.get("generationConfig").isPresent());
    }

    // ── response parsing ───────────────────────────────────────────────────

    private static NaruResponse parseWith(String json) {
        NElementReader reader = NElementReader.ofJson();
        reader.mapperStore().setDeserializer(NaruResponse.class, new NaruGeminiNativeResponseParser());
        return reader.read(json, NaruResponse.class);
    }

    @Test
    public void parsesTextAndFinishReason() {
        NaruResponse r = parseWith("{\n" +
                "  \"candidates\": [{\n" +
                "    \"content\": {\"role\": \"model\", \"parts\": [{\"text\": \"hi there\"}]},\n" +
                "    \"finishReason\": \"STOP\"\n" +
                "  }],\n" +
                "  \"usageMetadata\": {\"promptTokenCount\": 10, \"candidatesTokenCount\": 4, \"totalTokenCount\": 14}\n" +
                "}");
        Assertions.assertEquals("hi there", r.getMessage().getContent());
        Assertions.assertEquals("STOP", r.getStopReason());
        Assertions.assertTrue(r.isDone());
        Assertions.assertEquals(10, r.getPromptTokens());
        Assertions.assertEquals(4, r.getEvalTokens());
    }

    @Test
    public void parsesFunctionCall() {
        NaruResponse r = parseWith("{\n" +
                "  \"candidates\": [{\n" +
                "    \"content\": {\"role\": \"model\", \"parts\": [\n" +
                "      {\"functionCall\": {\"name\": \"search\", \"args\": {\"q\": \"naru\"}}}\n" +
                "    ]},\n" +
                "    \"finishReason\": \"STOP\"\n" +
                "  }]\n" +
                "}");
        Assertions.assertTrue(r.hasToolCalls());
        List<NaruToolCall> calls = r.getMessage().getToolCalls();
        Assertions.assertEquals(1, calls.size());
        Assertions.assertEquals("search", calls.get(0).getName());
        Assertions.assertEquals("naru", calls.get(0).getArguments().get("q"));
    }

    @Test
    public void cachedTokensAreABreakdownOfThePromptTotal() {
        NaruResponse r = parseWith("{\n" +
                "  \"candidates\": [{\"content\": {\"role\": \"model\", \"parts\": [{\"text\": \"x\"}]},\n" +
                "    \"finishReason\": \"STOP\"}],\n" +
                "  \"usageMetadata\": {\"promptTokenCount\": 1000, \"candidatesTokenCount\": 20,\n" +
                "    \"totalTokenCount\": 1020, \"cachedContentTokenCount\": 800}\n" +
                "}");
        Assertions.assertEquals(1000, r.getPromptTokens());
        Assertions.assertEquals(800, r.getCacheReadTokens());
        Assertions.assertEquals(200, r.getCacheWriteTokens());
    }

    @Test
    public void noCachedTokenFieldMeansNoCacheAccounting() {
        NaruResponse r = parseWith("{\n" +
                "  \"candidates\": [{\"content\": {\"role\": \"model\", \"parts\": [{\"text\": \"x\"}]},\n" +
                "    \"finishReason\": \"STOP\"}],\n" +
                "  \"usageMetadata\": {\"promptTokenCount\": 100, \"candidatesTokenCount\": 5, \"totalTokenCount\": 105}\n" +
                "}");
        Assertions.assertFalse(r.hasCacheAccounting());
    }

    @Test
    public void geminiIsRegisteredAsAProtocolType() {
        Assertions.assertTrue(NaruModelProtocolTypes.of("gemini").isPresent());
        Assertions.assertTrue(NaruModelProtocolTypes.of("GEMINI").isPresent(),
                "type ids are case-insensitive");
        Assertions.assertTrue(NaruModelProtocolTypes.names().contains("gemini"));
    }

    @Test
    public void emptyResponseDoesNotThrow() {
        NaruResponse r = parseWith("{}");
        Assertions.assertNotNull(r);
    }
}
