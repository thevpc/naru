package net.thevpc.naru.api.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * What a summary should look like.
 *
 * <p>A mutable builder-shaped value object with explicit "unset" fields, because the whole
 * point of {@link #level()} is to act as a preset and an explicit field must be able to
 * override exactly one part of it. A field that had already been resolved to the level's
 * default could not be told apart from one the caller set deliberately, and the level
 * would be unable to do its job.
 *
 * <p>So: {@code null} means "not set, follow the level" and {@link #resolvedLevel()} is what
 * a compactor should actually read. Every getter here is deliberately named to say which of
 * the two it is.
 *
 * <p>Immutable once built via {@link #build()}; the {@code with*} methods return a new
 * instance. That matters because a spec is often shared with a cached entry, and a
 * compactor that could mutate it would silently change the key another compactor is using.
 */
public final class NaruSummaryOptions {

    private final NaruSummaryLevel level;
    private final boolean levelSet;
    private final Long maxTokens;
    private final String focus;
    private final List<String> models;
    private final NaruToolOutputPolicy toolOutputs;
    private final boolean toolOutputsSet;
    private final boolean preservePinned;

    private NaruSummaryOptions(NaruSummaryLevel level, boolean levelSet, Long maxTokens, String focus,
                               List<String> models, NaruToolOutputPolicy toolOutputs,
                               boolean toolOutputsSet, boolean preservePinned) {
        this.level = level;
        this.levelSet = levelSet;
        this.maxTokens = maxTokens;
        this.focus = focus;
        this.models = models == null ? null : Collections.unmodifiableList(new ArrayList<>(models));
        this.toolOutputs = toolOutputs;
        this.toolOutputsSet = toolOutputsSet;
        this.preservePinned = preservePinned;
    }

    public static NaruSummaryOptions of() {
        return new NaruSummaryOptions(null, false, null, null, null, null, false, true);
    }

    public static NaruSummaryOptions of(NaruSummaryLevel level) {
        return of().withLevel(level);
    }

    /** The explicitly set level, or null to take it from config. */
    public NaruSummaryLevel level() {
        return level;
    }

    public boolean isLevelSet() {
        return levelSet;
    }

    /**
     * The level to use: the explicit one, else {@code fallback} -- normally the configured
     * default. Never null, because a compactor has to pick something.
     */
    public NaruSummaryLevel resolvedLevel(NaruSummaryLevel fallback) {
        if (levelSet && level != null) {
            return level;
        }
        return fallback == null ? NaruSummaryLevel.NORMAL : fallback;
    }

    /** Token target, or null to derive it from the level's ratio of the covered size. */
    public Long maxTokens() {
        return maxTokens;
    }

    public boolean isMaxTokensSet() {
        return maxTokens != null;
    }

    /** Free-text hint passed to the summarizer, or null. */
    public String focus() {
        return focus;
    }

    /** Per-call override of the configured model list, or null to use the configured one. */
    public List<String> models() {
        return models;
    }

    public boolean isModelsSet() {
        return models != null && !models.isEmpty();
    }

    /** Explicit tool-output policy, or null to take the level's. */
    public NaruToolOutputPolicy toolOutputs() {
        return toolOutputs;
    }

    public boolean isToolOutputsSet() {
        return toolOutputsSet;
    }

    public NaruToolOutputPolicy resolvedToolOutputs(NaruSummaryLevel effectiveLevel) {
        if (toolOutputsSet && toolOutputs != null) {
            return toolOutputs;
        }
        return effectiveLevel.toolOutputs();
    }

    /**
     * Whether items the user pinned must be kept verbatim rather than summarized.
     *
     * <p>True by default. The alternative -- quietly folding a pinned item into a summary
     * -- defeats the point of pinning it, and pinning has no other meaning.
     */
    public boolean preservePinned() {
        return preservePinned;
    }

    public NaruSummaryOptions withLevel(NaruSummaryLevel newLevel) {
        return new NaruSummaryOptions(newLevel, newLevel != null, maxTokens, focus, models,
                toolOutputs, toolOutputsSet, preservePinned);
    }

    public NaruSummaryOptions withMaxTokens(Long newMaxTokens) {
        return new NaruSummaryOptions(level, levelSet, newMaxTokens, focus, models,
                toolOutputs, toolOutputsSet, preservePinned);
    }

    public NaruSummaryOptions withFocus(String newFocus) {
        return new NaruSummaryOptions(level, levelSet, maxTokens, newFocus, models,
                toolOutputs, toolOutputsSet, preservePinned);
    }

    public NaruSummaryOptions withModels(List<String> newModels) {
        return new NaruSummaryOptions(level, levelSet, maxTokens, focus, newModels,
                toolOutputs, toolOutputsSet, preservePinned);
    }

    public NaruSummaryOptions withToolOutputs(NaruToolOutputPolicy newToolOutputs) {
        return new NaruSummaryOptions(level, levelSet, maxTokens, focus, models,
                newToolOutputs, newToolOutputs != null, preservePinned);
    }

    public NaruSummaryOptions withPreservePinned(boolean newPreservePinned) {
        return new NaruSummaryOptions(level, levelSet, maxTokens, focus, models,
                toolOutputs, toolOutputsSet, newPreservePinned);
    }

    /**
     * The subset of these options that changes the summary text.
     *
     * <p>Deliberately excludes the model list: a summary is a function of what it covers and
     * how aggressively, not of which model wrote it, so two calls differing only in
     * {@code models} share a cache entry. The model that produced a hit is recorded on the
     * entry, so the answer to "which model summarized this" survives the sharing.
     */
    public String outputKey() {
        return "level=" + (levelSet && level != null ? level.name() : "?")
                + ";maxTokens=" + (maxTokens == null ? "auto" : maxTokens)
                + ";focus=" + (focus == null ? "" : focus)
                + ";toolOutputs=" + (toolOutputsSet && toolOutputs != null ? toolOutputs.name() : "level")
                + ";preservePinned=" + preservePinned;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NaruSummaryOptions)) {
            return false;
        }
        NaruSummaryOptions that = (NaruSummaryOptions) o;
        return levelSet == that.levelSet
                && toolOutputsSet == that.toolOutputsSet
                && preservePinned == that.preservePinned
                && level == that.level
                && Objects.equals(maxTokens, that.maxTokens)
                && Objects.equals(focus, that.focus)
                && Objects.equals(models, that.models)
                && toolOutputs == that.toolOutputs;
    }

    @Override
    public int hashCode() {
        return Objects.hash(level, levelSet, maxTokens, focus, models, toolOutputs, toolOutputsSet,
                preservePinned);
    }

    @Override
    public String toString() {
        return "NaruSummaryOptions{" + outputKey() + (isModelsSet() ? " models=" + models : "") + "}";
    }
}