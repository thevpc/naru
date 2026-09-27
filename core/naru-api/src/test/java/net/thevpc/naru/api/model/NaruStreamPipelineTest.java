package net.thevpc.naru.api.model;

import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pipeline is the seam that makes every provider look the same to everything
 * downstream, and the promise that lets streaming be switched on is that it
 * changes when you see the answer, never what the answer is. Most of these tests
 * are really testing that second half.
 */
public class NaruStreamPipelineTest {

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

    private static final class Recorder implements NaruStreamHandler {
        final List<NaruStreamChunk> chunks = new ArrayList<>();

        @Override
        public void onChunk(NaruStreamChunk chunk) {
            chunks.add(chunk);
        }

        String answer() {
            StringBuilder sb = new StringBuilder();
            for (NaruStreamChunk c : chunks) {
                if (c.kind() == NaruChunkKind.ANSWER) {
                    sb.append(c.text());
                }
            }
            return sb.toString();
        }

        String thinking() {
            StringBuilder sb = new StringBuilder();
            for (NaruStreamChunk c : chunks) {
                if (c.kind() == NaruChunkKind.THINKING) {
                    sb.append(c.text());
                }
            }
            return sb.toString();
        }
    }

    private static NaruStreamPipeline streaming(Recorder r, NaruThinkingTags tags, String... pieces) {
        NaruStreamPipeline p = new NaruStreamPipeline("test-provider", r, tags);
        for (String piece : pieces) {
            p.feed(piece);
        }
        p.finish();
        return p;
    }

    private static NaruThinkingTags defaultTags() {
        return new NaruThinkingTagParser().tags();
    }

    // ── extraction ──────────────────────────────────────────────────────────

    @Test
    public void thinkingIsSeparatedFromTheAnswer() {
        Recorder r = new Recorder();
        NaruMessage m = streaming(r, defaultTags(), "a<think>weighing it up</think>b").message();
        assertEquals("ab", m.getContent());
        assertEquals(1, m.getThinkingSegments().size());
        assertEquals("weighing it up", m.getThinkingSegments().get(0).getText());
        assertEquals("weighing it up", r.thinking());
        assertEquals("ab", r.answer());
    }

    @Test
    public void nativeReasoningIsKeptOutOfTheAnswer() {
        Recorder r = new Recorder();
        NaruStreamPipeline p = new NaruStreamPipeline("test-provider", r, NaruThinkingTags.NATIVE_ONLY);
        p.feedNativeThinking("step one");
        p.feed("the answer");
        NaruMessage m = p.finish();
        assertEquals("the answer", m.getContent());
        assertEquals(1, m.getThinkingSegments().size());
        assertEquals(NaruThinkingExtraction.NATIVE_FIELD, m.getThinkingSegments().get(0).getExtraction());
        assertEquals("step one", r.thinking());
    }

    @Test
    public void nativeAndTaggedReasoningBothSurvive() {
        Recorder r = new Recorder();
        NaruStreamPipeline p = new NaruStreamPipeline("test-provider", r, defaultTags());
        p.feedNativeThinking("native thoughts");
        p.feed("x<think>tagged thoughts</think>y");
        NaruMessage m = p.finish();
        List<NaruThinkingSegment> segments = m.getThinkingSegments();
        assertEquals(2, segments.size());
        assertEquals(NaruThinkingExtraction.NATIVE_FIELD, segments.get(0).getExtraction());
        assertEquals("native thoughts", segments.get(0).getText());
        assertEquals(NaruThinkingExtraction.TAG_DELIMITED, segments.get(1).getExtraction());
        assertEquals("tagged thoughts", segments.get(1).getText());
        assertEquals("xy", m.getContent());
    }

    @Test
    public void severalThinkingRunsBecomeSeveralSegments() {
        Recorder r = new Recorder();
        NaruMessage m = streaming(r, defaultTags(),
                "<think>one</think>mid<think>two</think>end").message();
        List<NaruThinkingSegment> segments = m.getThinkingSegments();
        assertEquals(2, segments.size());
        assertEquals("one", segments.get(0).getText());
        assertEquals("two", segments.get(1).getText());
        assertEquals(0, segments.get(0).getIndex());
        assertEquals(1, segments.get(1).getIndex());
        assertEquals("midend", m.getContent());
    }

