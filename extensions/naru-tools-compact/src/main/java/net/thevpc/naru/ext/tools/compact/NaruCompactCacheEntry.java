package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruSummaryInfo;
import net.thevpc.naru.api.model.NaruSummaryState;
import net.thevpc.naru.api.model.NaruSummaryTrigger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One cached summary: the text a summarizer produced, and everything needed to decide whether
 * it may be used again.
 *
 * <p>The summary's own {@code summary} metadata is not stored here. An entry is keyed by
 * content and options, so two entries are the same summary exactly when their keys match --
 * re-deriving the metadata from the key on read would mean storing it twice and hoping the
 * two copies agree.
 *
 * <p>Entries persist across processes. A summary of a hundred messages is expensive enough
 * that throwing it away on exit would make compaction far more costly than it needs to be,
 * and it stays valid: it is keyed by content, and content that has not changed produces the
 * same key whether or not the process that produced it is still running.
 */
public class NaruCompactCacheEntry {

    /** The cache key this entry is stored under. */
    public final String key;

    /** The summary text, exactly as the summarizer returned it. */
    public final String summaryText;

    /** Estimated size of {@link #summaryText}, so a read does not re-measure it. */
    public final long summaryTokens;

    /** What produced it, as {@code provider/model}. Never part of the key. */
    public final String modelUsed;

    /**
     * Whether the summarizer returned this under its token target.
     *
     * <p>Kept because a truncated summary is a worse summary, and a caller who asks for a
     * smaller budget than last time should get a fresh attempt rather than the cached
     * truncated one. The budget is in the key, so this is about the target the summarizer
     * was actually able to hit, which the key cannot express.
     */
    public final boolean ok;

    /**
     * Whether the summarizer ran several passes, so the text is a fold rather than a single
     * reading. Reported for honesty: a folded summary is a different kind of artifact, and a
     * user comparing summaries should be able to tell.
     */
    public final boolean folded;

    public NaruCompactCacheEntry(String key, String summaryText, long summaryTokens,
                                 String modelUsed, boolean ok, boolean folded) {
        this.key = key;
        this.summaryText = summaryText;
        this.summaryTokens = summaryTokens;
        this.modelUsed = modelUsed;
        this.ok = ok;
        this.folded = folded;
    }

    /**
     * The summary item this entry would insert.
     *
     * <p>A fresh {@link NaruSummaryInfo} is built on every call, and so a fresh id, because
     * the id identifies <em>this</em> summary's exclusion linkage in <em>this</em> history.
     * Reusing the cached id would be wrong twice over: two compactions of the same content
     * would then agree on an id, so undoing one would clear exclusions that belonged to the
     * other; and the metadata would claim a creation time from a previous session.
     *
     * @param covered the items this summary stands in for, in order
     */
    public NaruMessage toSummaryItem(List<NaruMessage> covered, long coveredTokens,
                                     String trigger, Instant createdAt) {
        List<NaruMessage> safeCovered = covered == null ? List.of() : covered;
        NaruSummaryInfo info = new NaruSummaryInfo(
                null,
                null,
                null,
                safeCovered.size(),
                coveredTokens,
                summaryTokens,
                NaruCompactCacheKey.contentHash(safeCovered),
                null,
                null,
                null,
                modelUsed,
                createdAt,
                NaruSummaryTrigger.parse(trigger),
                NaruSummaryState.ACTIVE,
                ok);
        return NaruMessage.summary(summaryText, info);
    }

    /** Whether a cached summary may satisfy a request for {@code maxTokens}. */
    public boolean fits(long maxTokens) {
        return maxTokens <= 0 || summaryTokens <= maxTokens;
    }

    /** The entries as plain rows, for persistence. */
    public List<Object> toRow() {
        List<Object> row = new ArrayList<>();
        row.add(key);
        row.add(summaryText);
        row.add(summaryTokens);
        row.add(modelUsed);
        row.add(ok);
        row.add(folded);
        return row;
    }

    @Override
    public String toString() {
        return "NaruCompactCacheEntry{" + (key == null ? "?" : key.substring(0, Math.min(8, key.length())))
                + " " + summaryTokens + " by " + modelUsed + (ok ? "" : " (truncated)")
                + (folded ? " (folded)" : "") + "}";
    }
}