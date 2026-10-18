package net.thevpc.naru.desktop.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The cases that matter are the ones the engine really emits, including the exact string that
 * leaked into the Phase 1 gate transcript.
 */
class MsgTextTest {

    @Test
    void leavesPlainTextAlone() {
        assertEquals("no markers here", MsgText.plain("no markers here"));
        assertEquals("", MsgText.plain(""));
        assertEquals("hash # alone", MsgText.plain("hash # alone"));
    }

    @Test
    void stripsTheStyleTokensTheEngineEmits() {
        assertEquals("ollama", MsgText.plain("##{p9:ollama}##"));
        assertEquals("ollama/", MsgText.plain("##{p9:ollama}####{separator:/}##"));
        assertEquals("two words", MsgText.plain("##{a:two}## ##{b:words}##"));
        assertEquals("before x after", MsgText.plain("before ##{p1:x}## after"));
    }

    /** Reproduce the gate failure: this string came straight out of a real session. */
    @Test
    void stripsTheObservedLeakedLine() {
        String raw = "Selected model : ##{p9:ollama}####{separator:/}####{p6:qwen2.5-coder:14b}##";
        assertEquals("Selected model : ollama/qwen2.5-coder:14b", MsgText.plain(raw));
    }

    @Test
    void keepsAmbiguousTextIntact() {
        // a lone marker pair, a token with no terminator, and a bare brace group are all
        // left alone: guessing would delete real model output
        assertEquals("## not a token", MsgText.plain("## not a token"));
        assertEquals("{p9:x}", MsgText.plain("{p9:x}"));
        assertEquals("broken ##{p9:no end", MsgText.plain("broken ##{p9:no end"));
        // "##{...}##" whose head is prose, not a style id, keeps the whole body
        assertEquals("see note: here", MsgText.plain("##{p1:see note: here}##"));
    }

    @Test
    void nullSafe() {
        assertNull(MsgText.plain(null));
    }
}
