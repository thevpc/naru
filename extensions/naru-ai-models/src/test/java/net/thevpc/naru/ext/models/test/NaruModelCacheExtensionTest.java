package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.ext.models.cache.NaruCacheBaseline;
import net.thevpc.naru.ext.models.cache.NaruCacheKeyChain;
import net.thevpc.naru.ext.models.cache.NaruModelCacheExtension;
import net.thevpc.naru.api.model.NaruContextSegment;
import net.thevpc.naru.api.model.NaruCacheableContext;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruSegmentContent;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Persistence behaviour of the per-session cache bookkeeping.
 *
 * <p>Round-trips through a real file rather than asserting on an object graph,
 * because the failure that matters is a state file the next process cannot read
 * — and a shape that only looks right in memory proves nothing about that.
 */
public class NaruModelCacheExtensionTest {

    @BeforeAll
    public static void setUp() {
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

    private static NaruCacheableContext context() {
        return NaruCacheableContext.builder()
                .cacheableMessages("sys", List.of(NaruMessage.system("You are helpful.")))
                .cacheableMessages("docs", List.of(NaruMessage.system("Project notes.")))
                .volatileMessages("tail", List.of(NaruMessage.user("hi")))
                .build();
    }

    private static void writeTo(NPath file, NaruModelCacheExtension ext) {
        NElement saved = ext.save(null);
        Assertions.assertNotNull(saved, "expected state to be written");
        file.writeString(NElementWriter.ofTson().formatPlain(saved));
    }

    @Test
    public void survivesRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("naru-cache");
        NPath file = NPath.of(dir.resolve("model-cache.tson"));

        NaruModelCacheExtension writer = new NaruModelCacheExtension();
        List<String> keys = NaruCacheKeyChain.chain(context().segments());
        writer.commit("openrouter", "gpt-x", NaruCachingMode.AUTOMATIC_PREFIX, keys, 1000L);
        writer.save(null);
        writeTo(file, writer);

        NaruModelCacheExtension reader = new NaruModelCacheExtension();
        reader.load(null, file);

        NaruCacheBaseline restored = reader.baseline("openrouter", "gpt-x", NaruCachingMode.AUTOMATIC_PREFIX);
        Assertions.assertNotNull(restored, "baseline should have been restored");
        Assertions.assertEquals(keys, restored.getSegmentKeys(),
                "the chain must survive serialisation byte-for-byte or every next turn is a false miss");
    }

    @Test
    public void keysAreScopedPerProviderModelAndMode() {
        NaruModelCacheExtension ext = new NaruModelCacheExtension();
        List<String> keys = NaruCacheKeyChain.chain(context().segments());
        ext.commit("openrouter", "gpt-x", NaruCachingMode.AUTOMATIC_PREFIX, keys, 1L);

        Assertions.assertNotNull(ext.baseline("openrouter", "gpt-x", NaruCachingMode.AUTOMATIC_PREFIX));
        Assertions.assertNull(ext.baseline("openrouter", "other-model", NaruCachingMode.AUTOMATIC_PREFIX),
                "switching models must not reuse another model's prefix");
        Assertions.assertNull(ext.baseline("groq", "gpt-x", NaruCachingMode.AUTOMATIC_PREFIX),
                "switching providers must not reuse another provider's prefix");
        Assertions.assertNull(ext.baseline("openrouter", "gpt-x", NaruCachingMode.EXPLICIT_INLINE),
                "changing mode must invalidate, since the wire format differs");
    }

    @Test
    public void committingNothingWritesNoFile() {
        NaruModelCacheExtension ext = new NaruModelCacheExtension();
        Assertions.assertNull(ext.save(null),
                "an empty extension must ask the core to remove any stale state file");
    }

    @Test
    public void corruptStateDegradesToColdCache() throws Exception {
        Path dir = Files.createTempDirectory("naru-cache-bad");
        Files.writeString(dir.resolve("model-cache.tson"), "{ this is not the tson you are looking for");
        NPath file = NPath.of(dir.resolve("model-cache.tson"));

        NaruModelCacheExtension ext = new NaruModelCacheExtension();
        ext.load(null, file);

        // The only acceptable outcome: no state, so a full resend. A corrupt
        // hint must never be allowed to fail a session.
        Assertions.assertNull(ext.baseline("openrouter", "gpt-x", NaruCachingMode.AUTOMATIC_PREFIX));
    }

