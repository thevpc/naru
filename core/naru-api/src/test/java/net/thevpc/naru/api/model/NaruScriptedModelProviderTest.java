package net.thevpc.naru.api.model;

import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Every rule belongs to its instance: two scripted providers in one JVM must not answer for
 * each other, which a static rule list could not promise.
 */
public class NaruScriptedModelProviderTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NaruResponse ask(NaruModelProvider provider, List<NaruMessage> messages, List<NaruToolDefinition> tools) {
        NaruModelContext context = NaruModelContext.standalone();
        NaruModelConfig model = new NaruModelConfig(provider.name(), NaruScriptedModelProvider.DEFAULT_MODEL);
        return provider.getProtocol(model, context).get()
                .chat(new NaruModelRequest(messages, tools, Map.of()), context);
    }

    private static String reply(NaruModelProvider provider, String question) {
        return ask(provider, List.of(NaruMessage.user(question)), List.of()).getMessage().getFilteredContent();
    }

    @Test
    public void rulesBelongToTheirInstance() {
        NaruScriptedModelProvider tunis = new NaruScriptedModelProvider("a").when("capital", "Tunis.");
        NaruScriptedModelProvider paris = new NaruScriptedModelProvider("b").when("capital", "Paris.");

        Assertions.assertEquals("Tunis.", reply(tunis, "What is the capital?"));
        Assertions.assertEquals("Paris.", reply(paris, "What is the CAPITAL?"));
    }

    @Test
    public void theFirstMatchingRuleWinsAndOtherwiseAnswersTheRest() {
        NaruScriptedModelProvider model = new NaruScriptedModelProvider()
                .when("hello", "first")
                .when("hello there", "second")
                .otherwise(r -> NaruMessage.assistant("no rule for: " + NaruScriptedModelProvider.lastUserText(r)));

        Assertions.assertEquals("first", reply(model, "hello there"));
        Assertions.assertEquals("no rule for: bye", reply(model, "bye"));
        Assertions.assertEquals(List.of(NaruScriptedModelProvider.DEFAULT_MODEL),
                model.findModelIds(NaruModelContext.standalone()));
    }

    @Test
    public void callToolAsksForTheToolThenSummarizesItsResult() {
        NaruScriptedModelProvider model = new NaruScriptedModelProvider()
                .callTool("where is my order", "lookup_order",
                        Map.of("order_id", NElement.ofString("A-1042")),
                        result -> "Status: " + result);
        List<NaruToolDefinition> tools = List.of(new NaruToolDefinition("lookup_order", "Look an order up."));
        NaruMessage question = NaruMessage.user("Where is my order?");

        NaruResponse first = ask(model, List.of(question), tools);
        Assertions.assertTrue(first.getMessage().hasToolCalls());
        NaruToolCall call = first.getMessage().getToolCalls().get(0);
        Assertions.assertEquals("lookup_order", call.getName());
        Assertions.assertEquals("tool_calls", first.getStopReason());

        NaruResponse second = ask(model, List.of(question, first.getMessage(),
                NaruMessage.tool("lookup_order", call.getId(), "shipped")), tools);
        Assertions.assertEquals("Status: shipped", second.getMessage().getFilteredContent());

        // not offered the tool, the rule steps aside and the fallback answers
        Assertions.assertFalse(ask(model, List.of(question), List.of()).getMessage().hasToolCalls());
    }
}
