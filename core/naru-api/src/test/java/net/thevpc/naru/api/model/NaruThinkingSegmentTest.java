package net.thevpc.naru.api.model;

import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A session file is the only durable record of what happened, and the reasoning
 * it holds cannot be recovered by re-running the model. These tests pin the
 * round-trip and, just as importantly, pin that messages which never thought
 * serialize exactly as they did before any of this existed.
 */
public class NaruThinkingSegmentTest {

    /**
     * Serialising to an element goes through the Nuts workspace, which is not
     * implicitly available in a plain unit test.
     */
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

    private static NaruThinkingSegment seg(int i, String text, boolean complete) {
        return new NaruThinkingSegment(i, text, NaruThinkingExtraction.TAG_DELIMITED, "gpt", 12, complete);
    }

    private static NaruMessage roundTrip(NaruMessage m) {
        NElement e = m.toElement();
        return new NaruMessage(e);
    }

    // ── segment itself ──────────────────────────────────────────────────────

    @Test
    public void aSegmentSurvivesRoundTrip() {
        NaruThinkingSegment s = seg(2, "weighing options", true);
        NaruThinkingSegment back = NaruThinkingSegment.of(s.toElement());
        assertEquals(2, back.getIndex());
        assertEquals("weighing options", back.getText());
        assertEquals(NaruThinkingExtraction.TAG_DELIMITED, back.getExtraction());
        assertEquals("gpt", back.getProvider());
        assertEquals(12, back.getThinkingTokens());
        assertTrue(back.isComplete());
    }

    @Test
    public void anUnmeasuredTokenCountIsNotPersistedAsZero() {
        // conflating "nobody counted" with "none" would make a transcript claim
        // a measurement nobody made
        NaruThinkingSegment s = new NaruThinkingSegment(0, "t", NaruThinkingExtraction.NATIVE_FIELD, null);
        NObjectElement o = s.toElement().asObject().get();
        assertNull(o.get("thinkingTokens").orNull(), "unknown token count must not be stored");
        assertFalse(s.hasTokenCount());
        assertEquals(0, s.tokenCountOrZero());
        assertFalse(NaruThinkingSegment.of(s.toElement()).hasTokenCount());
    }

    @Test
    public void aTruncatedSegmentPersistsItsIncompleteness() {
        // the whole reason complete is recorded: a thought that was cut short
        // must not later read as one the model finished
        NaruThinkingSegment s = seg(0, "half a thought", false);
        assertEquals(false, s.toElement().asObject().get().getBooleanValue("complete").orElse(true));
        assertFalse(NaruThinkingSegment.of(s.toElement()).isComplete());
    }

    @Test
    public void aFinishedSegmentOmitsTheDefaultFlag() {
        assertNull(seg(0, "t", true).toElement().asObject().get().get("complete").orNull());
    }

    // ── message integration ──────────────────────────────────────────────────

    @Test
    public void segmentsSurviveAMessageRoundTrip() {
        NaruMessage m = NaruMessage.assistant("the answer")
                .addThinkingSegment(seg(0, "first thought", true))
                .addThinkingSegment(seg(1, "second thought", false));
        NaruMessage back = roundTrip(m);
        assertEquals("the answer", back.getContent());
        List<NaruThinkingSegment> segments = back.getThinkingSegments();
        assertEquals(2, segments.size());
        assertEquals("first thought", segments.get(0).getText());
        assertEquals("second thought", segments.get(1).getText());
        assertTrue(segments.get(0).isComplete());
        assertFalse(segments.get(1).isComplete());
    }

    @Test
    public void segmentOrderIsPreservedAcrossInterleavedThinking() {
        NaruMessage m = NaruMessage.assistant("a").addThinkingSegment(seg(0, "t1", true));
        NaruMessage back = roundTrip(m);
        assertEquals(0, back.getThinkingSegments().get(0).getIndex());
    }

    @Test
    public void addThinkingSegmentAssignsAscendingIndices() {
        NaruMessage m = NaruMessage.assistant("a")
                .addThinkingSegment(seg(0, "t1", true))
                .addThinkingSegment(seg(0, "t2", true));
        List<NaruThinkingSegment> segments = m.getThinkingSegments();
        assertEquals(0, segments.get(0).getIndex());
        assertEquals(1, segments.get(1).getIndex());
        assertEquals("t2", segments.get(1).getText(), "reindexing must not lose text");
    }

