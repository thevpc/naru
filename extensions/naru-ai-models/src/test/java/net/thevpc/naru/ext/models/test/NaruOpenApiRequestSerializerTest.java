package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.ext.models.openapi.NaruOpenApiRequestSerializer;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NObjectElement;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * Wire-protocol tests for the OpenAI-compatible ({@code openapi}) request shape,
 * exercising the same coverage as {@link NaruAnthropicRequestSerializerTest}.
 */
public class NaruOpenApiRequestSerializerTest {

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

    @Test
    public void testBasicShapeWithSystemMessage() {
        NaruOpenApiRequestSerializer serializer = new NaruOpenApiRequestSerializer();
        List<NaruMessage> messages = Arrays.asList(
                NaruMessage.system("You are a helpful assistant."),
                NaruMessage.user("Hello there"),
                NaruMessage.assistant("Hi!")
        );
        NaruModelRequest request = new NaruModelRequest(messages, Collections.emptyMap());
        NaruModelConfig model = new NaruModelConfig("custom-llm", "llama-3.1-8b");

        NObjectElement body = serializer.serialize(request, model, null).asObject().get();

        Assertions.assertEquals("llama-3.1-8b", body.getStringValue("model").get());
        Assertions.assertEquals(false, body.getBooleanValue("stream").get());
        // system message stays INSIDE messages[] (OpenAI shape)
        List<NElement> msgs = body.getArray("messages").get().children();
        Assertions.assertEquals(3, msgs.size());
        Assertions.assertEquals("system", msgs.get(0).asObject().get().getStringValue("role").get());
        Assertions.assertEquals("You are a helpful assistant.",
                msgs.get(0).asObject().get().getStringValue("content").get());
        Assertions.assertEquals("user", msgs.get(1).asObject().get().getStringValue("role").get());
        Assertions.assertEquals("assistant", msgs.get(2).asObject().get().getStringValue("role").get());
        // must NOT be hoisted to a top-level "system" key (unlike anthropic)
        Assertions.assertFalse(body.get("system").isPresent());
    }

    @Test
    public void testHyperparametersAtRoot() {
        NaruOpenApiRequestSerializer serializer = new NaruOpenApiRequestSerializer();
        NaruModelRequest request = new NaruModelRequest(
                Collections.singletonList(NaruMessage.user("hi")), Collections.emptyMap());
        NaruModelConfig model = new NaruModelConfig("custom-llm", "llama-3.1-8b")
                .withContextLength(8192L)
                .withTemperature(0.7f)
                .withNucleusThreshold(0.9f)
                .withCandidateCount(1)
                .withMaxTokens(512)
                .withStop(Collections.singletonList("END"));

        NObjectElement body = serializer.serialize(request, model, null).asObject().get();

        Assertions.assertEquals(0.7f, body.getFloatValue("temperature").get());
        Assertions.assertEquals(0.9f, body.getFloatValue("top_p").get()); // nucleusThreshold -> top_p
        Assertions.assertEquals(512, body.getIntValue("max_tokens").get());
        Assertions.assertEquals(Collections.singletonList("END"), body.getArray("stop").get().children()
                .stream().map(x -> x.asStringValue().get()).toList());
    }

