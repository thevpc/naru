package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.context.NaruCompactionException;
import net.thevpc.naru.api.context.NaruCompactionRequest;
import net.thevpc.naru.api.context.NaruCompactionResult;
import net.thevpc.naru.api.context.NaruContextCompactor;
import net.thevpc.naru.api.model.NaruContextSpec;
import net.thevpc.naru.api.model.NaruContextViews;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruSummaryInfo;
import net.thevpc.naru.api.model.NaruSummaryLevel;
import net.thevpc.naru.api.model.NaruSummaryOptions;
import net.thevpc.naru.api.model.NaruSummaryState;
import net.thevpc.naru.api.model.NaruSummaryTrigger;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NMsg;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The compactor: decides what to summarize, gets a summary, and writes it without losing
 * anything.
 *
 * <p>The whole operation, and the three properties that make it safe to run automatically:
 *
 * <ol>
 *   <li><b>Non-destructive.</b> Nothing is ever removed from the history. A summary item is
 *       inserted and covered items are flagged, so {@code /history} still shows every message
 *       and undo is clearing a flag rather than reconstructing a deleted list.</li>
 *   <li><b>Atomic.</b> The summary item and its exclusions go in through one
 *       {@code setHistory}, so a crash leaves either the old view or the new one -- never a
 *       summary claiming to cover items that are still being sent.</li>
 *   <li><b>Cheap when repeated.</b> Identical content under identical options hits the cache,
 *       and concurrent identical requests collapse to one summarizer call.</li>
 * </ol>
 */
public class NaruCompactContextCompactor implements NaruContextCompactor {

    /** Identity, stable across versions so a status report can name it. */
    public static final String NAME = "naru-compact";

    private final NaruCompactCache cache;
    private final AtomicReference<NaruCompactionResult> lastResult = new AtomicReference<>();

    /**
     * The no-arg constructor the SPI loader uses.
     *
     * <p>The cache it builds is process-wide, which is correct rather than a leak: entries
     * are keyed by a digest of the content they summarize, so an entry produced in one
     * session is valid in another whenever the content matches. Sharing means a summarizer
     * call for the same conversation is not repeated per session, and nothing session-specific
     * is retained -- the compactor reads its task from each request rather than holding one.
     *
     * <p>Per-session configuration still applies: the options in the request decide the
     * level, keep window and target, and the extension supplies the task.
     */
    public NaruCompactContextCompactor() {
        this(new NaruCompactCache(NaruCompactConfig.DEFAULT_CACHE_MAX_ENTRIES, true));
    }