    @Test
    public void aStreamSplittingOneThoughtStillYieldsOneSegment() {
        // the same thought arrives as many chunks; it must not be persisted as
        // many separate thoughts
        Recorder r = new Recorder();
        NaruMessage m = streaming(r, defaultTags(), "<thi", "nk>a lon", "g thoug", "ht</thi", "nk>done").message();
        assertEquals(1, m.getThinkingSegments().size());
        assertEquals("a long thought", m.getThinkingSegments().get(0).getText());
        assertEquals("done", m.getContent());
    }

    @Test
    public void nativeOnlyMeansTextMentioningTagsIsLeftAlone() {
        // parsing tags here would silently delete text the user asked about
        Recorder r = new Recorder();
        NaruStreamPipeline p = new NaruStreamPipeline("test-provider", r, NaruThinkingTags.NATIVE_ONLY);
        p.feed("you can use <think> tags like this");
        NaruMessage m = p.finish();
        assertEquals("you can use <think> tags like this", m.getContent());
        assertFalse(m.hasThinking());
    }

    @Test
    public void aModelWithNoThinkingProducesNoSegments() {
        Recorder r = new Recorder();
        NaruMessage m = streaming(r, defaultTags(), "just an answer").message();
        assertEquals("just an answer", m.getContent());
        assertFalse(m.hasThinking());
        assertNull(m.getThinkingSegments());
    }

    // ── batch and stream must agree ─────────────────────────────────────────

    @Test
    public void batchAndStreamProduceTheSameMessage() {
        String body = "before<think>reasoning here</think>after";
        Recorder batchR = new Recorder();
        NaruMessage batch = NaruStreamPipeline.batch("p", body, batchR, defaultTags()).message();
        // deliver it in pieces hostile enough to split the tags themselves
        Recorder streamR = new Recorder();
        NaruStreamPipeline p = new NaruStreamPipeline("p", streamR, defaultTags());
        p.feed("before<thi");
        p.feed("nk>reason");
        p.feed("ing here</thi");
        p.feed("nk>after");
        NaruMessage streamed = p.finish();
        assertEquals(batch.getContent(), streamed.getContent());
        assertEquals(batch.getThinking(), streamed.getThinking());
        assertEquals(batch.getThinkingSegments().size(), streamed.getThinkingSegments().size());
        assertEquals(batch.getThinkingSegments().get(0).getText(),
                streamed.getThinkingSegments().get(0).getText());
        assertEquals(batchR.answer(), streamR.answer());
        assertEquals(batchR.thinking(), streamR.thinking());
    }

    @Test
    public void batchAndStreamAgreeWhenThereIsNoThinking() {
        Recorder batchR = new Recorder();
        NaruMessage batch = NaruStreamPipeline.batch("p", "plain answer", batchR, defaultTags()).message();
        Recorder streamR = new Recorder();
        NaruStreamPipeline p = new NaruStreamPipeline("p", streamR, defaultTags());
        p.feed("plain ");
        p.feed("answer");
        NaruMessage streamed = p.finish();
        assertEquals(batch.getContent(), streamed.getContent());
        assertEquals(batchR.answer(), streamR.answer());
    }

    // ── interruption ────────────────────────────────────────────────────────

    @Test
    public void anInterruptedThoughtIsPersistedAsIncomplete() {
        // dropping it would lose the reasoning entirely; marking it complete
        // would claim the model finished a thought it never did
        Recorder r = new Recorder();
        NaruStreamPipeline p = new NaruStreamPipeline("p", r, defaultTags());
        p.feed("<think>half a thought");
        NaruMessage m = p.finish(true);
        assertEquals(1, m.getThinkingSegments().size());
        assertEquals("half a thought", m.getThinkingSegments().get(0).getText());
        assertFalse(m.getThinkingSegments().get(0).isComplete());
    }

    @Test
    public void anUnclosedTagWithoutInterruptionIsAlsoIncomplete() {
        Recorder r = new Recorder();
        NaruStreamPipeline p = new NaruStreamPipeline("p", r, defaultTags());
        p.feed("<think>never closed");
        NaruMessage m = p.finish();
        assertFalse(m.getThinkingSegments().get(0).isComplete());
    }

    @Test
    public void aClosedThoughtIsComplete() {
        Recorder r = new Recorder();
        NaruStreamPipeline p = new NaruStreamPipeline("p", r, defaultTags());
        p.feed("<think>all done</think>ok");
        NaruMessage m = p.finish();
        assertTrue(m.getThinkingSegments().get(0).isComplete());
    }

