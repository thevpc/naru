package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionUsageListener;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.context.NaruCompactionException;
import net.thevpc.naru.api.context.NaruCompactionResult;
import net.thevpc.naru.api.context.NaruCompactors;
import net.thevpc.naru.api.model.NaruContextSpec;
import net.thevpc.naru.api.model.NaruContextViews;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelInfo;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruProviderRateLimitInfo;
import net.thevpc.naru.api.model.NaruSummaryOptions;
import net.thevpc.naru.api.model.NaruSummaryTrigger;
import net.thevpc.naru.api.model.NaruWindowSpec;
import net.thevpc.naru.api.registry.NaruSessionExtension;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NDuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Installs compaction into a session: the compactor, the auto-compaction hook, and the cache.
 *
 * <p>One extension rather than three, because they share state that must be shared. The
 * summary cache is per-session, the rate-limit state comes from this session's usage
 * announcements, and the compactor is the only thing that reads either.
 *
 * <h2>Auto-compaction</h2>
 *
 * <p>Runs from {@link #beforeModelRequest}, which the engine calls before every model
 * request including every round of a tool-using turn. That placement is why auto-compaction
 * can work on a turn that fills up as it goes rather than only at the start.
 *
 * <p>Three guards keep it from looping or from firing expensively:
 * <ul>
 *   <li>the context must be past the configured fraction of the model's window;</li>
 *   <li>the previous compaction must be at least {@code minGap} items ago, so a compaction
 *       that did not get under the line does not immediately re-run;</li>
 *   <li>a failure is logged and remembered, and not retried until the gap elapses -- an
 *       unavailable summarizer model would otherwise cost one failed lookup per request.</li>
 * </ul>
 */
public class NaruCompactExtension implements NaruSessionExtension, NaruSessionUsageListener {

    public static final String NAME = "compact";

    private NaruSession session;
    private NaruCompactCacheStore cacheStore;
    private NaruCompactRateLimitState rateLimits = new NaruCompactRateLimitState();

    /** Item count at the last compaction, for the {@code minGap} guard. */
    private volatile int lastCompactedAt = -1;

    /** Why the last auto-compaction failed, so it is not retried on every request. */
    private volatile String lastAutoFailure;

    /** Tasks whose summaries have been checked against the history, so it happens once each. */
    private final java.util.Set<String> reconciledTasks = ConcurrentHashMap.newKeySet();

    @Override
    public String name() {
        return NAME;
    }

    /**
     * Runs before other extensions.
     *
     * <p>Compaction shrinks the context every later extension would measure. Running last
     * would mean {@code naru-budget} bills the request on the pre-compaction size, which is
     * the number the user is trying to stop paying.
     */
    @Override
    public int order() {
        return -100;
    }

    /**
     * The compactor this session compacts through.
     *
     * <p>Resolved from the registry rather than constructed here, because the registry is
     * what {@link net.thevpc.naru.api.context.NaruCompactors} resolves too. If the extension
     * built its own, a {@code /compact} driven by {@code NaruCompactors} would use one
     * instance and its cache while the auto-hook used another -- two caches, two copies of
     * every summary, and cache hits that only happened half the time.
     *
     * <p>Only this extension's own compactor is accepted. A third-party compactor on the
     * classpath is a legitimate choice, but then {@code /compact status} should describe that
     * one rather than reaching for ours behind the user's back.
     */
    public static NaruCompactContextCompactor compactor(NaruSession session) {
        return session.registry().compactor()
                .filter(c -> c instanceof NaruCompactContextCompactor)
                .map(c -> (NaruCompactContextCompactor) c)
                .orElseThrow(() -> new IllegalStateException(
                        "compaction is not installed in this session: no NaruCompactContextCompactor "
                                + "is registered. Add the naru-tools-compact extension to the classpath."));
    }

    public NaruCompactContextCompactor compactor() {
        if (session == null) {
            throw new IllegalStateException("compaction accessed before the extension was opened");
        }
        return compactor(session);
    }

    public NaruCompactRateLimitState rateLimits() {
        return rateLimits;
    }

    @Override
    public void open(NaruSession session) {
        // Idempotent: registering a usage listener twice would report every provider's
        // rate-limit state twice, and open() can legitimately be called again on reload.
        if (this.session == session) {
            return;
        }
        this.session = session;
        this.rateLimits = new NaruCompactRateLimitState();
        this.lastCompactedAt = -1;
        this.lastAutoFailure = null;
        this.reconciledTasks.clear();

        // The compactor comes from the registry, so the cache below is its cache. See
        // compactor(session) for why that matters.
        NaruCompactContextCompactor compactor = compactor(session);
        NaruCompactConfig bootstrap = new NaruCompactConfig(null);
        if (compactor.cache().isEnabled() && bootstrap.cacheEnabled()) {
            this.cacheStore = new NaruCompactCacheStore(
                    cacheFile(session), bootstrap.cacheMaxEntries());
            for (Map.Entry<String, NaruCompactCacheEntry> e : cacheStore.load().entrySet()) {
                compactor.cache().restore(e.getKey(), e.getValue());
            }
        }
        session.addUsageListener(this);
    }

    /**
     * Checks every active summary against the live history and deactivates the stale ones.
     *
     * <p>Called once per task, on its first model request, rather than at {@link #open} --
     * {@code open} runs before any task exists, so there is no history to check against. A
     * session reloaded after its covered items were edited therefore shows a consistent
     * context view from the first request onward.
     *
     * <p>Once per task, because re-running it per request would mean hashing the whole history
     * on every model call, which is precisely the cost compaction exists to avoid.
     */
    private void reconcileOnce(NaruTask task) {
        if (task == null || !reconciledTasks.add(taskId(task))) {
            return;
        }
        try {
            List<String> stale = compactor().reconcileStale(task,
                    new NaruCompactConfig(task).onStale());
            if (!stale.isEmpty()) {
                log(NaruLogMode.PROGRESS, NMsg.ofC(
                        "compaction: %d summar%s no longer matches the history it covered; "
                                + "the covered items are back in the context view",
                        stale.size(), stale.size() == 1 ? "y" : "ies").asWarning());
            }
        } catch (Exception e) {
            log(NaruLogMode.DEBUG, NMsg.ofC(
                    "compaction: could not check existing summaries: %s", e.getMessage()));
        }
    }

    private static String taskId(NaruTask task) {
        return task == null ? "" : String.valueOf(System.identityHashCode(task));
    }

    @Override
    public void onProviderRateLimits(NaruProviderRateLimitInfo info) {
        rateLimits.record(info);
    }

    @Override
    public void onModelCall(NaruModelKey model, long promptTokens, long completionTokens,
                            long cacheWriteTokens, long cacheReadTokens, NDuration duration) {
        // Nothing to do per call. Registered because rate limits arrive on the same listener,
        // and registering for one reason only would mean two listeners to keep in step.
    }

    @Override
    public void beforeModelRequest(NaruTask task) {
        if (task == null || session == null) {
            return;
        }
        // Before anything is measured: a stale summary must not be counted as if it were
        // current, or the threshold would be computed against a view that is about to change.
        reconcileOnce(task);
        NaruCompactConfig config = new NaruCompactConfig(task);
        if (!config.auto()) {
            return;
        }
        try {
            maybeCompactAutomatically(task, config);
        } catch (NaruCompactionException e) {
            // Remembered so the next requests do not each pay for a failing model probe. The
            // user is told once, not once per request.
            if (lastAutoFailure == null) {
                lastAutoFailure = e.getMessage();
                log(NaruLogMode.PROGRESS, NMsg.ofC(
                        "%s compaction skipped: %s",
                        NMsg.ofStyledPrimary8("!"), e.getMessage()).asWarning());
            }
        } catch (Exception e) {
            if (lastAutoFailure == null) {
                lastAutoFailure = String.valueOf(e.getMessage());
                log(NaruLogMode.DEBUG, NMsg.ofC(
                        "compaction skipped: %s", e.getMessage()));
            }
        }
    }

    /**
     * Compacts if the context is past the threshold, and the gap guard allows it.
     *
     * <p>All the guards are here rather than in the compactor because they are about the
     * policy of when to act, not about what compaction does. A caller that asks for
     * compaction explicitly gets it regardless of the threshold.
     */
    private void maybeCompactAutomatically(NaruTask task, NaruCompactConfig config) {
        NaruModelConfig model = task.model();
        if (model == null) {
            return;
        }
        long window = contextWindowOf(task, model);
        if (window <= 0) {
            // No published window means no threshold can be computed. Guessing one would fire
            // compaction at the wrong moment on every model that does not advertise a size.
            return;
        }
        List<NaruMessage> view = NaruContextViews.contextView(task.history());
        long used = NaruCompactTokens.estimate(NaruContextViews.wireContextView(task.history()));
        double threshold = window * config.threshold();
        if (used < threshold) {
            return;
        }
        int gap = config.minGap();
        int since = lastCompactedAt < 0 ? Integer.MAX_VALUE : view.size() - lastCompactedAt;
        if (gap > 0 && since < gap) {
            return;
        }
        NaruContextSpec spec = NaruContextSpec.of(window, config.keep(),
                NaruSummaryOptions.of(config.level()));
        NaruCompactionResult result = NaruCompactors.compact(task, spec);
        lastCompactedAt = task.history().size();
        lastAutoFailure = null;
        if (result.isSuccess()) {
            log(NaruLogMode.PROGRESS, NMsg.ofC(
                    "%s compacted: %s covered -> ~%s tokens, %s",
                    NMsg.ofStyledPrimary8("\uD83E\uDDD0"),
                    NaruCompactTokens.format(result.coveredTokens()),
                    NaruCompactTokens.format(result.summaryTokens()),
                    result.modelUsed() == null ? "cached summary" : result.modelUsed()));
        }
    }

    /** The model's context window, or 0 when nothing reports one. */
    static long contextWindowOf(NaruTask task, NaruModelConfig model) {
        if (task == null || model == null || task.session() == null) {
            return 0;
        }
        NaruModelKey key = model.key();
        for (NaruModelInfo info : task.session().registry().modelsInfos(task.session())) {
            if (info != null && key.equals(info.key())) {
                NaruModelCapabilities caps = info.capabilities();
                if (caps != null && caps.contextLength() > 0) {
                    return caps.contextLength();
                }
            }
        }
        return 0;
    }

    /**
     * Compacts now, on the user's instruction.
     *
     * <p>The threshold is ignored -- {@code /compact} means now -- but the model's window is
     * still needed, to aim the summary at something. With no known window the summary target
     * falls back to a fraction of the covered content.
     */
    public NaruCompactionResult compactNow(NaruTask task, NaruWindowSpec keep,
                                           NaruSummaryOptions options) {
        NaruCompactConfig config = new NaruCompactConfig(task);
        NaruWindowSpec window = keep == null ? config.keep() : keep;
        NaruSummaryOptions effective = options == null
                ? NaruSummaryOptions.of(config.level())
                : options;
        long window0 = contextWindowOf(task, task.model());
        NaruContextSpec spec = NaruContextSpec.of(window0, window, effective);
        NaruCompactionResult result = NaruCompactors.compact(task, spec);
        lastCompactedAt = task.history().size();
        lastAutoFailure = null;
        return result;
    }

    /** Undoes the summary with this id, restoring the items it covered. */
    public NaruCompactionResult undo(NaruTask task, String summaryId) {
        return compactor().undo(task, summaryId);
    }

    /** The state line for {@code /compact status}. */
    public String describe(NaruTask task) {
        NaruCompactConfig config = new NaruCompactConfig(task);
        StringBuilder sb = new StringBuilder(
                NaruCompactContextCompactor.describe(task,
                        compactor().lastResult(), compactor().cache()));
        sb.append('\n');
        for (String line : config.effectiveValues()) {
            sb.append("  ").append(line).append('\n');
        }
        return sb.toString();
    }

    @Override
    public void close() {
        if (cacheStore != null && session != null) {
            try {
                cacheStore.save(compactor().cacheMap());
            } catch (Exception e) {
                // losing the cache costs recomputation, never correctness
            }
        }
        this.cacheStore = null;
        this.session = null;
    }

    /** The trigger recorded on summaries this extension created automatically. */
    public static NaruSummaryTrigger autoTrigger() {
        return NaruSummaryTrigger.AUTO;
    }

    private static NPath cacheFile(NaruSession session) {
        return session.projectDir().resolve(".naru/cache/compact.tson");
    }

    private void log(NaruLogMode mode, NMsg msg) {
        if (session != null) {
            session.log(mode, msg);
        }
    }

    /** Stats passthrough, for a status report that wants cache numbers. */
    public Map<String, Long> cacheStats() {
        return session == null ? Map.of() : compactor().cache().stats();
    }
}