    public NaruCompactContextCompactor(NaruCompactCache cache) {
        this.cache = cache == null ? NaruCompactCache.disabled() : cache;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public NaruCompactionResult lastResult() {
        return lastResult.get();
    }

    /** The cache this compactor reads and writes. */
    public NaruCompactCache cache() {
        return cache;
    }

    /** The cache contents, for persisting on shutdown. */
    public Map<String, NaruCompactCacheEntry> cacheMap() {
        Map<String, NaruCompactCacheEntry> out = new LinkedHashMap<>();
        for (NaruCompactCacheEntry e : cache.entries()) {
            out.put(e.key, e);
        }
        return out;
    }

    @Override
    public NaruCompactionResult compact(NaruCompactionRequest request) {
        NaruCompactionResult result = doCompact(request);
        lastResult.set(result);
        return result;
    }

    private NaruCompactionResult doCompact(NaruCompactionRequest request) {
        NaruContextSpec spec = request.spec();
        List<NaruMessage> view = request.sourceView();

        // A view that is already shorter than its summary should be is not an error and not a
        // compaction: KEEP and DROP are policies a caller expresses through the same type, and
        // only SUMMARIZE has anything to do.
        if (spec.older() != NaruContextSpec.OlderPolicy.SUMMARIZE) {
            return NaruCompactionResult.produced(null, 0, 0, 0, null, List.of());
        }
        if (view == null || view.isEmpty()) {
            return NaruCompactionResult.nothingToCompact();
        }

        int cut = NaruCompactCut.cutOf(view, spec.window());
        if (cut < 0) {
            return NaruCompactionResult.nothingToCompact();
        }
        if (cut == 0) {
            // Nothing older than the window. Also not an error: a short conversation should
            // not be reported as a failed compaction.
            return NaruCompactionResult.nothingToCompact();
        }
        List<NaruMessage> covered = new ArrayList<>(view.subList(0, cut));
        if (covered.isEmpty()) {
            return NaruCompactionResult.nothingToCompact();
        }

        long coveredTokens = NaruCompactTokens.estimate(covered);
        NaruSummaryOptions options = spec.summary();

        // ── cache ──────────────────────────────────────────────────────────
        // Checked before selecting a model, so a hit costs nothing: no model resolution, no
        // availability probe, no call. On a hit the covered items are unchanged, so applying
        // is skipped and the caller gets a result saying so.
        NaruCompactCacheEntry cached = cache.get(covered, options);
        boolean fromCache = cached != null;
        List<String> skippedModels = new ArrayList<>();
        NaruCompactCacheEntry entry = cached;

        if (entry == null) {
            NaruSummaryModelSelector selector =
                    new NaruSummaryModelSelector(request.sourceTask(), rateLimitsOf(request));
            List<NaruModelKey> candidates = selector.candidates(
                    options.models(),
                    includeCurrent(request),
                    selector.currentModel());
            NaruModelKey chosen = selector.select(candidates, skippedModels);
            if (chosen == null) {
                throw new NaruCompactionException("cannot compact: no summarizer model is "
                        + "available."
                        + (skippedModels.isEmpty()
                        ? " Configure " + NaruCompactConfig.MODELS
                        + " or " + NaruCompactConfig.MODELS_INCLUDE_CURRENT + "."
                        : " Tried: " + String.join("; ", skippedModels) + "."));
            }
            entry = summarize(request, covered, options, spec, chosen);
        }

        long summaryTokens = entry.summaryTokens;

        // ── apply ──────────────────────────────────────────────────────────
        if (!request.apply()) {
            NaruMessage item = entry.toSummaryItem(covered, coveredTokens, request.trigger(), Instant.now());
            NaruCompactionResult produced = fromCache
                    ? NaruCompactionResult.cacheHit(item, entry.modelUsed)
                    : NaruCompactionResult.produced(item, coveredTokens, summaryTokens,
                    covered.size(), entry.modelUsed, skippedModels);
            return produced;
        }

        NaruTask task = request.sourceTask();
        if (task == null) {
            throw new NaruCompactionException(
                    "compaction was asked to apply but no source task was given");
        }
        NaruMessage item = entry.toSummaryItem(covered, coveredTokens, request.trigger(), Instant.now());
        NaruSummaryInfo info = item.getSummary();
        String summaryId = info.id();

        // Build the new history, then hand it over in one write. Everything below this line
        // is pure list arithmetic; nothing mutates the task until setHistory.
        List<NaruMessage> newHistory = new ArrayList<>();
        NaruMessage firstCovered = covered.get(0);
        int insertAt = task.history().indexOf(firstCovered);
        if (insertAt < 0) {
            // The covered items are not in the task's history -- they came from a caller that
            // supplied its own view. Refusing is right: applying would insert a summary whose
            // covered items nothing can be flagged against, and the context view would then
            // contain the summary alone, silently dropping everything it replaced.
            throw new NaruCompactionException("cannot apply compaction: the items to be "
                    + "summarized are not in the task's history");
        }
        List<NaruMessage> history = task.history();
        for (int i = 0; i < history.size(); i++) {
            NaruMessage m = history.get(i);
            if (m == null) {
                continue;
            }
            if (i == insertAt) {
                newHistory.add(item);
            }
            boolean isCovered = m == firstCovered || covered.contains(m);
            if (isCovered && !m.isSummary()) {
                newHistory.add(m.setExcludedBy(summaryId));
            } else {
                newHistory.add(m);
            }
        }
        if (insertAt >= history.size()) {
            newHistory.add(item);
        }
        task.setHistory(newHistory);

        return NaruCompactionResult.applied(item, coveredTokens, summaryTokens,
                covered.size(), entry.modelUsed, skippedModels);
    }

    /**
     * Whether the task's own model should be a candidate when no explicit list was given.
     *
     * <p>Resolves through the extension config rather than through the options, because
     * {@code NaruSummaryOptions} describes a single call and this is a policy that belongs to
     * the extension. The options win when they were set explicitly, which is what lets
     * {@code /compact models=...} override configuration for one run.
     */
    /**
     * The rate-limit state the extension is accumulating, so a provider reported exhausted is
     * skipped before a call rather than after a rejection.
     *
     * <p>Read through the registry rather than held by this compactor: the reports arrive on the
     * session, which is the extension's lifetime, and the compactor is a registry singleton that
     * outlives any one session. Absent extension means no reports, not an error -- compaction
     * still works, it just cannot see a limit before it hits one.
     */
    private NaruCompactRateLimitState rateLimitsOf(NaruCompactionRequest request) {
        NaruSession session = request.session();
        if (session == null) {
            return null;
        }
        return session.registry()
                .extension(NaruCompactExtension.NAME, NaruCompactExtension.class)
                .map(NaruCompactExtension::rateLimits)
                .orNull();
    }

    private boolean includeCurrent(NaruCompactionRequest request) {
        NaruTask task = request.sourceTask();
        if (task == null) {
            // No task means no config and no current model; the caller supplied a list.
            return false;
        }
        return new NaruCompactConfig(task).includeCurrentModel();
    }

    /**
     * Runs the summarizer, through the cache's single-flight so concurrent identical requests
     * produce one call.
     */
    private NaruCompactCacheEntry summarize(NaruCompactionRequest request,
                                            List<NaruMessage> covered,
                                            NaruSummaryOptions options,
                                            NaruContextSpec spec,
                                            NaruModelKey model) {
        long coveredTokens = NaruCompactTokens.estimate(covered);
        long window = new NaruSummaryModelSelector(request.sourceTask(), rateLimitsOf(request))
                .contextWindow(model);
        long maxTokens = options.isMaxTokensSet() && options.maxTokens() != null
                ? options.maxTokens()
                : targetTokens(spec, options, coveredTokens);

        return cache.computeIfAbsent(covered, options, key -> {
            NaruTask task = request.sourceTask();
            if (task == null) {
                // Named here rather than left to fail deeper, because "no task" from inside a
                // model call reads like a wiring bug in this compactor. It is a call-site
                // choice: this compactor summarizes through a task, so the session-only
                // NaruCompactors.preview overload cannot be used with it.
                throw new NaruCompactionException(
                        "cannot summarize: this compactor runs the summarizer through a task, "
                                + "so preview needs one -- use NaruCompactors.preview(task, view, spec)");
            }
            NaruSummaryLevel level = options.resolvedLevel(NaruCompactConfig.DEFAULT_LEVEL);
            List<NaruMessage> input = NaruSummaryInput.withoutThinking(covered);
            String content = NaruSummaryInput.render(input,
                    options.resolvedToolOutputs(level));
            try {
                LlmNaruSummarizer summarizer = new LlmNaruSummarizer(task, model, window);
                String text = summarizer.summarize(content, options.focus(), level, maxTokens);
                if (text == null || text.isBlank()) {
                    throw new NaruCompactionException(
                            "the summarizer returned no text for " + covered.size() + " items");
                }
                long tokens = NaruCompactTokens.estimate(text);
                boolean ok = maxTokens <= 0 || tokens <= maxTokens;
                return new NaruCompactCacheEntry(key, text, tokens,
                        model.provider() + "/" + model.model(), ok,
                        summarizer.lastCallFolded());
            } catch (NaruCompactionException e) {
                throw e;
            } catch (Exception e) {
                throw new NaruCompactionException("summarization failed on "
                        + model.provider() + "/" + model.model() + ": " + e.getMessage(), e);
            }
        });
    }

    private long targetTokens(NaruContextSpec spec, NaruSummaryOptions options, long coveredTokens) {
        Long explicit = options.maxTokens();
        if (explicit != null && explicit > 0) {
            return explicit;
        }
        NaruSummaryLevel level = options.resolvedLevel(NaruCompactConfig.DEFAULT_LEVEL);
        if (spec.isWindowKnown()) {
            return Math.max(1L, (long) (spec.contextWindow() * NaruCompactConfig.DEFAULT_TARGET));
        }
        return Math.max(1L, (long) (coveredTokens * level.targetRatio()));
    }

    /**
     * Marks a summary undone and restores the items it covered.
     *
     * <p>The inverse of applying, and exact rather than approximate: nothing was deleted, so
     * clearing the exclusion flags rebuilds the previous context view with no need to know
     * what was removed. Also deactivates the summary item itself -- leaving it ACTIVE would
     * send a summary <em>and</em> the items it replaced, doubling them.
     *
     * <p>By summary id, not by position, because ids are what {@code excludedBy} refers to
     * and are stable across edits to the items themselves.
     */
    public NaruCompactionResult undo(NaruTask task, String summaryId) {
        if (task == null || summaryId == null) {
            return NaruCompactionResult.failed("undo needs a task and a summary id");
        }
        List<NaruMessage> history = task.history();
        List<NaruMessage> newHistory = new ArrayList<>(history.size());
        boolean found = false;
        for (NaruMessage m : history) {
            if (m == null) {
                continue;
            }
            if (summaryId.equals(m.getExcludedBy())) {
                newHistory.add(m.setExcludedBy(null));
                found = true;
                continue;
            }
            if (m.isSummary() && m.getSummary() != null && summaryId.equals(m.getSummary().id())) {
                newHistory.add(m.setSummary(m.getSummary().withState(NaruSummaryState.UNDONE)));
                found = true;
                continue;
            }
            newHistory.add(m);
        }
        if (!found) {
            return NaruCompactionResult.failed("no summary with id " + summaryId + " in this task");
        }
        task.setHistory(newHistory);
        return NaruCompactionResult.produced(null, 0, 0, 0, null, List.of());
    }

    /**
     * Re-checks every active summary against its hash and deactivates the ones that no longer
     * describe their content.
     *
     * <p>Run at session load rather than lazily on read, because the decision of what to do
     * about a stale summary is the extension's policy and must not be taken by the core while
     * it is merely displaying history.
     *
     * @return the ids that were deactivated
     */
    public List<String> reconcileStale(NaruTask task, NaruCompactConfig.StalePolicy policy) {
        if (task == null) {
            return List.of();
        }
        List<NaruMessage> history = task.history();
        List<String> staleIds = new ArrayList<>();
        List<NaruMessage> newHistory = new ArrayList<>(history.size());
        boolean changed = false;
        // Collected first, because deciding to deactivate one summary has to clear the flags on
        // the items it stands for, and those items come earlier in the list than the summary
        // does -- a single forward pass cannot see the decision before it reaches them.
        boolean deactivate = policy == NaruCompactConfig.StalePolicy.deactivate;
        if (deactivate) {
            for (NaruMessage m : history) {
                if (m != null && m.isSummary() && m.getSummary() != null
                        && m.getSummary().isActive() && isStale(history, m)) {
                    staleIds.add(m.getSummary().id());
                }
            }
        }
        for (NaruMessage m : history) {
            if (m == null) {
                continue;
            }
            if (deactivate && staleIds.contains(m.getExcludedBy())) {
                // Clearing the flag is what puts the item back in the context view. Leaving it
                // set while also retiring the summary would drop the content from the view
                // entirely -- neither summarized nor present, which is the one outcome
                // non-destructive compaction must never produce.
                newHistory.add(m.setExcludedBy(null));
                changed = true;
                continue;
            }
            if (m.isSummary() && m.getSummary() != null
                    && m.getSummary().isActive() && isStale(history, m)) {
                if (deactivate) {
                    newHistory.add(m.setSummary(m.getSummary()
                            .withState(NaruSummaryState.UNDONE)
                            .asStale(true)));
                    changed = true;
                    continue;
                }
                // keep: flag it so the model and the user both see that it is out of date
                newHistory.add(m.setSummary(m.getSummary().asStale(true)));
                staleIds.add(m.getSummary().id());
                changed = true;
                continue;
            }
            newHistory.add(m);
        }
        if (changed) {
            task.setHistory(newHistory);
        }
        return staleIds;
    }

    /**
     * Whether a summary's covered content no longer matches what it was written from.
     *
     * <p>Recomputed from the live history, never trusted from the file: the whole point is
     * that the file cannot know what happened after it was written.
     */
    static boolean isStale(List<NaruMessage> history, NaruMessage summaryItem) {
        NaruSummaryInfo info = summaryItem.getSummary();
        if (info == null || info.coveredContentHash() == null) {
            // An old summary with no recorded hash cannot be checked. Treated as fresh: a
            // summary with no hash predates the field, and deactivating every one of them
            // would silently empty the context view of every existing session.
            return false;
        }
        List<NaruMessage> covered = new ArrayList<>();
        for (NaruMessage m : history) {
            if (m != null && summaryItem.getSummary().id().equals(m.getExcludedBy())) {
                covered.add(m);
            }
        }
        if (covered.isEmpty()) {
            // No item is flagged for it. Either it was already undone, or the flags are gone.
            // Either way the summary is not standing in for anything, so it should not be
            // rendered into the context view as though it were.
            return true;
        }
        return !info.coveredContentHash().equals(NaruCompactCacheKey.contentHash(covered));
    }

    /** The active summaries in a history, paired with whether each is stale. */
    public static List<NaruMessage> summariesWithStaleness(List<NaruMessage> history) {
        List<NaruMessage> out = new ArrayList<>();
        for (NaruMessage m : NaruContextViews.activeSummaries(history)) {
            out.add(m);
        }
        return out;
    }

    /** A status line for {@code /compact status}. */
    public static String describe(NaruTask task, NaruCompactionResult last, NaruCompactCache cache) {
        List<NaruMessage> history = task.history();
        List<NaruMessage> active = NaruContextViews.activeSummaries(history);
        long covered = 0;
        for (NaruMessage m : history) {
            if (m != null && m.isExcluded()) {
                covered++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("compaction: ");
        sb.append(active.size()).append(" active summar")
                .append(active.size() == 1 ? "y" : "ies");
        sb.append(", ").append(covered).append(" of ").append(history.size())
                .append(" history items covered");
        sb.append("\ncontext view: ").append(NaruContextViews.contextView(history).size())
                .append(" items, ~").append(NaruCompactTokens.format(NaruCompactTokens.estimate(
                NaruContextViews.wireContextView(history)))).append(" tokens");
        if (last != null) {
            // The message is only set for failures, so a successful compaction is reported
            // from its numbers. Reporting nothing there would leave the common case -- it just
            // worked -- invisible on the one screen a user checks after asking for it.
            String detail = last.message() != null ? last.message() : last.toString();
            sb.append("\nlast: ").append(last.outcome()).append(" - ").append(detail);
            if (!last.skippedModels().isEmpty()) {
                sb.append("\nskipped models: ").append(String.join("; ", last.skippedModels()));
            }
        }
        if (cache != null) {
            sb.append('\n').append(cache.describe(null));
        }
        return sb.toString();
    }

    /** The trigger recorded on an item, for reports. */
    public static NaruSummaryTrigger triggerOf(NaruMessage item) {
        return item == null || item.getSummary() == null ? null : item.getSummary().trigger();
    }
}