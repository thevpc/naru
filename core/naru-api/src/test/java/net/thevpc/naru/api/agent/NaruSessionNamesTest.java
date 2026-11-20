package net.thevpc.naru.api.agent;

import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.util.NIllegalArgumentException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The one rule that four different doors share: the prompt, the desktop's inline edit,
 * {@code newSession().name(...)} and the titler's model output. If any of them normalized
 * differently, the store would hold names that only some callers can produce.
 */
public class NaruSessionNamesTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    @Test
    public void surroundingWhitespaceIsNotPartOfAName() {
        Assertions.assertEquals("LVM notes", NaruSessionNames.normalize("  LVM notes \n"));
    }

    /**
     * Folding rather than rejecting is the point: a model that answers on two lines, or a
     * title pasted out of a document, states one intention. A name is a label, not a
     * document.
     */
    @Test
    public void lineBreaksAndTabsFoldIntoSpaces() {
        Assertions.assertEquals("Resizing a partition on lvm2",
                NaruSessionNames.normalize("Resizing a partition\non\tlvm2"));
        Assertions.assertEquals("a  b", NaruSessionNames.normalize("a\r\nb"),
                "folding keeps both line breaks, it does not collapse them");
    }

    @Test
    public void ordinarySpacingInsideTheNameIsLeftAlone() {
        Assertions.assertEquals("a  b", NaruSessionNames.normalize("a  b"),
                "a double space is content, not slop; eating it silently would make the "
                        + "stored name differ from what the caller typed");
    }

    @Test
    public void aLongNameIsCappedAtTheMaxLength() {
        String long_ = "x".repeat(NaruSessionNames.MAX_LENGTH + 200);
        Assertions.assertEquals(NaruSessionNames.MAX_LENGTH,
                NaruSessionNames.normalize(long_).length());
    }

    @Test
    public void aCapThatCutsMidWordLeavesNoTrailingSpace() {
        String name = "a".repeat(NaruSessionNames.MAX_LENGTH - 1) + " b c";
        Assertions.assertEquals(name.substring(0, NaruSessionNames.MAX_LENGTH - 1),
                NaruSessionNames.normalize(name));
    }

    @Test
    public void nothingLeftIsAnErrorRatherThanAnEmptyName() {
        for (String candidate : new String[]{null, "", "   ", "\n\t\r"}) {
            Assertions.assertThrows(NIllegalArgumentException.class,
                    () -> NaruSessionNames.normalize(candidate),
                    "a session with no label is a store entry that cannot be listed: " + candidate);
        }
    }

    /**
     * The front ends use this to decide whether to send a rename at all, so it has to agree
     * with {@link #normalize} exactly rather than reimplement a looser version of it.
     */
    @Test
    public void isNormalizedAgreesWithNormalize() {
        Assertions.assertTrue(NaruSessionNames.isNormalized("LVM notes"));
        Assertions.assertFalse(NaruSessionNames.isNormalized("  LVM notes  "));
        Assertions.assertFalse(NaruSessionNames.isNormalized("a\nb"));
        Assertions.assertFalse(NaruSessionNames.isNormalized(null));
        Assertions.assertFalse(NaruSessionNames.isNormalized(""));
    }
}
