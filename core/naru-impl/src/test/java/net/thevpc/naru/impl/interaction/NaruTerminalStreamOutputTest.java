package net.thevpc.naru.impl.interaction;

import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.nuts.io.NMemoryPrintStream;
import net.thevpc.nuts.io.NTerminal;
import net.thevpc.nuts.text.NMsg;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The live half of streaming: a fragment must appear on the terminal before the work
 * that produced it is finished.
 *
 * <p>A batched answer is one call to {@code write} and any number of newlines. A
 * streamed one is many calls with no newlines between them, so a bug here does not
 * crash anything -- it produces a screen full of one-token lines, or an answer that
 * runs into the next log line, and both look like "streaming doesn't work". These
 * tests pin the exact bytes, including the absence of the newlines that would give
 * the game away.
 */
public class NaruTerminalStreamOutputTest {

    private NTerminal terminal;
    private NaruTerminalInteraction interaction;

    @org.junit.jupiter.api.BeforeAll
    public static void setUpWorkspace() {
        try {
            net.thevpc.nuts.core.NWorkspace ws =
                    net.thevpc.nuts.Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            try {
                net.thevpc.nuts.core.NWorkspace ws = net.thevpc.nuts.Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private String out() {
        return new String(((NMemoryPrintStream) terminal.out()).bytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private void setUp() {
        terminal = NTerminal.ofMem();
        interaction = new NaruTerminalInteraction(terminal, null);
    }

    private static NMsg msg(String s) {
        return NMsg.ofC("%s", s);
    }

    @Test
    public void fragmentsAreJoinedWithNoNewlineBetweenThem() {
        setUp();
        interaction.writeStream(NaruLogMode.MODEL_RESPONSE, msg("Hello"), false);
        interaction.writeStream(NaruLogMode.MODEL_RESPONSE, msg(", "), false);
        interaction.writeStream(NaruLogMode.MODEL_RESPONSE, msg("world"), true);
        String text = out();
        Assertions.assertTrue(text.contains("Hello, world"),
                "fragments must read as one continuous answer, got: " + visible(text));
        Assertions.assertEquals(1, text.chars().filter(c -> c == '\n').count(),
                "only the end of the stream may break the line, got: " + visible(text));
    }

    @Test
    public void aFragmentIsVisibleBeforeTheStreamEnds() {
        setUp();
        interaction.writeStream(NaruLogMode.MODEL_RESPONSE, msg("first"), false);
        String midway = out();
        Assertions.assertTrue(midway.contains("first"),
                "a fragment must be drawn immediately, not held until the end: " + visible(midway));
        Assertions.assertFalse(midway.contains("\n"),
                "an unfinished stream must not close its line: " + visible(midway));
    }

    @Test
    public void aClosedStreamLeavesTheCursorOnAFreshLine() {
        setUp();
        interaction.writeStream(NaruLogMode.MODEL_RESPONSE, msg("done"), true);
        String text = out();
        Assertions.assertTrue(text.endsWith("\n"),
                "the line must be terminated so the next output does not continue it: " + visible(text));
    }

    @Test
    public void anEmptyStreamDrawsNothing() {
        setUp();
        interaction.writeStream(NaruLogMode.MODEL_RESPONSE, msg(""), true);
        Assertions.assertEquals("", out().replace("\n", "").trim(),
                "a turn that produced no answer must not leave a stray marker line");
    }

    @Test
    public void anEmbeddedNewlineRepeatsTheMarkerOnTheContinuationLine() {
        setUp();
        interaction.writeStream(NaruLogMode.MODEL_RESPONSE, msg("one\ntwo"), true);
        String text = out();
        Assertions.assertTrue(text.contains("one"));
        Assertions.assertTrue(text.contains("two"));
        Assertions.assertEquals(2, occurrences(text, "\u258C"),
                "both physical lines need the marker, or the block loses its edge: " + visible(text));
    }

    @Test
    public void anUnrelatedLogLineClosesTheOpenFragmentFirst() {
        setUp();
        interaction.writeStream(NaruLogMode.MODEL_RESPONSE, msg("half a sen"), false);
        interaction.write(NaruLogMode.PROGRESS, msg("something else"));
        interaction.writeStream(NaruLogMode.MODEL_RESPONSE, msg("tence"), true);
        String text = visible(out());
        Assertions.assertTrue(text.contains("half a sen"));
        Assertions.assertTrue(text.contains("tence"));
        Assertions.assertTrue(text.indexOf("half a sen") < text.indexOf("something else"),
                "the fragment must be closed before the log line: " + text);
        Assertions.assertTrue(text.indexOf("something else") < text.lastIndexOf("tence"),
                "the fragment after the log line must be a new line, not a continuation: " + text);
    }

    @Test
    public void switchingFromThinkingToAnswerClosesTheThinkingLine() {
        setUp();
        interaction.writeStream(NaruLogMode.MODEL_THINKING, msg("reasoning"), false);
        interaction.writeStream(NaruLogMode.MODEL_RESPONSE, msg("answer"), true);
        String text = out();
        Assertions.assertTrue(text.contains("reasoning"));
        Assertions.assertTrue(text.contains("answer"));
        Assertions.assertTrue(text.indexOf("reasoning") < text.indexOf("answer"));
        Assertions.assertTrue(visible(text).contains("reasoning\n"),
                "reasoning must not run into the answer: " + visible(text));
    }

    @Test
    public void aShutdownMidStreamStillReportsWhatWasSaid() {
        setUp();
        interaction.writeStream(NaruLogMode.MODEL_RESPONSE, msg("half"), false);
        interaction.close();
        String text = visible(out());
        Assertions.assertTrue(text.contains("half"),
                "closing must not throw away text the model already produced: " + text);
    }

    @Test
    public void thinkingAndAnswerStayInSeparateBuffersForAHeadlessHost() {
        // the headless path joins fragments instead of drawing them, and must not
        // glue a model's reasoning onto the front of its reply
        java.util.List<String> seen = new java.util.ArrayList<>();
        NaruStreamInteraction stream = new NaruStreamInteraction(output -> {
            seen.add(output.message().toString());
        });
        stream.writeStream(NaruLogMode.MODEL_THINKING, msg("because "), false);
        stream.writeStream(NaruLogMode.MODEL_THINKING, msg("reasons"), true);
        stream.writeStream(NaruLogMode.MODEL_RESPONSE, msg("42"), true);
        Assertions.assertTrue(seen.contains("because reasons"), "got: " + seen);
        Assertions.assertTrue(seen.contains("42"), "got: " + seen);
        Assertions.assertFalse(seen.contains("because reasons42"),
                "reasoning and answer must not be merged into one message: " + seen);
    }

    private static String visible(String raw) {
        return raw.replace("\u001B", "<ESC>");
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
