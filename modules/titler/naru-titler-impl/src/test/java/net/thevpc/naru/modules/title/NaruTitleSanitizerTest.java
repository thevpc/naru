package net.thevpc.naru.modules.title;

import net.thevpc.naru.api.model.NaruThinkingTags;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The sanitizer is the part of titling a model can defeat with punctuation, so it is tested
 * against the answers models really give rather than against a model.
 */
public class NaruTitleSanitizerTest {

    private static String clean(String raw) {
        return NaruTitleSanitizer.sanitize(raw, null);
    }

    @Test
    public void stripsSurroundingQuotesAndBackticks() {
        Assertions.assertEquals("Resizing a partition", clean("\"Resizing a partition\""));
        Assertions.assertEquals("Resizing a partition", clean("`Resizing a partition`"));
        Assertions.assertEquals("Resizing a partition", clean("“Resizing a partition”"));
        Assertions.assertEquals("Resizing a partition", clean("**Resizing a partition**"));
    }

    @Test
    public void dropsAPrefixTheModelAddedAnyway() {
        Assertions.assertEquals("Resizing a partition", clean("Title: Resizing a partition"));
        Assertions.assertEquals("Resizing a partition", clean("**Session title:** Resizing a partition"));
        Assertions.assertEquals("Resizing a partition", clean("Name - Resizing a partition"));
    }

    @Test
    public void takesTheFirstLineAndIgnoresThePreamble() {
        Assertions.assertEquals("Resizing a partition",
                clean("Sure, here is a title:\nResizing a partition\nLet me know if you want another."));
    }

    @Test
    public void removesMarkdownHeadingMarkers() {
        Assertions.assertEquals("Resizing a partition", clean("## Resizing a partition"));
    }

    @Test
    public void discardsThinkingBlocks() {
        Assertions.assertEquals("Resizing a partition",
                clean("<think>let me consider the topic of disks</think>Resizing a partition"));
        Assertions.assertEquals("Resizing a partition",
                clean("<thinking>\nsome reasoning\n</thinking>\nResizing a partition"));
    }

    /** An unterminated block is reasoning that ran to the end of the answer, so nothing is left. */
    @Test
    public void anUnterminatedThinkingBlockYieldsNothing() {
        Assertions.assertNull(clean("<think>reasoning that never closed"));
    }

    @Test
    public void honoursTheModelOwnThinkingDelimiters() {
        NaruThinkingTags tags = NaruThinkingTags.of("<plan>", "</plan>");
        Assertions.assertEquals("Resizing a partition",
                NaruTitleSanitizer.sanitize("<plan>thinking</plan>Resizing a partition", tags));
    }

    @Test
    public void trimsTrailingSentencePunctuation() {
        Assertions.assertEquals("Resizing a partition", clean("Resizing a partition."));
        Assertions.assertEquals("Resizing a partition", clean("Resizing a partition?"));
    }

    @Test
    public void emptyOrWhitespaceAnswersAreNotTitles() {
        Assertions.assertNull(clean(null));
        Assertions.assertNull(clean(""));
        Assertions.assertNull(clean("   \n\t  "));
        Assertions.assertNull(clean(""));
    }

    /** An answer that is only a prefix is an answer that is nothing. */
    @Test
    public void aTitleThatIsOnlyAPrefixIsRejected() {
        Assertions.assertNull(clean("Title:"));
    }

    @Test
    public void capsAtEightyCharactersOnAWordBoundary() {
        String long_ = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicpi "
                + "rho sigma tau upsilon phi chi psi omega";
        String out = clean(long_);
        Assertions.assertNotNull(out);
        Assertions.assertTrue(out.length() <= 80, "too long: " + out.length());
        Assertions.assertFalse(out.endsWith(" "), "must be trimmed: [" + out + "]");
        // cut on a space, not mid-word: the character before the cut is a whole word
        Assertions.assertTrue(long_.startsWith(out));
        Assertions.assertNotEquals(' ', out.charAt(out.length() - 1));
    }

    @Test
    public void keepsNonLatinTextIntact() {
        Assertions.assertEquals("如何调整分区大小", clean("如何调整分区大小"));
        Assertions.assertEquals("تعديل حجم القسم", clean("«تعديل حجم القسم»"));
    }

    @Test
    public void collapsesInnerWhitespaceOfTheChosenLine() {
        Assertions.assertEquals("Resizing a partition", clean("  Resizing    a   partition  "));
    }

    /** The stored name is the shared rule, so a sanitized title must already satisfy it. */
    @Test
    public void resultIsAlwaysAValidSessionName() {
        String out = clean("Title: \"Resizing a\npartition today.\"\n");
        Assertions.assertNotNull(out);
        Assertions.assertEquals(out, net.thevpc.naru.api.agent.NaruSessionNames.normalize(out));
    }
}