    @Test
    public void missingFileIsNotAnError() {
        NaruModelCacheExtension ext = new NaruModelCacheExtension();
        NOptional<NElement> r = ext.load(null, null);
        Assertions.assertFalse(r.isPresent());
        Assertions.assertNull(ext.baseline("openrouter", "gpt-x", NaruCachingMode.AUTOMATIC_PREFIX));
    }

    @Test
    public void resourceIsClearedWithoutLosingTheChain() {
        NaruModelCacheExtension ext = new NaruModelCacheExtension();
        List<String> keys = NaruCacheKeyChain.chain(context().segments());
        ext.commit("gemini", "g-2.5", NaruCachingMode.EXPLICIT_RESOURCE, keys, 1L);
        ext.commitResource("gemini", "g-2.5", NaruCachingMode.EXPLICIT_RESOURCE,
                "cachedContents/abc", 9_999_999L, keys, 2L);

        NaruCacheBaseline withResource = ext.baseline("gemini", "g-2.5", NaruCachingMode.EXPLICIT_RESOURCE);
        Assertions.assertEquals("cachedContents/abc", withResource.getCachedResourceId());
        Assertions.assertTrue(withResource.isResourceUsable(1_000L, 60_000L));

        ext.clearResource("gemini", "g-2.5", NaruCachingMode.EXPLICIT_RESOURCE);
        NaruCacheBaseline cleared = ext.baseline("gemini", "g-2.5", NaruCachingMode.EXPLICIT_RESOURCE);
        Assertions.assertNull(cleared.getCachedResourceId());
        Assertions.assertEquals(keys, cleared.getSegmentKeys(),
                "forgetting a resource id must not throw away a still-valid prefix");
    }

    @Test
    public void expiredResourceIsNotUsable() {
        List<String> keys = NaruCacheKeyChain.chain(context().segments());
        // expires at 1_000_000, safety margin 60s
        NaruCacheBaseline b = new NaruCacheBaseline("gemini", "g-2.5", NaruCachingMode.EXPLICIT_RESOURCE,
                keys, "cachedContents/abc", 1_000_000L, 0L);
        // comfortably before expiry-minus-margin: usable
        Assertions.assertTrue(b.isResourceUsable(100_000L, 60_000L));
        // past expiry
        Assertions.assertFalse(b.isResourceUsable(1_000_001L, 60_000L));
        // inside the safety margin: a slow turn must not start on a dying resource
        Assertions.assertFalse(b.isResourceUsable(950_000L, 60_000L));
        // no resource at all
        NaruCacheBaseline noResource = new NaruCacheBaseline("gemini", "g-2.5",
                NaruCachingMode.EXPLICIT_RESOURCE, keys, null, -1L, 0L);
        Assertions.assertFalse(noResource.isResourceUsable(1L, 60_000L));
    }

    @Test
    public void differentSegmentOrderIsADifferentChain() {
        NaruMessage sys = NaruMessage.system("You are helpful.");
        NaruMessage docs = NaruMessage.system("Project notes.");
        NaruCacheableContext a = NaruCacheableContext.builder()
                .cacheableMessages("sys", List.of(sys))
                .cacheableMessages("docs", List.of(docs))
                .build();
        NaruCacheableContext b = NaruCacheableContext.builder()
                .cacheableMessages("docs", List.of(docs))
                .cacheableMessages("sys", List.of(sys))
                .build();
        List<String> ka = NaruCacheKeyChain.chain(a.segments());
        List<String> kb = NaruCacheKeyChain.chain(b.segments());
        Assertions.assertNotEquals(ka, kb,
                "reordered segments change the wire order and must not compare as a hit");
    }

    @Test
    public void segmentWithSameContentButDifferentIdHashesIdentically() {
        // ids are labels for humans and for the session file; they must not
        // affect whether the provider sees the same bytes
        NaruMessage m = NaruMessage.user("hello");
        NaruContextSegment s1 = NaruContextSegment.cacheable("alpha", NaruSegmentContent.Messages.of(List.of(m)));
        NaruContextSegment s2 = NaruContextSegment.cacheable("beta", NaruSegmentContent.Messages.of(List.of(m)));
        List<String> k1 = NaruCacheKeyChain.chain(List.of(s1));
        List<String> k2 = NaruCacheKeyChain.chain(List.of(s2));
        Assertions.assertEquals(k1, k2);
    }
}
