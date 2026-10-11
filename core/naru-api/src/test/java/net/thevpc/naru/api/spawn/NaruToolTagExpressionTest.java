package net.thevpc.naru.api.spawn;

import net.thevpc.nuts.Nuts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the tag-expression language now that its syntax is parsed by the Nuts NExpr engine
 * (see {@link net.thevpc.naru.api.util.NaruExprs}) instead of a hand-written parser.
 *
 * <p>What must not change across that swap: the accepted operators ({@code &}, {@code |},
 * {@code !}, parentheses), lower-kebab normalization of names, membership evaluation, the
 * positive/negative name collection, and the {@link IllegalArgumentException} contract on
 * malformed input.
 */
public class NaruToolTagExpressionTest {

    @BeforeAll
    public static void setUp() {
        // the NExpr engine resolves through the Nuts workspace
        Nuts.require();
    }

    private static NaruToolTagExpression expr(String s) {
        return NaruToolTagExpression.parse(s);
    }

    // ── evaluation ───────────────────────────────────────────────────────────

    @Test
    public void andRequiresEveryName() {
        NaruToolTagExpression e = expr("fs & !write");
        assertTrue(e.matches(Set.of("fs")));
        assertTrue(!e.matches(Set.of("fs", "write")));
        assertTrue(!e.matches(Set.of("write")));
        assertTrue(!e.matches(Set.of()));
    }

    @Test
    public void orIsSatisfiedByEitherSide() {
        NaruToolTagExpression e = expr("write | exec");
        assertTrue(e.matches(Set.of("write")));
        assertTrue(e.matches(Set.of("exec")));
        assertTrue(!e.matches(Set.of("fs")));
    }

    @Test
    public void parenthesesGroupAndNegationDistributes() {
        NaruToolTagExpression e = expr("fs & !(write | exec)");
        assertTrue(e.matches(Set.of("fs")));
        assertTrue(!e.matches(Set.of("fs", "write")));
        assertTrue(!e.matches(Set.of("fs", "exec")));
    }

    @Test
    public void namesAreNormalizedToLowerKebabCase() {
        NaruToolTagExpression e = expr("FS & !Code-Review");
        assertTrue(e.matches(Set.of("fs")));
        assertTrue(!e.matches(Set.of("fs", "code_review")));
        assertTrue(!e.matches(Set.of("fs", "CODE-REVIEW")));
    }

    // ── name collection ──────────────────────────────────────────────────────

    @Test
    public void positiveAndNegativeNamesAreSplit() {
        NaruToolTagExpression e = expr("fs & !write & git");
        assertEquals(Set.of("fs", "git"), e.positiveTagNames());
        assertEquals(Set.of("write"), e.negativeTagNames());
    }

    @Test
    public void anOrCannotPromiseEitherPositiveName() {
        // an 'or' cannot promise any single positive name, and the names behind it are not
        // required to be absent either; this is the behavior the consistency checks rely on
        NaruToolTagExpression e = expr("a | b");
        assertEquals(Set.of(), e.positiveTagNames());
        assertEquals(Set.of("a", "b"), e.negativeTagNames());
    }

    @Test
    public void violationsNameWhatMustBeAddedOrRevoked() {
        NaruToolTagExpression e = expr("fs & !write");
        assertEquals(List.of("+fs", "-write"), e.violations(Set.of("write")));
        assertEquals(List.of("-write"), e.violations(Set.of("fs", "write")));
        assertEquals(List.of(), e.violations(Set.of("fs")));
    }

    // ── formatting and equality ──────────────────────────────────────────────

    @Test
    public void toStringRoundTripsAndEqualsFollowsIt() {
        NaruToolTagExpression e = expr("fs & !write");
        // a non-name operand is parenthesised by the shared writer, as it always was
        assertEquals("fs & (!write)", e.toString());
        assertEquals(e, NaruToolTagExpression.parse(e.toString()));
        assertEquals(e.hashCode(), NaruToolTagExpression.parse(e.toString()).hashCode());
        assertEquals("fs & (write | exec)", expr("fs & (write | exec)").toString());
        assertEquals("fs & (!write) & git", expr("fs & !write & git").toString());
    }

    // ── malformed input ──────────────────────────────────────────────────────

    @Test
    public void malformedExpressionsThrowIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> NaruToolTagExpression.parse(null));
        assertThrows(IllegalArgumentException.class, () -> NaruToolTagExpression.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> NaruToolTagExpression.parse("fs & (("));
        assertThrows(IllegalArgumentException.class, () -> NaruToolTagExpression.parse("fs &"));
        assertThrows(IllegalArgumentException.class, () -> NaruToolTagExpression.parse("& fs"));
    }
}
