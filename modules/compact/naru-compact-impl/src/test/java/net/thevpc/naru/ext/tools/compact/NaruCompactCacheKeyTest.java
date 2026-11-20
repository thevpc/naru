package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruSummaryLevel;
import net.thevpc.naru.api.model.NaruSummaryOptions;
import net.thevpc.naru.api.model.NaruToolOutputPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The cache key, tested for the two ways it could hand back the wrong summary.
 *
 * <p>Everything here is a pure function of its arguments, which is the point: a cache key
 * nobody can reason about is a cache that will eventually serve a summary of text that no
 * longer exists.
 */
class NaruCompactCacheKeyTest {

    private static final NaruCompactCacheKey.NaruSummaryOutputKey OPTIONS =
            new NaruCompactCacheKey.NaruSummaryOutputKey("level=NORMAL");

    private static List<NaruMessage> items(String... contents) {
        List<NaruMessage> out = new java.util.ArrayList<>();
        for (String c : contents) {
            out.add(NaruMessage.user(c));
        }
        return out;
    }

    @Test
    void theSameContentAndOptionsGiveTheSameKey() {
        assertEquals(NaruCompactCacheKey.of(items("a", "b"), OPTIONS),
                NaruCompactCacheKey.of(items("a", "b"), OPTIONS));
    }

    @Test
    void editedContentIsAMiss() {
        // The failure this prevents: a key built from ids alone would keep returning a summary
        // of the pre-edit text, and nothing in the result would look wrong.
        assertNotEquals(NaruCompactCacheKey.of(items("a", "b"), OPTIONS),
                NaruCompactCacheKey.of(items("a", "b edited"), OPTIONS));
    }

    @Test
    void aDifferentItemCountIsAMiss() {
        assertNotEquals(NaruCompactCacheKey.of(items("a"), OPTIONS),
                NaruCompactCacheKey.of(items("a", "b"), OPTIONS));
    }

    @Test
    void reorderingIsAMiss() {
        assertNotEquals(NaruCompactCacheKey.of(items("a", "b"), OPTIONS),
                NaruCompactCacheKey.of(items("b", "a"), OPTIONS));
    }

    @Test
    void differentOptionsAreAMiss() {
        assertNotEquals(
                NaruCompactCacheKey.of(items("a"), new NaruCompactCacheKey.NaruSummaryOutputKey("level=NORMAL")),
                NaruCompactCacheKey.of(items("a"), new NaruCompactCacheKey.NaruSummaryOutputKey("level=TERSE")));
    }

    @Test
    void theOutputKeyDoesNotMentionTheModel() {
        // The design note says the model is not part of the key, because any model's summary
        // of the same content is as good as another's. That is only true if the options do
        // not carry the model either -- so this asserts the property the cache relies on,
        // rather than repeating the claim.
        assertFalse(NaruSummaryOptions.of().outputKey().contains("m1"));
        assertEquals(NaruSummaryOptions.of().withModels(List.of("m1")).outputKey(),
                NaruSummaryOptions.of().withModels(List.of("m2")).outputKey(),
                "a different model list must not change the output key");
        String withM1 = NaruCompactCacheKey.of(items("a"),
                new NaruCompactCacheKey.NaruSummaryOutputKey(
                        NaruSummaryOptions.of().withModels(List.of("m1")).outputKey()));
        String withM2 = NaruCompactCacheKey.of(items("a"),
                new NaruCompactCacheKey.NaruSummaryOutputKey(
                        NaruSummaryOptions.of().withModels(List.of("m2")).outputKey()));
        assertEquals(withM1, withM2,
                "the same content summarized by a different model shares one entry");
    }

    @Test
    void adjacentFieldsCannotBeConfusedForOneAnother() {
        // Length-prefixing is what stops ("ab","c") and ("a","bc") from hashing alike.
        assertNotEquals(NaruCompactCacheKey.contentHash(items("ab", "c")),
                NaruCompactCacheKey.contentHash(items("a", "bc")));
    }

    @Test
    void theContentHashIgnoresOptionsAndTheKeyDoesNot() {
        List<NaruMessage> covered = items("a", "b");
        String normal = NaruCompactCacheKey.of(covered, OPTIONS);
        String terse = NaruCompactCacheKey.of(covered,
                new NaruCompactCacheKey.NaruSummaryOutputKey("level=TERSE"));
        assertNotEquals(normal, terse);
        assertEquals(NaruCompactCacheKey.contentHash(covered), NaruCompactCacheKey.contentHash(covered));
    }

    @Test
    void theContentHashIsUnaffectedByToolOutputPolicy() {
        // The content hash is compared against a stored summary to detect an *edit* to history.
        // Changing how a later request would summarize must not make an untouched summary look
        // edited, or every re-request would report staleness.
        assertEquals(NaruCompactCacheKey.contentHash(items("a")),
                NaruCompactCacheKey.contentHash(items("a")));
        assertNotEquals(
                NaruSummaryOptions.of().withToolOutputs(NaruToolOutputPolicy.DROP).outputKey(),
                NaruSummaryOptions.of().withToolOutputs(NaruToolOutputPolicy.TRUNCATE).outputKey(),
                "precondition: tool output policy is an output-affecting option");
    }

    @Test
    void prefixKeysDifferByLength() {
        String h = NaruCompactCacheKey.contentHash(items("a", "b", "c"));
        assertNotEquals(NaruCompactCacheKey.prefixKey(h, 3), NaruCompactCacheKey.prefixKey(h, 30));
        assertEquals(NaruCompactCacheKey.prefixKey(h, 3), NaruCompactCacheKey.prefixKey(h, 3));
    }

    @Test
    void aToolResultAndAPlainMessageWithTheSameTextDoNotShareAKey() {
        NaruMessage plain = NaruMessage.user("output");
        NaruMessage tool = NaruMessage.tool("shell", "call-1", "output");
        assertNotEquals(NaruCompactCacheKey.of(List.of(plain), OPTIONS),
                NaruCompactCacheKey.of(List.of(tool), OPTIONS));
    }

    @Test
    void theKeyIsAFullLengthDigest() {
        String key = NaruCompactCacheKey.of(items("a"), OPTIONS);
        assertEquals(64, key.length());
        assertEquals(key, key.toLowerCase());
    }
}