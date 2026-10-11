package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.ext.models.NoToolWrapHelper;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * The emulated tool-call helper: separator resolution from the per-model
 * {@code .naru/models/*.md} front matter, and unwrapping of a response whose
 * tool call arrived inside those tags.
 */
public class NoToolWrapHelperTest {

    @BeforeAll
    public static void setUp() {
        Nuts.require();
    }

    @Test
    public void configuredSeparatorsWinOverTheDefaults() {
        Map<String, NElement> env = new HashMap<>();
        env.put("tool_call_start", NElement.ofString("<tool_call>"));
        env.put("tool_call_end", NElement.ofString("</tool_call>"));
        env.put("tool_result_start", NElement.ofString("<tool_result>"));
        env.put("tool_result_end", NElement.ofString("</tool_result>"));

        NoToolWrapHelper.Separators calls = NoToolWrapHelper.forToolCalls(env);
        Assertions.assertEquals("<tool_call>", calls.open());
        Assertions.assertEquals("</tool_call>", calls.close());

        NoToolWrapHelper.Separators results = NoToolWrapHelper.forToolResults(env);
        Assertions.assertEquals("<tool_result>", results.open());
        Assertions.assertEquals("</tool_result>", results.close());
    }

    @Test
    public void absentEnvKeepsTheDefaults() {
        NoToolWrapHelper.Separators calls = NoToolWrapHelper.forToolCalls(new HashMap<>());
        Assertions.assertEquals(NoToolWrapHelper.TOOL_CALL_SEP.open(), calls.open());
        Assertions.assertEquals(NoToolWrapHelper.TOOL_CALL_SEP.close(), calls.close());
    }

    @Test
    public void aHalfConfiguredPairFallsBackToTheDefault() {
        // an opener with no closer would never match; better to use the known pair
        Map<String, NElement> env = new HashMap<>();
        env.put("tool_call_start", NElement.ofString("<tool_call>"));
        NoToolWrapHelper.Separators calls = NoToolWrapHelper.forToolCalls(env);
        Assertions.assertEquals(NoToolWrapHelper.TOOL_CALL_SEP.open(), calls.open());
    }

    @Test
    public void unwrapExtractsTheCallAndStripsTheTags() {
        NaruResponse response = new NaruResponse();
        response.setMessage(NaruMessage.assistant(
                "before\n<|tool_call|>\n{\"tool\":\"file_read\",\"args\":{\"path\":\"/x\"}}\n<|end_tool_call|>\nafter"));

        NaruResponse unwrapped = NoToolWrapHelper.unwrapResponse(response, NoToolWrapHelper.TOOL_CALL_SEP, null);

        Assertions.assertTrue(unwrapped.hasToolCalls());
        Assertions.assertEquals("file_read", unwrapped.getMessage().getToolCalls().get(0).getName());
        Assertions.assertEquals("/x", unwrapped.getMessage().getToolCalls().get(0).getArguments().get("path"));
        Assertions.assertFalse(unwrapped.getMessage().getContent().contains("tool_call"),
                "the emulated block must be stripped from the answer text");
    }

    @Test
    public void unwrapHonoursConfiguredSeparators() {
        Map<String, NElement> env = new HashMap<>();
        env.put("tool_call_start", NElement.ofString("<tool_call>"));
        env.put("tool_call_end", NElement.ofString("</tool_call>"));
        NaruResponse response = new NaruResponse();
        response.setMessage(NaruMessage.assistant(
                "<tool_call>\n{\"tool\":\"bash\",\"args\":{\"command\":\"ls\"}}\n</tool_call>"));

        NaruResponse unwrapped = NoToolWrapHelper.unwrapResponse(response,
                NoToolWrapHelper.forToolCalls(env), null);

        Assertions.assertTrue(unwrapped.hasToolCalls());
        Assertions.assertEquals("bash", unwrapped.getMessage().getToolCalls().get(0).getName());
    }

    @Test
    public void unwrapLeavesPlainTextAlone() {
        NaruResponse response = new NaruResponse();
        response.setMessage(NaruMessage.assistant("just an answer"));

        NaruResponse unwrapped = NoToolWrapHelper.unwrapResponse(response, NoToolWrapHelper.TOOL_CALL_SEP, null);

        Assertions.assertFalse(unwrapped.hasToolCalls());
        Assertions.assertEquals("just an answer", unwrapped.getMessage().getContent());
    }
}