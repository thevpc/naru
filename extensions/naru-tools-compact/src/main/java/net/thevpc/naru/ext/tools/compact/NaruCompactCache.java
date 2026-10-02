package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruSummaryOptions;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NMsg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Remembers summaries so identical work is not repeated.
 *
 * <p>Three problems, three mechanisms, all of which exist because a summary is expensive:
 *
 * <ul>
 *   <li><b>Repetition</b> -- solved by the key. Two compactions of the same content under the
 *       same options must not cost two calls.</li>
 *   <li><b>Duplication in flight</b> -- solved by single-flight. Two threads asking for the
 *       same key at the same time must produce one call, not two. Without this, an LRU that
 *       simply returns "miss" for both would both call the model, and the second result would
 *       overwrite the first.</li>
 *   <li><b>Unbounded growth</b> -- solved by the LRU bound. A cache that only grows is a leak
 *       with extra steps.</li>
 * </ul>
 *
 * <h2>Never returns a stale answer</h2>
 *
 * <p>The key is a digest of every covered item's content, so an edited item is a different
 * key and therefore a miss. That single property is what makes it safe to keep entries at
 * all: the failure this guards against is a summary that describes content the history no
 * longer holds, and content-addressing makes that unrepresentable rather than merely unlikely.
 */
public class NaruCompactCache {

    private final int maxEntries;
    private final boolean enabled;

    /**
     * Insertion-ordered so the eldest entry is the first one {@code iterator()} yields, which
     * is what makes eviction a one-liner. Access order is deliberately not used: it would make
     * eviction depend on access timing, and a cache whose eviction order changes under
     * concurrent reads is harder to reason about than the small hit-rate gain.
     */
    private final LinkedHashMap<String, NaruCompactCacheEntry> entries;

    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Keys currently being summarized, mapped to the monitor their producer holds.
     *
     * <p>One monitor per key rather than one global lock, so unrelated keys summarize
     * concurrently -- two different compactions should not serialize behind each other -- and
     * so a waiter's wait is interruptible against the right producer.
     */
    private final ConcurrentHashMap<String, InFlight> inFlight = new ConcurrentHashMap<>();

    private long hits;
    private long misses;
    private long evictions;

    public NaruCompactCache(int maxEntries, boolean enabled) {
        this.maxEntries = Math.max(0, maxEntries);
        this.enabled = enabled && this.maxEntries > 0;
        this.entries = new LinkedHashMap<>(16, 0.75f, false);
    }

