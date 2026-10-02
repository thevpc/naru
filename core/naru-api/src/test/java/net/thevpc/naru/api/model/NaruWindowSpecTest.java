package net.thevpc.naru.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NaruWindowSpec} parsing, with particular attention to the two accepted spellings.
 *
 * <p>The {@code last<Unit>=} form had a real defect: scanning for the end of the number before
 * looking for {@code =} made that form unreachable, because its first character is a letter.
 * Both forms are exercised here so that cannot come back.
 */
class NaruWindowSpecTest {

    @Test
    void parsesTheBareUnitSuffix() {
        assertEquals(NaruWindowSpec.lastItems(4), NaruWindowSpec.parse("4items"));
        assertEquals(NaruWindowSpec.lastTurns(4), NaruWindowSpec.parse("4turns"));
        assertEquals(NaruWindowSpec.lastTokens(2000), NaruWindowSpec.parse("2000tokens"));
    }

    @Test
    void parsesTheEqualsFormWithAndWithoutTheLastPrefix() {
        assertEquals(NaruWindowSpec.lastItems(20), NaruWindowSpec.parse("lastItems=20"));
        assertEquals(NaruWindowSpec.lastItems(20), NaruWindowSpec.parse("items=20"));
        assertEquals(NaruWindowSpec.lastTurns(2), NaruWindowSpec.parse("lastTurns=2"));
        assertEquals(NaruWindowSpec.lastTokens(500), NaruWindowSpec.parse("lastTokens=500"));
    }

    @Test
    void isCaseAndWhitespaceInsensitive() {
        assertEquals(NaruWindowSpec.lastTurns(4), NaruWindowSpec.parse("  4TURNS "));
        assertEquals(NaruWindowSpec.lastItems(20), NaruWindowSpec.parse(" LASTITEMS = 20 "));
    }

    @Test
    void acceptsSingularUnits() {
        assertEquals(NaruWindowSpec.lastItems(1), NaruWindowSpec.parse("1item"));
        assertEquals(NaruWindowSpec.lastTurns(1), NaruWindowSpec.parse("1turn"));
        assertEquals(NaruWindowSpec.lastTokens(1), NaruWindowSpec.parse("1token"));
    }

    @Test
    void noneAndAllAreRecognized() {
        assertTrue(NaruWindowSpec.parse("none").isNone());
        assertTrue(NaruWindowSpec.parse("").isNone());
        assertTrue(NaruWindowSpec.parse("all").isAll());
    }

    @Test
    void nullMeansUnsetRatherThanNone() {
        // The distinction matters to config resolution: null says "nothing was configured",
        // so the next source in the chain gets its turn. Returning NONE here would let an
        // absent setting read as an explicit "keep nothing".
        assertNull(NaruWindowSpec.parse(null));
        assertEquals(NaruWindowSpec.all(), NaruContextSpec.keepAll().window(),
                "an unspecified keep leaves the default of keep-everything in place");
    }

    @Test
    void zeroOrNegativeMeansKeepNothing() {
        // "keep 0 turns" is a legitimate way of saying "summarize everything", and collapsing
        // it to an error would leave no way to express it.
        assertTrue(NaruWindowSpec.parse("0turns").isNone());
        assertTrue(NaruWindowSpec.lastItems(0).isNone());
        assertTrue(NaruWindowSpec.lastItems(-3).isNone());
    }

    @Test
    void rejectsAMalformedValue() {
        assertThrows(IllegalArgumentException.class, () -> NaruWindowSpec.parse("4widgets"));
        assertThrows(IllegalArgumentException.class, () -> NaruWindowSpec.parse("turns"));
        assertThrows(IllegalArgumentException.class, () -> NaruWindowSpec.parse("lastItems=many"));
    }

    @Test
    void roundTripsThroughToString() {
        for (String v : new String[]{"20items", "4turns", "3000tokens"}) {
            NaruWindowSpec spec = NaruWindowSpec.parse(v);
            assertEquals(spec, NaruWindowSpec.parse(spec.toString()),
                    "toString of " + v + " must parse back to itself");
        }
        assertEquals(NaruWindowSpec.none(), NaruWindowSpec.parse(NaruWindowSpec.none().toString()));
        assertEquals(NaruWindowSpec.all(), NaruWindowSpec.parse(NaruWindowSpec.all().toString()));
    }

    @Test
    void compactFormIsAcceptedBack() {
        assertEquals(NaruWindowSpec.lastTurns(4),
                NaruWindowSpec.parse(NaruWindowSpec.lastTurns(4).toCompactString()));
    }
}