package net.thevpc.naru.api.model;

import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.util.NIllegalArgumentException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Misconfigured delimiters fail in ways that are hard to notice and expensive to
 * diagnose later, so they are rejected where they are configured rather than
 * discovered when a model's answer comes back with half of it missing.
 */
public class NaruThinkingTagsTest {

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

    @Test
    public void aCompletePairIsEnabled() {
        NaruThinkingTags t = NaruThinkingTags.of("<r>", "</r>");
        assertTrue(t.isEnabled());
        assertEquals("<r>", t.openTag());
        assertEquals("</r>", t.closeTag());
        assertTrue(t.newParser() != null);
    }

    @Test
    public void halfAPairIsRejectedRatherThanGuessed() {
        // defaulting the missing half would strip whichever half happened to
        // match, and that looks exactly like a model that stopped thinking
        assertThrows(NIllegalArgumentException.class, () -> NaruThinkingTags.of("<r>", null));
        assertThrows(NIllegalArgumentException.class, () -> NaruThinkingTags.of(null, "</r>"));
    }

    @Test
    public void identicalOpenAndCloseTagsAreRejected() {
        // they would cancel out on the first character and never delimit anything
        assertThrows(NIllegalArgumentException.class, () -> NaruThinkingTags.of("<r>", "<r>"));
    }

    @Test
    public void bothTagsBlankMeansNativeOnly() {
        assertEquals(NaruThinkingTags.NATIVE_ONLY, NaruThinkingTags.of(null, null));
        assertEquals(NaruThinkingTags.NATIVE_ONLY, NaruThinkingTags.of("", ""));
        assertFalse(NaruThinkingTags.NATIVE_ONLY.isEnabled());
    }

    @Test
    public void nativeOnlyBuildsNoParser() {
        // asking for a parser here would scan the answer for tags the model was
        // never asked to emit
        assertNull(NaruThinkingTags.NATIVE_ONLY.newParser());
    }

    @Test
    public void tagsRoundTripThroughAnElement() {
        NaruThinkingTags t = NaruThinkingTags.of("<scratch>", "</scratch>");
        assertEquals(t, NaruThinkingTags.of(t.toElement()));
    }

    @Test
    public void differentPairsAreNotEqual() {
        // part of the model config, so equality is what stops a reconfigured
        // model from reusing a cached response parsed with the old delimiters
        assertNotEquals(NaruThinkingTags.of("<a>", "</a>"), NaruThinkingTags.of("<b>", "</b>"));
        assertEquals(NaruThinkingTags.of("<a>", "</a>"), NaruThinkingTags.of("<a>", "</a>"));
    }

    // ── config integration ──────────────────────────────────────────────────

    @Test
    public void configCarriesTagsThroughCopyMethods() {
        // every withX rebuilds the config, so a field not threaded through would
        // be silently dropped by any unrelated change
        NaruThinkingTags t = NaruThinkingTags.of("<r>", "</r>");
        NaruModelConfig c = new NaruModelConfig("p", "m").withThinkingTags(t);
        assertEquals(t, c.thinkingTags());
        assertEquals(t, c.withName("named").thinkingTags());
        assertEquals(t, c.withTemperature(0.5f).thinkingTags());
        assertEquals(t, c.withMaxTokens(10).thinkingTags());
        assertEquals(t, c.withStop(List.of("x")).thinkingTags());
    }

    @Test
    public void anUnconfiguredModelHasNoTagOverride() {
        // null means "use the protocol default", which is different from
        // NATIVE_ONLY meaning "deliberately do not parse"
        assertNull(new NaruModelConfig("p", "m").thinkingTags());
    }

    @Test
    public void configEqualityAccountsForTags() {
        NaruThinkingTags t = NaruThinkingTags.of("<r>", "</r>");
        assertNotEquals(new NaruModelConfig("p", "m").withThinkingTags(t),
                new NaruModelConfig("p", "m"));
        assertEquals(new NaruModelConfig("p", "m").withThinkingTags(t),
                new NaruModelConfig("p", "m").withThinkingTags(t));
    }

    @Test
    public void configTagsRoundTripThroughAnElement() {
        NaruThinkingTags t = NaruThinkingTags.of("<r>", "</r>");
        NaruModelConfig c = new NaruModelConfig("p", "m").withThinkingTags(t);
        NaruModelConfig back = new NaruModelConfig(c.toElement());
        assertEquals(t, back.thinkingTags());
    }
}
