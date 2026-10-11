package net.thevpc.naru.ext.tools.compact;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementFormatterStyle;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NPairElement;
import net.thevpc.nuts.io.NPath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The on-disk half of the summary cache.
 *
 * <p>A single TSON object of {@code key -> entry}, beside the session rather than inside it.
 * <b>Outside</b> is the important part: a cache entry is derived data, and putting it in the
 * session would make it versioned history. Compaction is then no longer purely additive --
 * rolling back a session would roll back the cache, and every subsequent summary would be
 * recomputed. Worse, a cache file in the session directory would be walked by anything that
 * enumerates session files.
 *
 * <p>Written on eviction and on shutdown rather than on every put. A put is on the hot path
 * of a model call, and fsyncing a file per summary would make compaction slower than the
 * work it saves. Losing the last few entries to a crash costs only recomputation.
 *
 * <p>Every failure here is swallowed. A cache that cannot be read or written must still allow
 * compaction -- it is an optimization, and a corrupt cache file should not make a session
 * uncompacting forever.
 */
public class NaruCompactCacheStore {

    /** Entries above this many are dropped on load, so a hand-edited file cannot stall us. */
    private static final int MAX_LOADED = 5_000;

    private final NPath file;
    private final int maxEntries;
    private volatile boolean dirty;

    public NaruCompactCacheStore(NPath file, int maxEntries) {
        this.file = file;
        this.maxEntries = Math.max(0, maxEntries);
    }

    /**
     * Reads the cache, newest-first ordering irrelevant.
     *
     * <p>Insertion order from the file is preserved so that eviction stays meaningful across
     * restarts: the file is written in eldest-first order, so reloading it in file order
     * keeps the same entries getting evicted.
     */
    public Map<String, NaruCompactCacheEntry> load() {
        Map<String, NaruCompactCacheEntry> out = new LinkedHashMap<>();
        if (file == null || !file.isRegularFile()) {
            return out;
        }
        try {
            NElement element = NElementReader.ofTson().read(file);
            if (!(element instanceof NObjectElement)) {
                return out;
            }
            int count = 0;
            for (NPairElement pair : ((NObjectElement) element).namedPairs()) {
                if (count++ >= MAX_LOADED) {
                    break;
                }
                String key = pair.key().asStringValue().orNull();
                if (key == null) {
                    continue;
                }
                NaruCompactCacheEntry entry = fromElement(key, pair.value());
                if (entry != null) {
                    out.put(key, entry);
                }
            }
        } catch (Exception e) {
            // unreadable cache: start empty rather than fail
            return new LinkedHashMap<>();
        }
        return out;
    }

    /**
     * Writes the cache, oldest entries first.
     *
     * <p>The order matters in both directions. Written oldest-first because
     * {@link #load()} re-inserts in file order, and eviction takes the eldest insertion: a
     * file written newest-first would make the newest entries the ones evicted on the first
     * insert after a restart.
     *
     * <p>Trimming takes from the <em>front</em> for the same reason: when the file holds more
     * entries than the current bound allows, the ones to drop are the oldest, and dropping
     * the newest would throw away exactly the summaries most likely to be asked for again.
     */
    public void save(Map<String, NaruCompactCacheEntry> entries) {
        if (file == null || entries == null) {
            return;
        }
        try {
            NObjectElementBuilder root = NObjectElementBuilder.of();
            int skip = Math.max(0, entries.size() - maxEntries);
            int written = 0;
            for (Map.Entry<String, NaruCompactCacheEntry> e : entries.entrySet()) {
                if (skip > 0) {
                    skip--;
                    continue;
                }
                if (written++ >= maxEntries) {
                    break;
                }
                root.set(e.getKey(), toElement(e.getValue()));
            }
            // the writer creates missing parents; the directory is created here anyway so an
            // unwritable path fails at the mkdirs with a clearer cause than at the write
            NPath dir = file.parent();
            if (dir != null) {
                dir.mkdirs();
            }
            NElementWriter.ofTson().ntf(false)
                    .formatter(NElementFormatterStyle.PRETTY)
                    .write(root.build(), file);
            dirty = false;
        } catch (Exception e) {
            // a cache that cannot be written is still a cache that works, just in memory
        }
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public NPath file() {
        return file;
    }

    private static NElement toElement(NaruCompactCacheEntry entry) {
        NObjectElementBuilder b = NObjectElementBuilder.of();
        b.set("text", entry.summaryText);
        b.set("tokens", entry.summaryTokens);
        // null-tolerant on read: an entry written by an older build, or hand-edited, must not
        // be a reason to drop the whole cache
        if (entry.modelUsed != null) {
            b.set("model", entry.modelUsed);
        }
        b.set("ok", entry.ok);
        b.set("folded", entry.folded);
        return b.build();
    }

    private static NaruCompactCacheEntry fromElement(String key, NElement element) {
        if (element == null || !element.isObject()) {
            return null;
        }
        NObjectElement o = element.asObject().get();
        String text = o.getStringValue("text").orNull();
        if (text == null) {
            return null;
        }
        Long tokens = o.getLongValue("tokens").orNull();
        return new NaruCompactCacheEntry(
                key,
                text,
                tokens == null ? NaruCompactTokens.estimate(text) : tokens,
                o.getStringValue("model").orNull(),
                o.getBooleanValue("ok").orElse(true),
                o.getBooleanValue("folded").orElse(false));
    }

    /** Keys only, for a status report that should not dump summary text. */
    public static List<String> keys(Map<String, NaruCompactCacheEntry> entries) {
        return new ArrayList<>(entries.keySet());
    }
}