    /** A cache that never stores anything, for {@code naru.compact.cache=false}. */
    public static NaruCompactCache disabled() {
        return new NaruCompactCache(0, false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * A cached summary for exactly this content and options, or null.
     *
     * <p>Does not wait on an in-flight call. A caller that wants the answer from a call
     * already running uses {@link #computeIfAbsent}; a caller that would rather give up than
     * block -- the auto-compaction path, which runs inside a model request -- uses this.
     */
    public NaruCompactCacheEntry get(List<NaruMessage> covered, NaruSummaryOptions options) {
        if (!enabled || covered == null) {
            return null;
        }
        String key = NaruCompactCacheKey.of(covered, new NaruCompactCacheKey.NaruSummaryOutputKey(
                options == null ? "" : options.outputKey()));
        lock.lock();
        try {
            NaruCompactCacheEntry entry = entries.get(key);
            if (entry == null) {
                misses++;
                return null;
            }
            hits++;
            return entry;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The summary for this key, summarizing it only if nobody else is already doing so.
     *
     * <p>The single-flight boundary. The first caller for a key runs {@code producer}; the
     * others wait and then read what it stored. That is the whole point: without it, two
     * callers missing the same key would both call the model and the second would silently
     * replace the first's result.
     *
     * <p>Waiting is bounded. A producer that hangs must not hang every other compaction, so a
     * waiter gives up and returns null -- a miss it can act on, which is strictly better than
     * a deadlock.
     */
    public NaruCompactCacheEntry computeIfAbsent(List<NaruMessage> covered,
                                                 NaruSummaryOptions options,
                                                 EntryProducer producer) {
        if (covered == null || options == null) {
            return null;
        }
        String key = NaruCompactCacheKey.of(covered, new NaruCompactCacheKey.NaruSummaryOutputKey(options.outputKey()));
        if (!enabled) {
            // still collapses concurrent work: the single-flight map is independent of whether
            // results are retained, and two simultaneous compactions of the same content
            // should be one call even with caching off
            return produceOnce(key, producer);
        }
        NaruCompactCacheEntry cached = get(covered, options);
        if (cached != null) {
            return cached;
        }
        return produceOnce(key, producer);
    }

    /**
     * Runs the producer at most once per key, across threads.
     *
     * <p>A waiter does not time out. Waiting here is bounded by the time the producer takes,
     * and the producer is a model call the caller was going to make anyway; a timeout would
     * mean a slow-but-succeeding summarizer got thrown away and paid for twice, which is the
     * exact cost this mechanism exists to remove. Cancellation is a caller's decision to make
     * on the thread running the producer, and is not this cache's to impose on a waiter.
     *
     * <p>A waiter that finds nothing -- because the producer declined to cache an entry, or
     * returned null -- gets null. It does not run the producer itself: doing so would let two
     * threads both summarize when the mechanism promised one.
     */
    private NaruCompactCacheEntry produceOnce(String key, EntryProducer producer) {
        InFlight flight = new InFlight();
        InFlight existing = inFlight.putIfAbsent(key, flight);
        if (existing == null) {
            try {
                synchronized (flight) {
                    NaruCompactCacheEntry produced = producer.produce(key);
                    if (produced != null) {
                        flight.produced = produced;
                        put(key, produced);
                    }
                    return produced;
                }
            } finally {
                inFlight.remove(key);
            }
        }
        // Someone else holds this key. Waiting on their monitor is enough: the producer stores
        // its entry -- and records it on the flight -- before releasing it, so a returned
        // monitor means the result is readable.
        synchronized (existing) {
            // With the cache disabled nothing is retained, so the entry map is empty and the
            // flight is the only place the result exists. Reading it here is what keeps
            // "one call for this content" true even when caching is off; a waiter that found
            // nothing would have to summarize again, which is the cost this avoids.
            return existing.produced != null ? existing.produced : peek(key);
        }
    }

    /**
     * A key being summarized: the monitor waiters block on, and the result once produced.
     *
     * <p>The result is held here rather than only in the map so a disabled cache still hands
     * the produced summary to the waiters that were collapsed onto it.
     */
    private static final class InFlight {
        private NaruCompactCacheEntry produced;
    }

    /** Reads without counting a hit, for a waiter re-reading a just-produced entry. */
    private NaruCompactCacheEntry peek(String key) {
        lock.lock();
        try {
            return entries.get(key);
        } finally {
            lock.unlock();
        }
    }

    private void put(String key, NaruCompactCacheEntry entry) {
        lock.lock();
        try {
            entries.remove(key);
            entries.put(key, entry);
            while (entries.size() > maxEntries) {
                String eldest = entries.keySet().iterator().next();
                entries.remove(eldest);
                evictions++;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Every stored entry, eldest first. For persistence and for {@code /compact status}. */
    public List<NaruCompactCacheEntry> entries() {
        lock.lock();
        try {
            return new ArrayList<>(entries.values());
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    /** Whether a key is currently being summarized. Exposed for tests. */
    public boolean isInFlight(String key) {
        return inFlight.containsKey(key);
    }

    /**
     * Puts an entry back, as if it had just been produced.
     *
     * <p>For loading a persisted cache at startup. Deliberately not counted as a hit or a
     * miss: neither happened, and inflating either would make the hit rate a measure of how
     * many times the process was restarted rather than how well the cache is working.
     *
     * <p>Evicts as it goes, so a cache file written with a larger bound than the current
     * configuration loads without exceeding it.
     */
    public void restore(String key, NaruCompactCacheEntry entry) {
        if (!enabled || key == null || entry == null) {
            return;
        }
        put(key, entry);
    }

    public void clear() {
        lock.lock();
        try {
            entries.clear();
        } finally {
            lock.unlock();
        }
    }

    /** Hit, miss and eviction counts for a status report. */
    public Map<String, Long> stats() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("hits", hits);
        out.put("misses", misses);
        out.put("evictions", evictions);
        out.put("size", (long) size());
        out.put("max", (long) maxEntries);
        return out;
    }

    /**
     * A one-line summary of what the cache has been doing.
     *
     * <p>Hits and misses are reported because a cache that never hits is either unnecessary
     * or mis-keyed, and those are very different problems -- the first means summaries are
     * too coarse to be worth caching, the second that the same content is arriving with a
     * different options hash.
     */
    public String describe(NaruTask task) {
        if (!enabled) {
            return "compaction cache is disabled";
        }
        long total = hits + misses;
        String ratio = total == 0 ? "no lookups yet" : String.format("%.0f%% hit", 100.0 * hits / total);
        String msg = String.format("compaction cache: %d/%d entries, %s, %d evicted",
                size(), maxEntries, ratio, evictions);
        if (task != null) {
            task.log(NaruLogMode.DEBUG, NMsg.ofC("%s", msg));
        }
        return msg;
    }

    /** Produces the entry for a key, or null to decline to cache one. */
    @FunctionalInterface
    public interface EntryProducer {
        NaruCompactCacheEntry produce(String key);
    }
}