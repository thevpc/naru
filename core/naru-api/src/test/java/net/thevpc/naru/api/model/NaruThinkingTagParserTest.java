package net.thevpc.naru.api.model;

import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.Nuts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tag parser is the one component that has to be right about something it
 * cannot see: a delimiter split across two network packets. These tests feed it
 * deliberately hostile splits, including one character at a time.
 */
public class NaruThinkingTagParserTest {

    @BeforeAll
    public static void setUpWorkspace() {
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

    private static List<String> kinds(List<NaruStreamChunk> chunks) {
        List<String> r = new ArrayList<>();
        for (NaruStreamChunk c : chunks) {
            r.add(c.kind().name());
        }
        return r;
    }

    private static String join(List<NaruStreamChunk> chunks, NaruChunkKind kind) {
        StringBuilder sb = new StringBuilder();
        for (NaruStreamChunk c : chunks) {
            if (c.kind() == kind) {
                sb.append(c.text());
            }
        }
        return sb.toString();
    }

    private static List<NaruStreamChunk> parseAll(String... chunks) {
        NaruThinkingTagParser p = new NaruThinkingTagParser();
        List<NaruStreamChunk> all = new ArrayList<>();
        for (String c : chunks) {
            all.addAll(p.feed(c, "test"));
        }
        all.addAll(p.flush("test"));
        return all;
    }

    /**
     * The invariant that actually matters when a stream is split arbitrarily:
     * no tag leaks into the visible text, the text is not reordered, and
     * thinking and answer never interleave within a segment.
     *
     * <p>Deliberately not asserting one chunk per segment. A streaming parser
     * must emit speculatively, so a single logical segment legitimately arrives
     * as several chunks depending on where the network happened to split.
     */
    private static void assertSegmentsSplit(List<NaruStreamChunk> chunks, String answer, String thinking) {
        assertEquals(answer, join(chunks, NaruChunkKind.ANSWER),
                "answer text must be reassembled exactly, with no tag leakage");
        assertEquals(thinking, join(chunks, NaruChunkKind.THINKING),
                "thinking text must be reassembled exactly");
        // A single thinking segment yields at most answer-then-thinking-then-answer.
        // Anything else means the parser came back for a second round within one
        // segment, i.e. the two kinds interleave.
        StringBuilder shape = new StringBuilder();
        NaruChunkKind previous = null;
        for (NaruStreamChunk c : chunks) {
            if (c.kind() != previous) {
                shape.append(c.kind().name().charAt(0));
                previous = c.kind();
            }
        }
        String s = shape.toString();
        assertTrue("A".equals(s) || "T".equals(s) || "AT".equals(s) || "ATA".equals(s),
                "answer and thinking must not interleave, got shape " + s);
    }

    // ── tag wholly inside one chunk ─────────────────────────────────────────

    @Test
    public void aTagInsideOneChunkIsSplitOut() {
        List<NaruStreamChunk> chunks = parseAll("<think>reasoning</think>answer");
        assertEquals(List.of("THINKING", "ANSWER"), kinds(chunks));
        assertEquals("reasoning", join(chunks, NaruChunkKind.THINKING));
        assertEquals("answer", join(chunks, NaruChunkKind.ANSWER));
    }

    @Test
    public void textBeforeAndAfterTheTagIsKept() {
        List<NaruStreamChunk> chunks = parseAll("before<think>mid</think>after");
        assertEquals(List.of("ANSWER", "THINKING", "ANSWER"), kinds(chunks));
        assertEquals("before", chunks.get(0).text());
        assertEquals("mid", chunks.get(1).text());
        assertEquals("after", chunks.get(2).text());
    }

    // ── the same tag split across chunks ────────────────────────────────────

    @Test
    public void aTagSplitAcrossTwoChunksDoesNotLeakIntoTheAnswer() {
        // the open tag is cut after "<thi"
        List<NaruStreamChunk> chunks = parseAll("x<thi", "nk>secret</think>y");
        assertEquals(List.of("ANSWER", "THINKING", "ANSWER"), kinds(chunks));
        assertEquals("x", chunks.get(0).text());
        assertEquals("secret", chunks.get(1).text());
        assertEquals("y", chunks.get(2).text());
    }

    @Test
    public void aTagSplitAtEveryPossibleOffsetStillParses() {
        String whole = "a<think>hidden</think>b";
        for (int cut = 1; cut < whole.length(); cut++) {
            List<NaruStreamChunk> chunks = parseAll(whole.substring(0, cut), whole.substring(cut));
            assertSegmentsSplit(chunks, "ab", "hidden");
        }
    }

    @Test
    public void aTagDeliveredOneCharacterAtATimeStillParses() {
        String whole = "a<think>hidden</think>b";
        NaruThinkingTagParser p = new NaruThinkingTagParser();
        List<NaruStreamChunk> chunks = new ArrayList<>();
        for (char c : whole.toCharArray()) {
            chunks.addAll(p.feed(String.valueOf(c), "test"));
        }
        chunks.addAll(p.flush("test"));
        assertSegmentsSplit(chunks, "ab", "hidden");
    }

    @Test
    public void aPartialTagAtTheEndOfTheStreamIsNotSwallowed() {
        // no close tag and the stream ends mid-open-tag: whatever was pending is
        // still answer text and must be emitted by flush, not dropped
        List<NaruStreamChunk> chunks = parseAll("done<thi");
        assertSegmentsSplit(chunks, "done<thi", "");
    }

    // ── several thinking segments in one response ───────────────────────────

    @Test
    public void twoThinkingSegmentsAreEmittedSeparately() {
        List<NaruStreamChunk> chunks = parseAll("<think>one</think>mid<think>two</think>end");
        assertEquals(List.of("THINKING", "ANSWER", "THINKING", "ANSWER"), kinds(chunks));
        assertEquals("one", chunks.get(0).text());
        assertEquals("mid", chunks.get(1).text());
        assertEquals("two", chunks.get(2).text());
        assertEquals("end", chunks.get(3).text());
    }

    @Test
    public void thinkingSegmentsSplitAcrossChunksKeepTheirOrder() {
        List<NaruStreamChunk> chunks = parseAll("<thi", "nk>one</thi", "nk>mid<thi", "nk>two</thi", "nk>end");
        assertEquals(List.of("THINKING", "ANSWER", "THINKING", "ANSWER"), kinds(chunks));
        assertEquals("one", join(chunks, NaruChunkKind.THINKING).substring(0, 3));
        assertEquals("mid", chunks.get(1).text());
        assertEquals("end", chunks.get(3).text());
    }

    // ── nothing to do ───────────────────────────────────────────────────────

    @Test
    public void aResponseWithNoThinkingIsAllAnswer() {
        List<NaruStreamChunk> chunks = parseAll("just a normal answer");
        assertEquals(List.of("ANSWER"), kinds(chunks));
        assertEquals("just a normal answer", chunks.get(0).text());
    }

    @Test
    public void anEmptyResponseProducesNoChunks() {
        assertTrue(parseAll("").isEmpty());
    }

    @Test
    public void emptyChunksInTheMiddleAreHarmless() {
        List<NaruStreamChunk> chunks = parseAll("a", "", "b");
        assertEquals(List.of("ANSWER", "ANSWER"), kinds(chunks));
        assertEquals("ab", join(chunks, NaruChunkKind.ANSWER));
    }

    // ── malformed / unclosed ────────────────────────────────────────────────

    @Test
    public void anUnclosedTagLeavesTheTailAsThinkingAndReportsTruncation() {
        NaruThinkingTagParser p = new NaruThinkingTagParser();
        List<NaruStreamChunk> chunks = new ArrayList<>(p.feed("<think>never finished", "test"));
        chunks.addAll(p.flush("test"));
        assertSegmentsSplit(chunks, "", "never finished");
        assertTrue(p.sawThinking());
        assertTrue(p.isTruncated());
        assertFalse(p.isSegmentComplete());
        for (NaruStreamChunk c : chunks) {
            assertFalse(c.complete(), "an unterminated segment must not claim to be complete");
        }
    }

    @Test
    public void midStreamTextIsNeverReportedAsComplete() {
        // the whole point of the conservative rule: text delivered before the
        // stream ends cannot promise that more is coming
        NaruThinkingTagParser p = new NaruThinkingTagParser();
        List<NaruStreamChunk> chunks = new ArrayList<>(p.feed("partia", "test"));
        for (NaruStreamChunk c : chunks) {
            assertFalse(c.complete(), "mid-stream text must not claim to be complete");
        }
    }

    @Test
    public void aStrayCloseTagIsAnswerTextNotAnError() {
        // nothing opened, so nothing closes: the whole thing is ordinary text
        List<NaruStreamChunk> chunks = parseAll("plain</think>also plain");
        assertSegmentsSplit(chunks, "plain</think>also plain", "");
    }

    @Test
    public void aBareOpenTagWithNothingAfterItEmitsNothing() {
        // the tag is pure delimiter: it carries no text, so it must not become
        // an empty chunk
        List<NaruStreamChunk> chunks = parseAll("<think></think>done");
        assertEquals(List.of("ANSWER"), kinds(chunks));
        assertEquals("done", chunks.get(0).text());
    }

    @Test
    public void literalTextThatMerelyResemblesATagIsNotMistakenForOne() {
        // "a < b" style text must survive, and a lone "<" must not be held back
        List<NaruStreamChunk> chunks = parseAll("a < b", " <think is a thought, not a tag");
        assertEquals(List.of("ANSWER", "ANSWER"), kinds(chunks));
        assertEquals("a < b", chunks.get(0).text());
        assertEquals(" <think is a thought, not a tag", chunks.get(1).text());
    }

    @Test
    public void anInterruptMidSegmentMarksTheOpenSegmentTruncated() {
        NaruThinkingTagParser p = new NaruThinkingTagParser();
        List<NaruStreamChunk> chunks = new ArrayList<>(p.feed("<think>partial", "test"));
        chunks.addAll(p.markOpenSegmentTruncated("test"));
        assertSegmentsSplit(chunks, "", "partial");
        for (NaruStreamChunk c : chunks) {
            assertFalse(c.complete(), "an interrupted segment must not claim to be complete");
        }
        assertTrue(p.isTruncated());
        assertFalse(p.isSegmentComplete());
    }

    @Test
    public void aCleanBatchResponseHasExactSegmentation() {
        // the batch path sees the whole body, so every segment boundary is known
        // and no text is ever misfiled. The trailing chunk is still marked
        // provisional, because even a one-shot feed cannot know it is the last --
        // isSegmentComplete() is what settles that, not the chunk.
        NaruThinkingTagParser p = new NaruThinkingTagParser();
        List<NaruStreamChunk> chunks = new ArrayList<>(p.feed("a<think>done</think>after", "test"));
        chunks.addAll(p.flush("test"));
        assertSegmentsSplit(chunks, "aafter", "done");
        assertFalse(p.isTruncated());
        assertTrue(p.isSegmentComplete());
        // every chunk terminated by a delimiter the parser actually saw is final
        assertTrue(chunks.get(0).complete(), "text before an open tag is settled");
        assertTrue(chunks.get(1).complete(), "text before a close tag is settled");
        assertFalse(chunks.get(2).complete(), "a trailing chunk is provisional by nature");
    }

    @Test
    public void aCompletedSegmentIsNotMarkedTruncated() {
        NaruThinkingTagParser p = new NaruThinkingTagParser();
        p.feed("<think>done</think>after", "test");
        p.flush("test");
        assertFalse(p.isTruncated());
        assertTrue(p.isSegmentComplete());
    }

    // ── configurable delimiters ─────────────────────────────────────────────

    @Test
    public void aNonDefaultTagPairIsHonoured() {
        NaruThinkingTagParser p = new NaruThinkingTagParser("<scratch>", "</scratch>");
        List<NaruStreamChunk> chunks = new ArrayList<>();
        chunks.addAll(p.feed("a<scratch>hidden</scratch>b", "test"));
        chunks.addAll(p.flush("test"));
        assertEquals(List.of("ANSWER", "THINKING", "ANSWER"), kinds(chunks));
        assertEquals("hidden", chunks.get(1).text());
    }

    @Test
    public void aNonDefaultTagPairSplitAcrossChunksStillWorks() {
        NaruThinkingTagParser p = new NaruThinkingTagParser("<|reasoning|>", "<|/reasoning|>");
        List<NaruStreamChunk> chunks = new ArrayList<>();
        chunks.addAll(p.feed("a<|reason", "test"));
        chunks.addAll(p.feed("ing|>hidden<|/reason", "test"));
        chunks.addAll(p.feed("ing|>b", "test"));
        chunks.addAll(p.flush("test"));
        assertEquals(List.of("ANSWER", "THINKING", "ANSWER"), kinds(chunks));
        assertEquals("hidden", chunks.get(1).text());
        assertEquals("b", chunks.get(2).text());
    }

    @Test
    public void theDefaultTagPairIsNotAppliedWhenAnotherIsConfigured() {
        // <think> must now be ordinary text
        NaruThinkingTagParser p = new NaruThinkingTagParser("<r>", "</r>");
        List<NaruStreamChunk> chunks = new ArrayList<>();
        chunks.addAll(p.feed("<think>x</think>", "test"));
        chunks.addAll(p.flush("test"));
        assertEquals(List.of("ANSWER"), kinds(chunks));
        assertFalse(p.sawThinking());
    }

    // ── bookkeeping ─────────────────────────────────────────────────────────

    @Test
    public void indicesAreSequentialAndStartAtZero() {
        List<NaruStreamChunk> chunks = parseAll("a<think>b</think>c");
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).index());
        }
    }

    @Test
    public void thinkingChunksRecordTheTagExtractionMode() {
        List<NaruStreamChunk> chunks = parseAll("<think>x</think>y");
        assertEquals(NaruThinkingExtraction.TAG_DELIMITED, chunks.get(0).extraction());
        assertEquals(null, chunks.get(1).extraction(), "answer chunks have no extraction mode");
    }

    @Test
    public void theStaticHelperMatchesTheIncrementalPath() {
        List<NaruStreamChunk> viaHelper = new ArrayList<>();
        NaruThinkingTagParser.parseAll("a<think>x</think>b", "<think>", "</think>", "p",
                viaHelper::add);
        assertEquals(kinds(parseAll("a<think>x</think>b")), kinds(viaHelper));
    }
}
