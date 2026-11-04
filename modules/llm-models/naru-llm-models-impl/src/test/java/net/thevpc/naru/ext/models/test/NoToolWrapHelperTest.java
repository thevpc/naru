package net.thevpc.naru.ext.models.test;

import net.thevpc.nuts.text.NMsg;
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
        Assertions.assertFalse(unwrapped.getMessage().getContent().toString().contains("tool_call"),
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
        Assertions.assertEquals(NMsg.ofP("just an answer"), unwrapped.getMessage().getContent());
    }

    @Test
    public void aBareJsonCallIsRecoveredWhenTheSentinelIsIgnored() {
        // qwen2.5-coder ignores the requested tags and echoes the object as-is
        NaruResponse response = new NaruResponse();
        response.setMessage(NaruMessage.assistant(
                "{\"name\": \"file_grep\", \"arguments\": {\"pattern\": \"poem\"}}"));

        NaruResponse unwrapped = NoToolWrapHelper.unwrapResponse(response, NoToolWrapHelper.TOOL_CALL_SEP, null);

        Assertions.assertTrue(unwrapped.hasToolCalls(),
                "a bare tool-call object must become a call, not be shown as the answer");
        Assertions.assertEquals("file_grep", unwrapped.getMessage().getToolCalls().get(0).getName());
        Assertions.assertEquals("poem", unwrapped.getMessage().getToolCalls().get(0).getArguments().get("pattern"));
        Assertions.assertNull(unwrapped.getMessage().getContent(),
                "the recovered object must not linger as answer text");
    }

    @Test
    public void aBareJsonCallInTheToolArgsSpellingIsRecoveredToo() {
        NaruResponse response = new NaruResponse();
        response.setMessage(NaruMessage.assistant(
                "{\"tool\": \"bash\", \"args\": {\"command\": \"ls\"}}"));

        NaruResponse unwrapped = NoToolWrapHelper.unwrapResponse(response, NoToolWrapHelper.TOOL_CALL_SEP, null);

        Assertions.assertTrue(unwrapped.hasToolCalls());
        Assertions.assertEquals("bash", unwrapped.getMessage().getToolCalls().get(0).getName());
        Assertions.assertEquals("ls", unwrapped.getMessage().getToolCalls().get(0).getArguments().get("command"));
    }

    @Test
    public void aPlainJsonAnswerIsNotMistakenForAToolCall() {
        NaruResponse response = new NaruResponse();
        response.setMessage(NaruMessage.assistant("{\"poem\": \"roses are red\", \"lines\": 4}"));

        NaruResponse unwrapped = NoToolWrapHelper.unwrapResponse(response, NoToolWrapHelper.TOOL_CALL_SEP, null);

        Assertions.assertFalse(unwrapped.hasToolCalls());
        Assertions.assertEquals(NMsg.ofP("{\"poem\": \"roses are red\", \"lines\": 4}"),
    unwrapped.getMessage().getContent());
    }

    @Test
    public void severalBareJsonCallsInARowAreAllRecovered() {
        // the real qwen2.5-coder output: two calls, one object per line, no tags
        NaruResponse response = new NaruResponse();
        response.setMessage(NaruMessage.assistant(
                "{\"name\": \"pwd\", \"arguments\": {}}\n"
                        + "{\"name\": \"folder_find\", \"arguments\": {\"path\": \".\", \"recursive\": true}}"));

        NaruResponse unwrapped = NoToolWrapHelper.unwrapResponse(response, NoToolWrapHelper.TOOL_CALL_SEP, null);

        Assertions.assertTrue(unwrapped.hasToolCalls());
        Assertions.assertEquals(2, unwrapped.getMessage().getToolCalls().size(),
                "both bare objects must become calls");
        Assertions.assertEquals("pwd", unwrapped.getMessage().getToolCalls().get(0).getName());
        Assertions.assertEquals("folder_find", unwrapped.getMessage().getToolCalls().get(1).getName());
        Assertions.assertEquals(true,
                unwrapped.getMessage().getToolCalls().get(1).getArguments().get("recursive"));
        Assertions.assertNull(unwrapped.getMessage().getContent());
    }

    @Test
    public void bracesInsideArgumentsDoNotConfuseTheScanner() {
        NaruResponse response = new NaruResponse();
        response.setMessage(NaruMessage.assistant(
                "{\"name\": \"folder_find\", \"arguments\": {\"include_filename\": \"*.java,*.py\", \"nested\": {\"a\": 1}}}"));

        NaruResponse unwrapped = NoToolWrapHelper.unwrapResponse(response, NoToolWrapHelper.TOOL_CALL_SEP, null);

        Assertions.assertTrue(unwrapped.hasToolCalls());
        Assertions.assertEquals("*.java,*.py",
                unwrapped.getMessage().getToolCalls().get(0).getArguments().get("include_filename"));
    }

    @Test
    public void aBareCallAfterProseKeepsTheProse() {
        NaruResponse response = new NaruResponse();
        response.setMessage(NaruMessage.assistant(
                "Let me look.\n{\"name\": \"pwd\", \"arguments\": {}}"));

        NaruResponse unwrapped = NoToolWrapHelper.unwrapResponse(response, NoToolWrapHelper.TOOL_CALL_SEP, null);

        Assertions.assertTrue(unwrapped.hasToolCalls());
        Assertions.assertEquals(NMsg.ofP("Let me look."), unwrapped.getMessage().getContent(),
                "the prose around the recovered call must be kept");
    }
}