package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruSummaryOptions;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache: single-flight, eviction, and surviving a restart.
 *
 * <p>The properties here are the ones a summary cache can get wrong in a way nobody notices
 * until a bill arrives: summarizing the same content twice because two requests raced, and
 * keeping the entries nobody asked for again.
 */
class NaruCompactCacheTest {

    private static final NaruSummaryOptions OPTIONS = NaruSummaryOptions.of();

    @BeforeAll
    public static void setUpWorkspace() {
        // NPath.ofTempFolder resolves through the Nuts workspace, so the cache tests need one
        // even though they touch no model and no session.
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

    private static List<NaruMessage> items(String... contents) {
        List<NaruMessage> out = new ArrayList<>();
        for (String c : contents) {
            out.add(NaruMessage.user(c));
        }
        return out;
    }

    private static NaruCompactCacheEntry entry(String key, String text) {
        return new NaruCompactCacheEntry(key, text, NaruCompactTokens.estimate(text), "m", true, false);
    }

    private static String keyOf(List<NaruMessage> covered) {
        return NaruCompactCacheKey.of(covered, new NaruCompactCacheKey.NaruSummaryOutputKey(
                OPTIONS.outputKey()));
    }


    /**
     * Waits until every listed thread has reached the cache and parked there.
     *
     * <p>Single-flight only collapses callers that genuinely overlap, so the threads have to
     * all be inside the cache before the producer is released. Waiting on their states is what
     * makes that deterministic: a caller still on its way to {@code computeIfAbsent} would
     * otherwise arrive after the key was released and become a second producer, and the
     * assertion would be a coin toss.
     *
     * <p>A thread holding the producer is parked in {@code release.await} and reads as
     * TIMED_WAITING; a collapsed caller is parked on the producer's monitor and reads as
     * BLOCKED. Any thread still runnable means the arrangement did not happen.
     *
     * @param expectedBlocked how many callers must be collapsed onto an in-flight call
     */
    private static void awaitOverlap(List<Thread> threads, int expectedBlocked) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            boolean allParked = true;
            for (Thread t : threads) {
                Thread.State s = t.getState();
                if (s != Thread.State.BLOCKED && s != Thread.State.TIMED_WAITING
                        && s != Thread.State.WAITING) {
                    allParked = false;
                    break;
                }
            }
            if (allParked) {
                break;
            }
            Thread.onSpinWait();
        }
        int blocked = 0;
        for (Thread t : threads) {
            Thread.State s = t.getState();
            if (s == Thread.State.BLOCKED) {
                blocked++;
            } else {
                assertTrue(s == Thread.State.TIMED_WAITING || s == Thread.State.WAITING,
                        "a thread was still running inside the cache: " + s);
            }
        }
        assertEquals(expectedBlocked, blocked,
                "expected " + expectedBlocked + " collapsed callers");
    }

    // ── basics ───────────────────────────────────────────────────────────────

    @Test
    void aMissThenAHit() {
        NaruCompactCache cache = new NaruCompactCache(10, true);
        List<NaruMessage> covered = items("a");
        assertNull(cache.get(covered, OPTIONS));
        NaruCompactCacheEntry produced = cache.computeIfAbsent(covered, OPTIONS,
                k -> entry(k, "the summary"));
        assertNotNull(produced);
        assertEquals("the summary", cache.get(covered, OPTIONS).summaryText);
        assertEquals(1, cache.stats().get("hits").longValue());
        // computeIfAbsent probes the cache before it produces, so the miss count is not pinned
        // to one lookup; what matters is that the hit was counted as a hit.
        assertTrue(cache.stats().get("misses").longValue() >= 1);
    }

    @Test
    void aProducerRunsOnceForTheSameContent() {
        NaruCompactCache cache = new NaruCompactCache(10, true);
        AtomicInteger calls = new AtomicInteger();
        List<NaruMessage> covered = items("a");
        for (int i = 0; i < 5; i++) {
            cache.computeIfAbsent(covered, OPTIONS, k -> {
                calls.incrementAndGet();
                return entry(k, "the summary");
            });
        }
        assertEquals(1, calls.get(), "a second call would be paid for and would overwrite the first");
    }

    @Test
    void differentContentIsADifferentEntry() {
        NaruCompactCache cache = new NaruCompactCache(10, true);
        AtomicInteger calls = new AtomicInteger();
        cache.computeIfAbsent(items("a"), OPTIONS, k -> {
            calls.incrementAndGet();
            return entry(k, "one");
        });
        cache.computeIfAbsent(items("b"), OPTIONS, k -> {
            calls.incrementAndGet();
            return entry(k, "two");
        });
        assertEquals(2, calls.get());
        assertEquals(2, cache.size());
    }

    @Test
    void aNullProducerResultIsNotCached() {
        // A summarizer that declines -- rate limited, over budget -- must not leave an entry
        // that makes the next attempt look like a hit.
        NaruCompactCache cache = new NaruCompactCache(10, true);
        assertNull(cache.computeIfAbsent(items("a"), OPTIONS, k -> null));
        assertEquals(0, cache.size());
        assertNull(cache.get(items("a"), OPTIONS));
    }

    // ── disabled ─────────────────────────────────────────────────────────────

    @Test
    void aDisabledCacheStoresNothingButStillSummarizes() {
        NaruCompactCache cache = NaruCompactCache.disabled();
        assertFalse(cache.isEnabled());
        assertNotNull(cache.computeIfAbsent(items("a"), OPTIONS, k -> entry(k, "s")));
        assertEquals(0, cache.size());
        assertNull(cache.get(items("a"), OPTIONS));
        assertEquals("compaction cache is disabled", cache.describe(null));
    }

    @Test
    void aDisabledCacheStillCollapsesConcurrentWorkAndSharesTheResult() throws Exception {
        // Caching is off, but two simultaneous requests for the same content must still be one
        // call -- and both must end up with its result, not one of them with nothing.
        NaruCompactCache cache = NaruCompactCache.disabled();
        List<NaruMessage> covered = items("a", "b");
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        NaruCompactCache.EntryProducer slow = k -> {
            calls.incrementAndGet();
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return entry(k, "shared summary");
        };
        List<Thread> threads = new ArrayList<>();
        List<NaruCompactCacheEntry> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> results.add(cache.computeIfAbsent(covered, OPTIONS, slow)));
            threads.add(t);
            t.start();
        }
        assertTrue(inside.await(10, TimeUnit.SECONDS), "the producer should have started");
        awaitOverlap(threads, 3);
        release.countDown();
        for (Thread t : threads) {
            t.join(10_000);
        }
        assertEquals(1, calls.get());
        assertEquals(4, results.size());
        for (NaruCompactCacheEntry r : results) {
            assertNotNull(r, "every caller that waited must receive the produced summary");
            assertEquals("shared summary", r.summaryText);
        }
    }

    // ── single-flight under concurrency ──────────────────────────────────────

    @Test
    void concurrentCallersMakeOneCallAndAllGetTheAnswer() throws Exception {
        NaruCompactCache cache = new NaruCompactCache(10, true);
        List<NaruMessage> covered = items("x", "y", "z");
        int callers = 6;
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<NaruCompactCacheEntry> results = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            Thread t = new Thread(() -> results.add(cache.computeIfAbsent(covered, OPTIONS, k -> {
                calls.incrementAndGet();
                inside.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return entry(k, "once");
            })));
            threads.add(t);
            t.start();
        }
        assertTrue(inside.await(10, TimeUnit.SECONDS));
        awaitOverlap(threads, callers - 1);
        release.countDown();
        for (Thread t : threads) {
            t.join(10_000);
        }
        assertEquals(1, calls.get());
        assertEquals(callers, results.size());
        for (NaruCompactCacheEntry r : results) {
            assertNotNull(r);
            assertEquals("once", r.summaryText);
        }
        assertFalse(cache.isInFlight(keyOf(covered)), "the key is released once the call ends");
    }

    @Test
    void anUnrelatedKeySummarizesInParallel() throws Exception {
        // Single-flight is per key. A global lock here would serialize every compaction behind
        // whichever one happened to start first.
        NaruCompactCache cache = new NaruCompactCache(10, true);
        CountDownLatch bothInside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (String content : new String[]{"a", "b"}) {
            Thread t = new Thread(() -> cache.computeIfAbsent(items(content), OPTIONS, k -> {
                bothInside.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return entry(k, content);
            }));
            threads.add(t);
            t.start();
        }
        assertTrue(bothInside.await(10, TimeUnit.SECONDS),
                "two different keys must be able to summarize at the same time");
        // Both are producers here: different keys must not queue behind each other.
        awaitOverlap(threads, 0);
        release.countDown();
        for (Thread t : threads) {
            t.join(10_000);
        }
    }

    @Test
    void aFailedSummaryDoesNotPoisonTheKey() {
        NaruCompactCache cache = new NaruCompactCache(10, true);
        AtomicInteger calls = new AtomicInteger();
        assertNull(cache.computeIfAbsent(items("a"), OPTIONS, k -> {
            calls.incrementAndGet();
            return null;
        }));
        assertNotNull(cache.computeIfAbsent(items("a"), OPTIONS, k -> {
            calls.incrementAndGet();
            return entry(k, "second attempt");
        }));
        assertEquals(2, calls.get(), "the retry must be allowed to try again");
    }

    // ── eviction ─────────────────────────────────────────────────────────────

    @Test
    void theBoundIsRespectedAndTheEldestGoes() {
        NaruCompactCache cache = new NaruCompactCache(3, true);
        for (String c : new String[]{"a", "b", "c", "d", "e"}) {
            cache.computeIfAbsent(items(c), OPTIONS, k -> entry(k, c));
        }
        assertEquals(3, cache.size());
        assertEquals(2, cache.stats().get("evictions").longValue());
        assertNull(cache.get(items("a"), OPTIONS), "the eldest was evicted");
        assertNotNull(cache.get(items("e"), OPTIONS), "the newest is still there");
    }

    @Test
    void restoringRespectsTheCurrentBoundEvenIfTheFileWasBigger() {
        NaruCompactCache cache = new NaruCompactCache(2, true);
        Map<String, NaruCompactCacheEntry> many = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) {
            many.put("k" + i, entry("k" + i, "s" + i));
        }
        many.forEach(cache::restore);
        assertEquals(2, cache.size());
    }

    @Test
    void restoredEntriesCountAsNeitherHitsNorMisses() {
        // Otherwise the hit rate measures how many times the process restarted.
        NaruCompactCache cache = new NaruCompactCache(10, true);
        cache.restore("k", entry("k", "s"));
        assertEquals(0, cache.stats().get("hits").longValue());
        assertEquals(0, cache.stats().get("misses").longValue());
        assertEquals(0, cache.stats().get("evictions").longValue());
    }

    @Test
    void clearEmptiesTheCache() {
        NaruCompactCache cache = new NaruCompactCache(10, true);
        cache.computeIfAbsent(items("a"), OPTIONS, k -> entry(k, "s"));
        cache.clear();
        assertEquals(0, cache.size());
    }

    // ── persistence ──────────────────────────────────────────────────────────

    @Test
    void entriesSurviveAWriteAndAReload() {
        NPath file = NPath.ofTempFolder("naru-compact-cache-" + System.nanoTime())
                .resolve("compact.tson");
        try {
            NaruCompactCache cache = new NaruCompactCache(10, true);
            cache.computeIfAbsent(items("a"), OPTIONS, k -> entry(k, "kept across restart"));
            cache.computeIfAbsent(items("b"), OPTIONS, k -> entry(k, "also kept"));
            Map<String, NaruCompactCacheEntry> snapshot = new LinkedHashMap<>();
            for (NaruCompactCacheEntry e : cache.entries()) {
                snapshot.put(e.key, e);
            }
            new NaruCompactCacheStore(file, 10).save(snapshot);
            assertTrue(file.isRegularFile());

            NaruCompactCacheStore store = new NaruCompactCacheStore(file, 10);
            Map<String, NaruCompactCacheEntry> loaded = store.load();
            assertEquals(2, loaded.size());
            NaruCompactCache reloaded = new NaruCompactCache(10, true);
            loaded.forEach(reloaded::restore);
            assertEquals(0, reloaded.stats().get("hits").longValue(),
                    "loading is not a lookup");
            assertEquals("kept across restart",
                    reloaded.get(items("a"), OPTIONS).summaryText);
        } finally {
            if (file != null && file.parent() != null) {
                file.parent().deleteTree();
            }
        }
    }

    @Test
    void aMissingOrUnreadableCacheFileIsNotAnError() {
        NPath file = NPath.ofTempFolder("naru-compact-cache-missing-" + System.nanoTime())
                .resolve("nope.tson");
        assertEquals(0, new NaruCompactCacheStore(file, 10).load().size());
        // a directory where the file should be: the read fails, and that must not propagate
        NPath dir = NPath.ofTempFolder("naru-compact-cache-dir-" + System.nanoTime());
        dir.mkdirs();
        try {
            assertEquals(0, new NaruCompactCacheStore(dir, 10).load().size());
        } finally {
            dir.deleteTree();
        }
    }

    @Test
    void aFileBiggerThanTheBoundKeepsTheNewestEntries() {
        // Trimming the wrong end throws away the summaries most likely to be requested again,
        // and looks fine on paper because the file is still populated.
        NPath file = NPath.ofTempFolder("naru-compact-cache-trim-" + System.nanoTime())
                .resolve("compact.tson");
        try {
            Map<String, NaruCompactCacheEntry> entries = new LinkedHashMap<>();
            for (int i = 0; i < 10; i++) {
                entries.put("k" + i, entry("k" + i, "text " + i));
            }
            NaruCompactCacheStore store = new NaruCompactCacheStore(file, 4);
            store.save(entries);
            Map<String, NaruCompactCacheEntry> loaded = store.load();
            assertEquals(4, loaded.size());
            assertTrue(loaded.containsKey("k9"), "the newest entry must survive the trim");
            assertFalse(loaded.containsKey("k0"));
            assertEquals("k6", new ArrayList<>(loaded.keySet()).get(0),
                    "file order stays oldest-first so eviction order survives the restart");
        } finally {
            if (file != null && file.parent() != null) {
                file.parent().deleteTree();
            }
        }
    }

    @Test
    void theDirtyFlagTracksWhetherASaveIsNeeded() {
        NPath file = NPath.ofTempFolder("naru-compact-cache-dirty-" + System.nanoTime())
                .resolve("compact.tson");
        try {
            NaruCompactCacheStore store = new NaruCompactCacheStore(file, 10);
            assertFalse(store.isDirty());
            store.markDirty();
            assertTrue(store.isDirty());
            store.save(Map.of("k", entry("k", "s")));
            assertFalse(store.isDirty(), "a completed save clears the flag");
        } finally {
            if (file != null && file.parent() != null) {
                file.parent().deleteTree();
            }
        }
    }
}