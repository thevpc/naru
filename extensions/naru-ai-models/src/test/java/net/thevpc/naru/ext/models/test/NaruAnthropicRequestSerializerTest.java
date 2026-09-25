package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.ext.models.anthropic.NaruAnthropicRequestSerializer;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

public class NaruAnthropicRequestSerializerTest {

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
    public void testSystemMessagesHoistedToTopLevel() {
        NaruAnthropicRequestSerializer serializer = new NaruAnthropicRequestSerializer();
        List<NaruMessage> messages = Arrays.asList(
                NaruMessage.system("You are a helpful assistant."),
                NaruMessage.user("Hello there"),
                NaruMessage.assistant("Hi!")
        );
        NaruModelRequest request = new NaruModelRequest(messages, Collections.emptyMap());
        NaruModelConfig model = new NaruModelConfig("anthropic-compat", "claude-sonnet-4");

        NObjectElement body = serializer.serialize(request, model, null).asObject().get();

        Assertions.assertEquals("claude-sonnet-4", body.getStringValue("model").get());
        // system message must NOT appear in messages[]
        Assertions.assertEquals("You are a helpful assistant.", body.getStringValue("system").get());
        List<NElement> msgs = body.getArray("messages").get().children();
        Assertions.assertEquals(2, msgs.size());
        Assertions.assertEquals("user", msgs.get(0).asObject().get().getStringValue("role").get());
        Assertions.assertEquals("assistant", msgs.get(1).asObject().get().getStringValue("role").get());
    }

    @Test
    public void testMaxTokensRequired() {
        NaruAnthropicRequestSerializer serializer = new NaruAnthropicRequestSerializer();
        NaruModelRequest request = new NaruModelRequest(
                Collections.singletonList(NaruMessage.user("hi")), Collections.emptyMap());
        NaruModelConfig model = new NaruModelConfig(null, "anthropic-compat", "claude-sonnet-4",
                null, null, null, null, null, Collections.emptyList());

        NObjectElement body = serializer.serialize(request, model, null).asObject().get();
        Assertions.assertEquals(4096, body.getIntValue("max_tokens").get());
    }

    @Test
    public void testToolCallAndToolResultBlocks() {
        NaruAnthropicRequestSerializer serializer = new NaruAnthropicRequestSerializer();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("q", "what is naru");
        NaruToolCall call = new NaruToolCall("toolu_01ABC", "web_search", args);

        List<NaruMessage> messages = Arrays.asList(
                NaruMessage.user("search the web"),
                NaruMessage.assistantWithToolCalls("", Collections.singletonList(call)),
                NaruMessage.tool("web_search", "toolu_01ABC", "naru is a coding agent")
        );
        NaruModelRequest request = new NaruModelRequest(messages, Collections.emptyMap());
        NaruModelConfig model = new NaruModelConfig("anthropic-compat", "claude-sonnet-4");

        NObjectElement body = serializer.serialize(request, model, null).asObject().get();
        List<NElement> msgs = body.getArray("messages").get().children();

        // assistant message carries tool_use block
        NObjectElement assistantMsg = msgs.get(1).asObject().get();
        List<NElement> assistantContent = assistantMsg.getArray("content").get().children();
        NObjectElement toolUse = assistantContent.get(0).asObject().get();
        Assertions.assertEquals("tool_use", toolUse.getStringValue("type").get());
        Assertions.assertEquals("toolu_01ABC", toolUse.getStringValue("id").get());
        Assertions.assertEquals("web_search", toolUse.getStringValue("name").get());
        Assertions.assertEquals("what is naru", toolUse.getObject("input").get().getStringValue("q").get());

        // tool message becomes a user message with tool_result block
        NObjectElement toolMsg = msgs.get(2).asObject().get();
        Assertions.assertEquals("user", toolMsg.getStringValue("role").get());
        NObjectElement toolResult = toolMsg.getArray("content").get().children().get(0).asObject().get();
        Assertions.assertEquals("tool_result", toolResult.getStringValue("type").get());
        Assertions.assertEquals("toolu_01ABC", toolResult.getStringValue("tool_use_id").get());
        Assertions.assertEquals("naru is a coding agent", toolResult.getStringValue("content").get());
    }

    @Test
    public void testToolsSchema() {
        NaruAnthropicRequestSerializer serializer = new NaruAnthropicRequestSerializer();
        List<NaruToolDefinition> tools = Collections.singletonList(
                new NaruToolDefinitionFunction("web_search",
                        "Search the web",
                        NaruToolParameter.string("q", "query", true).build())
        );
        NaruModelRequest request = new NaruModelRequest(
                Collections.singletonList(NaruMessage.user("search")), tools, Collections.emptyMap());
        NaruModelConfig model = new NaruModelConfig("anthropic-compat", "claude-sonnet-4");

        NObjectElement body = serializer.serialize(request, model, null).asObject().get();
        NObjectElement tool = body.getArray("tools").get().children().get(0).asObject().get();
        Assertions.assertEquals("web_search", tool.getStringValue("name").get());
        NObjectElement inputSchema = tool.getObject("input_schema").get();
        Assertions.assertEquals("object", inputSchema.getStringValue("type").get());
        Assertions.assertEquals(true,
                inputSchema.getObject("properties").get().get("q").get().asObject().get().getStringValue("type").get().equals("string"));
        Assertions.assertEquals("q", inputSchema.getArray("required").get().children().get(0).asStringValue().get());
    }

    @Test
    public void testImageBlock() {
        NaruAnthropicRequestSerializer serializer = new NaruAnthropicRequestSerializer();
        NaruMessage user = NaruMessage.userWithImages("what is this?", Collections.singletonList("QUJD"));
        NaruModelRequest request = new NaruModelRequest(
                Collections.singletonList(user), Collections.emptyMap());
        NaruModelConfig model = new NaruModelConfig("anthropic-compat", "claude-sonnet-4");

        NObjectElement body = serializer.serialize(request, model, null).asObject().get();
        NObjectElement userMsg = body.getArray("messages").get().children().get(0).asObject().get();
        List<NElement> content = userMsg.getArray("content").get().children();
        Assertions.assertEquals(2, content.size());
        NObjectElement image = content.get(1).asObject().get();
        Assertions.assertEquals("image", image.getStringValue("type").get());
        NObjectElement source = image.getObject("source").get();
        Assertions.assertEquals("base64", source.getStringValue("type").get());
        Assertions.assertEquals("image/jpeg", source.getStringValue("media_type").get());
        Assertions.assertEquals("QUJD", source.getStringValue("data").get());
    }
}