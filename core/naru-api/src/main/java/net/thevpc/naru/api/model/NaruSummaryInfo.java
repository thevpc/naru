package net.thevpc.naru.api.model;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NToElement;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What a summary item records about the conversation it stands in for.
 *
 * <p>Lives in {@code naru-api}, not in the compaction extension, for one reason: a summary
 * item is part of the persisted history, and a session must load and be usable by a build
 * that does not have the compaction extension installed. If the metadata were an extension
 * type, either the core could not render the item to the model or the item would vanish
 * from the history on the way back in. Everything here is therefore plain data that the
 * core can read, display and hash, and the extension supplies.
 *
 * <p>The summary text itself is the {@link NaruMessage#getContent() content} of the
 * {@code summary}-role message carrying this metadata. Splitting it this way means the
 * text is rendered, stored and versioned by exactly the machinery that already handles
 * every other history item, with no second content channel to keep in sync.
 *
 * <p>Immutable, and read/written additively: an older file that has none of these keys
 * simply produces a metadata object with nulls, which every accessor here treats as
 * "unknown" rather than as zero.
 */
public class NaruSummaryInfo implements NToElement {

    private final String id;
    private final String coversFromId;
    private final String coversToId;
    private final int coveredItemCount;
    private final long coveredTokens;
    private final long summaryTokens;
    private final String coveredContentHash;
    private final NaruSummaryLevel level;
    private final Long maxTokens;
    private final String focus;
    private final String modelUsed;
    private final Instant createdAt;
    private final NaruSummaryTrigger trigger;
    private final NaruSummaryState state;
    private final boolean ok;
    /**
     * Whether the covered content no longer matches {@link #coveredContentHash()}, so the
     * summary describes something the history no longer contains.
     *
     * <p>Not part of the stored form: it is recomputed from the hash on every read, so it
     * cannot go stale in the file the way a stored flag would. Whether a stale summary is
     * still used is a policy decision, not a fact.
     */
    private final boolean stale;

    public NaruSummaryInfo(String id, String coversFromId, String coversToId, int coveredItemCount,
                           long coveredTokens, long summaryTokens, String coveredContentHash,
                           NaruSummaryLevel level, Long maxTokens, String focus, String modelUsed,
                           Instant createdAt, NaruSummaryTrigger trigger, NaruSummaryState state,
                           boolean ok) {
        this(id, coversFromId, coversToId, coveredItemCount, coveredTokens, summaryTokens,
                coveredContentHash, level, maxTokens, focus, modelUsed, createdAt, trigger, state,
                ok, false);
    }

    private NaruSummaryInfo(String id, String coversFromId, String coversToId, int coveredItemCount,
                            long coveredTokens, long summaryTokens, String coveredContentHash,
                            NaruSummaryLevel level, Long maxTokens, String focus, String modelUsed,
                            Instant createdAt, NaruSummaryTrigger trigger, NaruSummaryState state,
                            boolean ok, boolean stale) {
        this.id = id == null || id.isBlank() ? newId() : id;
        this.coversFromId = coversFromId;
        this.coversToId = coversToId;
        this.coveredItemCount = coveredItemCount;
        this.coveredTokens = coveredTokens;
        this.summaryTokens = summaryTokens;
        this.coveredContentHash = coveredContentHash;
        this.level = level;
        this.maxTokens = maxTokens;
        this.focus = focus;
        this.modelUsed = modelUsed;
        this.createdAt = createdAt;
        this.trigger = trigger;
        this.state = state == null ? NaruSummaryState.ACTIVE : state;
        this.ok = ok;
        this.stale = stale;
    }

    /** A copy carrying a different {@link #state()}. Used by undo, supersede and deactivate. */
    public NaruSummaryInfo withState(NaruSummaryState newState) {
        return new NaruSummaryInfo(id, coversFromId, coversToId, coveredItemCount, coveredTokens,
                summaryTokens, coveredContentHash, level, maxTokens, focus, modelUsed, createdAt,
                trigger, newState, ok, stale);
    }

    /**
     * A copy flagged as stale. Staleness is not stored, so this only ever affects the
     * in-memory view handed to a caller; the file is unchanged until something decides to
     * act on it.
     */
    public NaruSummaryInfo asStale(boolean newStale) {
        return new NaruSummaryInfo(id, coversFromId, coversToId, coveredItemCount, coveredTokens,
                summaryTokens, coveredContentHash, level, maxTokens, focus, modelUsed, createdAt,
                trigger, state, ok, newStale);
    }

    /**
     * Stable identity of this summary, generated once and never reused.
     *
     * <p>This is what {@code excludedBy} on a covered item refers to, and it is generated
     * rather than read from the store on purpose. The store derives a message's file name
     * from a hash of its content, so writing a covered item's {@code excludedBy} changes
     * that name -- the id of a summary could not be known before the summary was written,
     * and would change every time the summary's own text was touched. An id that is minted
     * once, stored in the item, and survives every rewrite is what makes the exclusion
     * link stable and undo exact.
     */
    public String id() {
        return id;
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Store id of the first covered item, or null when the range is not known.
     *
     * <p>Informational, not authoritative. The linkage that actually holds is
     * {@code excludedBy} on each covered item pointing at {@link #id()}, because that flag
     * travels with the item and so stays correct when the history is reordered, reloaded or
     * rewritten. These two names are for display and for a sanity check, and are null when
     * the task had no store ids to resolve.
     */
    public String coversFromId() {
        return coversFromId;
    }

    /** Store id of the last covered item, inclusive. Informational; see {@link #coversFromId()}. */
    public String coversToId() {
        return coversToId;
    }

    public int coveredItemCount() {
        return coveredItemCount;
    }

    /** Estimated size of the covered items, in tokens. */
    public long coveredTokens() {
        return coveredTokens;
    }

    /** Estimated size of the summary text, in tokens. */
    public long summaryTokens() {
        return summaryTokens;
    }

    /**
     * Digest over the ids and contents of the covered items.
     *
     * <p>This is what makes a stale summary detectable. Editing, deleting or injecting any
     * covered item changes it, so a mismatch means the summary describes content the
     * history no longer holds.
     */
    public String coveredContentHash() {
        return coveredContentHash;
    }

    public NaruSummaryLevel level() {
        return level;
    }

    /** Token target the summary was asked to respect, or null when it was left to the level. */
    public Long maxTokens() {
        return maxTokens;
    }

    /** Free-text hint that was passed to the summarizer, or null. */
    public String focus() {
        return focus;
    }

    /**
     * The model that actually produced this summary, as {@code provider/model}.
     *
     * <p>Not part of the compaction cache key -- any model's summary of the same content
     * is as good as another's -- but recorded per item, because "which model summarized
     * this" is a question worth answering after the fact.
     */
    public String modelUsed() {
        return modelUsed;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public NaruSummaryTrigger trigger() {
        return trigger;
    }

    public NaruSummaryState state() {
        return state;
    }

    /**
     * Whether the summary is complete.
     *
     * <p>False means the compactor fell back to truncating because it could not get under
     * the requested size. The summary is still usable and the covered items are still
     * preserved -- compaction is never destructive -- but the tail of it may be missing.
     */
    public boolean ok() {
        return ok;
    }

    /** Whether the covered content no longer matches the recorded hash. */
    public boolean stale() {
        return stale;
    }

    /** Whether this summary is the one currently in force for the items it covers. */
    public boolean isActive() {
        return state == NaruSummaryState.ACTIVE;
    }

    /**
     * Tokens saved by this summary: what the covered items cost versus what the summary
     * costs. Negative for a summary that grew the context, which a level of
     * {@code LIGHT} on a very short range can legitimately produce.
     */
    public long savedTokens() {
        return coveredTokens - summaryTokens;
    }

    public NElement toElement() {
        NObjectElementBuilder o = NObjectElementBuilder.of();
        // always written: the id is what excludedBy points at, so a summary that lost it
        // would strand every item it covers as excluded by something nobody can find
        o.set("id", id);
        o.set("coversFromId", coversFromId);
        o.set("coversToId", coversToId);
        o.set("coveredItemCount", coveredItemCount);
        o.set("coveredTokens", coveredTokens);
        o.set("summaryTokens", summaryTokens);
        o.set("coveredContentHash", coveredContentHash);
        if (level != null) {
            o.set("level", level.name());
        }
        if (maxTokens != null) {
            o.set("maxTokens", maxTokens);
        }
        o.set("focus", focus);
        o.set("modelUsed", modelUsed);
        if (createdAt != null) {
            o.set("createdAt", NElement.ofInstant(createdAt));
        }
        if (trigger != null) {
            o.set("trigger", trigger.name());
        }
        // state is always written: ACTIVE is the default only for a brand new item, and a
        // file that says nothing about it is one written before the field existed, which
        // is exactly the case where assuming ACTIVE would resurrect a summary someone undid
        o.set("state", state.name());
        o.set("ok", ok);
        // stale is derived from the hash on every read and is never stored: writing it would
        // freeze one moment's answer into the file, where it could only ever go more wrong
        return o.build();
    }

    public static NaruSummaryInfo of(NElement element) {
        if (element == null || element.isNull()) {
            return null;
        }
        NObjectElement o = element.asObject().get();
        NaruSummaryState state = NaruSummaryState.ACTIVE;
        String stateValue = o.getStringValue("state").orNull();
        if (stateValue != null) {
            try {
                state = NaruSummaryState.valueOf(stateValue);
            } catch (IllegalArgumentException ignore) {
                // a state written by a newer build: treat as ACTIVE rather than refusing to
                // load the session at all, which would lose the whole conversation over one
                // unknown enum constant
                state = NaruSummaryState.ACTIVE;
            }
        }
        return new NaruSummaryInfo(
                o.getStringValue("id").orElse(null),
                o.getStringValue("coversFromId").orNull(),
                o.getStringValue("coversToId").orNull(),
                o.getIntValue("coveredItemCount").orElse(0),
                o.getLongValue("coveredTokens").orElse(0L),
                o.getLongValue("summaryTokens").orElse(0L),
                o.getStringValue("coveredContentHash").orNull(),
                NaruSummaryLevel.parse(o.getStringValue("level").orNull()),
                o.get("maxTokens").isPresent() ? o.getLongValue("maxTokens").orElse(null) : null,
                o.getStringValue("focus").orNull(),
                o.getStringValue("modelUsed").orNull(),
                o.getInstantValue("createdAt").orNull(),
                NaruSummaryTrigger.parse(o.getStringValue("trigger").orNull()),
                state,
                o.getBooleanValue("ok").orElse(true)
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        NaruSummaryInfo that = (NaruSummaryInfo) o;
        return coveredItemCount == that.coveredItemCount
                && coveredTokens == that.coveredTokens
                && summaryTokens == that.summaryTokens
                && ok == that.ok
                && Objects.equals(id, that.id)
                && Objects.equals(coversFromId, that.coversFromId)
                && Objects.equals(coversToId, that.coversToId)
                && Objects.equals(coveredContentHash, that.coveredContentHash)
                && level == that.level
                && Objects.equals(maxTokens, that.maxTokens)
                && Objects.equals(focus, that.focus)
                && Objects.equals(modelUsed, that.modelUsed)
                && Objects.equals(createdAt, that.createdAt)
                && trigger == that.trigger
                && state == that.state;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, coversFromId, coversToId, coveredItemCount, coveredTokens, summaryTokens,
                coveredContentHash, level, maxTokens, focus, modelUsed, createdAt, trigger, state, ok);
    }

    @Override
    public String toString() {
        return "NaruSummaryInfo{" + id.substring(0, Math.min(8, id.length()))
                + " " + state + " " + level + " covers=" + coveredItemCount
                + " " + coveredTokens + "->" + summaryTokens
                + " by " + modelUsed + (ok ? "" : " (truncated)") + "}";
    }
}