    @Test
    public void aMessageWithNoThinkingAddsNoNewKeys() {
        // the overwhelmingly common case: a non-reasoning model's turn must not
        // grow. "thinkingSegments" must be absent entirely -- writing an empty
        // array would change every existing session file for no benefit.
        // "thinking" is still written as an explicit null, which is long-standing
        // behaviour and is deliberately left alone.
        NObjectElement o = NaruMessage.assistant("just an answer").toElement().asObject().get();
        assertNull(o.get("thinkingSegments").orNull(), "segments key must be absent, not empty");
        NElement legacy = o.get("thinking").orNull();
        assertNotNull(legacy, "the legacy thinking key has always been present");
        assertTrue(legacy.isNull());
    }

    @Test
    public void anEmptySegmentListIsTreatedAsNoThinking() {
        NaruMessage m = NaruMessage.assistant("a").setThinkingSegments(new ArrayList<>());
        assertNull(m.getThinkingSegments());
        assertFalse(m.hasThinking());
        assertNull(m.toElement().asObject().get().get("thinkingSegments").orNull());
    }

    @Test
    public void legacyThinkingStringsStillRead() {
        // a session written before segments existed must keep its reasoning.
        // setThinking produces exactly that legacy shape: a single "thinking"
        // string and no "thinkingSegments" key.
        NElement legacy = NaruMessage.assistant("a").setThinking("old reasoning").toElement();
        assertNull(legacy.asObject().get().get("thinkingSegments").orNull());
        NaruMessage back = new NaruMessage(legacy);
        assertEquals("old reasoning", back.getThinking());
        assertTrue(back.hasThinking());
        assertNull(back.getThinkingSegments());
    }

    @Test
    public void getThinkingIsDerivedFromSegments() {
        // callers written against the original string field keep working
        NaruMessage m = NaruMessage.assistant("a")
                .addThinkingSegment(seg(0, "one", true))
                .addThinkingSegment(seg(1, "two", true));
        assertEquals("one\ntwo", m.getThinking());
        assertTrue(m.hasThinking());
    }

    @Test
    public void settingSegmentsClearsTheLegacyString() {
        // the two are alternatives; keeping a stale string would make the
        // message serialize whichever form it happens to check first
        NaruMessage m = NaruMessage.assistant("a")
                .setThinking("stale")
                .setThinkingSegments(List.of(seg(0, "fresh", true)));
        assertEquals("fresh", m.getThinking());
        NaruMessage back = roundTrip(m);
        assertEquals("fresh", back.getThinkingSegments().get(0).getText());
    }

    @Test
    public void aMessageWithNoReasoningHasNoThinkingString() {
        assertNull(NaruMessage.assistant("a").getThinking());
        assertFalse(NaruMessage.assistant("a").hasThinking());
    }

    @Test
    public void cloneCopiesSegmentsDefensively() {
        NaruMessage m = NaruMessage.assistant("a").addThinkingSegment(seg(0, "t", true));
        NaruMessage copy = m.copy();
        assertNotSame(m.getThinkingSegments(), copy.getThinkingSegments());
        assertEquals("t", copy.getThinkingSegments().get(0).getText());
    }

    @Test
    public void theExposedSegmentListIsReadOnly() {
        NaruMessage m = NaruMessage.assistant("a").addThinkingSegment(seg(0, "t", true));
        try {
            m.getThinkingSegments().add(seg(1, "sneaky", true));
            throw new AssertionError("segment list must not be mutable from outside");
        } catch (UnsupportedOperationException expected) {
            // correct: mutating it would bypass index assignment
        }
    }

    // ── token kinds ─────────────────────────────────────────────────────────

    @Test
    public void everyTokenKindOccupiesContext() {
        // reasoning is carried back in on the next request, so a context budget
        // that ignored it would under-count by however much the model thought
        for (NaruTokenKind k : NaruTokenKind.values()) {
            assertTrue(k.consumesContext(), k + " must consume context");
        }
    }

    @Test
    public void onlyOutputAndThinkingAreBilled() {
        assertFalse(NaruTokenKind.INPUT.isBilled());
        assertTrue(NaruTokenKind.OUTPUT.isBilled());
        assertTrue(NaruTokenKind.THINKING.isBilled());
    }

    @Test
    public void tokenKindWireNamesAreDistinct() {
        List<String> names = new ArrayList<>();
        for (NaruTokenKind k : NaruTokenKind.values()) {
            names.add(k.wireName());
        }
        assertEquals(names.size(), names.stream().distinct().count());
    }
}