    @Test
    public void testToolCallAndToolResultBlocks() {
        NaruOpenApiRequestSerializer serializer = new NaruOpenApiRequestSerializer();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("q", "what is naru");
        NaruToolCall call = new NaruToolCall("call_01ABC", "web_search", args);

        List<NaruMessage> messages = Arrays.asList(
                NaruMessage.user("search the web"),
                NaruMessage.assistantWithToolCalls("", Collections.singletonList(call)),
                NaruMessage.tool("web_search", "call_01ABC", "naru is a coding agent")
        );
        NaruModelRequest request = new NaruModelRequest(messages, Collections.emptyMap());
        NaruModelConfig model = new NaruModelConfig("custom-llm", "llama-3.1-8b");

        NObjectElement body = serializer.serialize(request, model, null).asObject().get();
        List<NElement> msgs = body.getArray("messages").get().children();

        // assistant message carries tool_calls[]
        NObjectElement assistantMsg = msgs.get(1).asObject().get();
        NObjectElement toolCall = assistantMsg.getArray("tool_calls").get().children().get(0).asObject().get();
        Assertions.assertEquals("call_01ABC", toolCall.getStringValue("id").get());
        Assertions.assertEquals("function", toolCall.getStringValue("type").get());
        NObjectElement fn = toolCall.getObject("function").get();
        Assertions.assertEquals("web_search", fn.getStringValue("name").get());
        // arguments must be a JSON-escaped string whose content parses to the args map
        Map<?, ?> parsedArgs = NElementReader.ofJson().read(fn.getStringValue("arguments").get(), Map.class);
        Assertions.assertEquals("what is naru", parsedArgs.get("q"));

        // tool message carries tool_call_id + name
        NObjectElement toolMsg = msgs.get(2).asObject().get();
        Assertions.assertEquals("tool", toolMsg.getStringValue("role").get());
        Assertions.assertEquals("call_01ABC", toolMsg.getStringValue("tool_call_id").get());
        Assertions.assertEquals("web_search", toolMsg.getStringValue("name").get());
        Assertions.assertEquals("naru is a coding agent", toolMsg.getStringValue("content").get());
    }

    @Test
    public void testToolsSchema() {
        NaruOpenApiRequestSerializer serializer = new NaruOpenApiRequestSerializer();
        List<NaruToolDefinition> tools = Collections.singletonList(
                new NaruToolDefinitionFunction("web_search",
                        "Search the web",
                        NaruToolParameter.string("q", "query", true).build())
        );
        NaruModelRequest request = new NaruModelRequest(
                Collections.singletonList(NaruMessage.user("search")), tools, Collections.emptyMap());
        NaruModelConfig model = new NaruModelConfig("custom-llm", "llama-3.1-8b");

        NObjectElement body = serializer.serialize(request, model, null).asObject().get();
        NObjectElement tool = body.getArray("tools").get().children().get(0).asObject().get();
        Assertions.assertEquals("function", tool.getStringValue("type").get());
        NObjectElement functionBlock = tool.getObject("function").get();
        Assertions.assertEquals("web_search", functionBlock.getStringValue("name").get());
        Assertions.assertEquals("Search the web", functionBlock.getStringValue("description").get());
        NObjectElement params = functionBlock.getObject("parameters").get();
        Assertions.assertEquals("object", params.getStringValue("type").get());
        Assertions.assertEquals("string",
                params.getObject("properties").get().get("q").get().asObject().get().getStringValue("type").get());
        Assertions.assertEquals("q", params.getArray("required").get().children().get(0).asStringValue().get());
    }

    @Test
    public void testImageBlock() {
        NaruOpenApiRequestSerializer serializer = new NaruOpenApiRequestSerializer();
        NaruMessage user = NaruMessage.userWithImages("what is this?", Collections.singletonList("QUJD"));
        NaruModelRequest request = new NaruModelRequest(
                Collections.singletonList(user), Collections.emptyMap());
        NaruModelConfig model = new NaruModelConfig("custom-llm", "llava-v1.6");

        NObjectElement body = serializer.serialize(request, model, null).asObject().get();
        NObjectElement userMsg = body.getArray("messages").get().children().get(0).asObject().get();
        List<NElement> content = userMsg.getArray("content").get().children();
        Assertions.assertEquals(2, content.size());
        Assertions.assertEquals("text", content.get(0).asObject().get().getStringValue("type").get());
        NObjectElement image = content.get(1).asObject().get();
        Assertions.assertEquals("image_url", image.getStringValue("type").get());
        NObjectElement imageUrl = image.getObject("image_url").get();
        Assertions.assertEquals("data:image/jpeg;base64,QUJD", imageUrl.getStringValue("url").get());
    }
}