    // ── token accounting ────────────────────────────────────────────────────

    @Test
    public void reportedThinkingTokensAreAttributed() {
        Recorder r = new Recorder();
        NaruStreamPipeline p = new NaruStreamPipeline("p", r, defaultTags());
        p.feed("<think>t</think>a");
        p.thinkingTokens(42);
        NaruMessage m = p.finish();
        assertTrue(m.getThinkingSegments().get(0).hasTokenCount());
        assertEquals(42, m.getThinkingSegments().get(0).getThinkingTokens());
    }

    @Test
    public void unreportedThinkingTokensStayUnknownRatherThanZero() {
        Recorder r = new Recorder();
        NaruStreamPipeline p = new NaruStreamPipeline("p", r, defaultTags());
        p.feed("<think>t</think>a");
        NaruMessage m = p.finish();
        assertFalse(m.getThinkingSegments().get(0).hasTokenCount());
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    @Test
    public void messageIsStableAcrossRepeatedReads() {
        // rendering asks for the message as it goes and again at the end; the
        // segment numbering must not drift underneath it
        Recorder r = new Recorder();
        NaruStreamPipeline p = new NaruStreamPipeline("p", r, defaultTags());
        p.feedNativeThinking("native");
        p.feed("x<think>tagged</think>y");
        NaruMessage before = p.message();
        NaruMessage after = p.finish();
        assertEquals(before.getContent(), after.getContent());
        assertEquals(before.getThinkingSegments().size(), after.getThinkingSegments().size());
        assertEquals(0, after.getThinkingSegments().get(0).getIndex());
        assertEquals(1, after.getThinkingSegments().get(1).getIndex());
    }

    @Test
    public void finishingTwiceIsHarmless() {
        Recorder r = new Recorder();
        NaruStreamPipeline p = new NaruStreamPipeline("p", r, defaultTags());
        p.feed("a<think>b</think>c");
        NaruMessage first = p.finish();
        NaruMessage second = p.finish();
        assertEquals(first.getContent(), second.getContent());
        assertEquals(first.getThinking(), second.getThinking());
    }

    @Test
    public void feedingAfterFinishIsRejected() {
        // a late chunk from a transport race must not silently extend a message
        // that has already been persisted
        NaruStreamPipeline p = new NaruStreamPipeline("p", new Recorder(), defaultTags());
        p.finish();
        assertThrows(IllegalStateException.class, () -> p.feed("late"));
    }

    @Test
    public void aNullHandlerStillBuildsAMessage() {
        // useful for the batch path, where only the result is wanted
        NaruStreamPipeline p = new NaruStreamPipeline("p", null, defaultTags());
        p.feed("a<think>b</think>c");
        NaruMessage m = p.finish();
        assertEquals("ac", m.getContent());
        assertEquals("b", m.getThinking());
    }

    @Test
    public void emptyInputIsANoOp() {
        Recorder r = new Recorder();
        NaruStreamPipeline p = new NaruStreamPipeline("p", r, defaultTags());
        p.feed("");
        p.feed(null);
        p.feedNativeThinking("");
        p.feedNativeThinking(null);
        NaruMessage m = p.finish();
        assertEquals("", m.getContent());
        assertFalse(m.hasThinking());
        assertTrue(r.chunks.isEmpty());
    }

    // ── cancellation seam ───────────────────────────────────────────────────

    @Test
    public void thePipelineNeverDropsContentItWasGiven() {
        // the contract is that cancellation is observed by whoever stops feeding:
        // the pipeline records everything it is handed, so an interrupted turn
        // is marked incomplete rather than silently short
        final boolean[] cancelled = {false};
        NaruStreamHandler handler = new NaruStreamHandler() {
            @Override
            public void onChunk(NaruStreamChunk chunk) {
            }

            @Override
            public boolean isCancelled() {
                return cancelled[0];
            }
        };
        NaruStreamPipeline p = new NaruStreamPipeline("p", handler, defaultTags());
        p.feed("first");
        cancelled[0] = true;
        p.feed("second");
        NaruMessage m = p.finish(true);
        assertTrue(handler.isCancelled(), "a transport can poll this to stop reading");
        assertEquals("firstsecond", m.getContent());
    }
}
