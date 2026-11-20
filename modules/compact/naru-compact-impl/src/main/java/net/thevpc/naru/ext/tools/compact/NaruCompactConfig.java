package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.agent.NaruTaskConfig;
import net.thevpc.naru.api.model.NaruSummaryLevel;
import net.thevpc.naru.api.model.NaruToolOutputPolicy;
import net.thevpc.naru.api.model.NaruWindowSpec;
import net.thevpc.naru.api.task.NaruTask;

import java.util.ArrayList;
import java.util.List;

/**
 * Every {@code naru.compact.*} setting, resolved for one task.
 *
 * <p>Resolved lazily per task rather than read once at startup, because the chain the values
 * come from is per-task: a child task inherits its parent's env at {@code start}, so a
 * setting changed in a parent between two requests has to take effect without restarting
 * anything. Caching would have made {@code /set} appear not to work.
 *
 * <p>See {@link NaruTaskConfig} for the resolution order. Every key here is read through it,
 * so a value set with {@code /set naru.compact.level = aggressive} in a script takes effect
 * exactly as a project file would.
 */
public class NaruCompactConfig {

    /** Root of every key this extension owns. */
    public static final String PREFIX = "naru.compact.";

    public static final String AUTO = PREFIX + "auto";
    public static final String THRESHOLD = PREFIX + "threshold";
    public static final String TARGET = PREFIX + "target";
    public static final String LEVEL = PREFIX + "level";
    public static final String KEEP = PREFIX + "keep";
    public static final String MODELS = PREFIX + "models";
    public static final String MODELS_INCLUDE_CURRENT = PREFIX + "models.includeCurrent";
    public static final String MAX_TOKENS = PREFIX + "maxTokens";
    public static final String MIN_GAP = PREFIX + "minGap";
    public static final String ON_STALE = PREFIX + "onStale";
    public static final String MODEL_CAN_COMPACT = PREFIX + "modelCanCompact";
    public static final String CACHE_ENABLED = PREFIX + "cache.enabled";
    public static final String CACHE_MAX_ENTRIES = PREFIX + "cache.maxEntries";

    /** What to do with a summary whose covered content no longer matches its hash. */
    public enum StalePolicy {
        /**
         * Stop using the summary: mark it undone and clear the exclusions, so the real
         * covered items go back into the context view. The default, because serving a
         * summary of content that no longer exists would make the model confidently wrong
         * about a state it cannot see.
         */
        deactivate,
        /**
         * Keep using the summary but report it as stale. Opt-in for a user who edits
         * history often and would rather have a slightly out-of-date summary than the whole
         * conversation back in the window.
         */
        keep;

        public static StalePolicy parse(String value) {
            if (value != null) {
                for (StalePolicy p : values()) {
                    if (p.name().equalsIgnoreCase(value.trim())) {
                        return p;
                    }
                }
            }
            return deactivate;
        }
    }

    // ── defaults ────────────────────────────────────────────────────────────
    //
    // Auto-compaction is ON by default. The reasoning: an agent that has run out of context
    // cannot recover on its own -- it either fails the call or starts asking the user to
    // start over -- so a user who installs this extension is asking for the conversation to
    // be kept usable. Opting in again for a session that never needs it costs nothing, since
    // below-threshold sessions never trigger it.
    public static final boolean DEFAULT_AUTO = true;
    public static final double DEFAULT_THRESHOLD = 0.80d;
    public static final double DEFAULT_TARGET = 0.40d;
    public static final NaruSummaryLevel DEFAULT_LEVEL = NaruSummaryLevel.NORMAL;
    public static final String DEFAULT_KEEP = "4turns";
    public static final boolean DEFAULT_MODELS_INCLUDE_CURRENT = true;
    public static final int DEFAULT_MIN_GAP = 6;
    public static final StalePolicy DEFAULT_ON_STALE = StalePolicy.deactivate;
    public static final boolean DEFAULT_MODEL_CAN_COMPACT = false;
    public static final boolean DEFAULT_CACHE_ENABLED = true;
    public static final int DEFAULT_CACHE_MAX_ENTRIES = 64;

    /**
     * A summarizer below this many tokens is not worth a call.
     *
     * <p>Distinct from "no model is available": a model can be perfectly available and
     * still have a window so small that summarizing the input would need more chunks than
     * the content is worth. Summarizing 300 tokens saves nothing.
     */
    public static final long MIN_USABLE_WINDOW = 512L;

    private final NaruTask task;

    public NaruCompactConfig(NaruTask task) {
        this.task = task;
    }

    public boolean auto() {
        return NaruTaskConfig.getBoolean(task, AUTO, DEFAULT_AUTO);
    }

    /** Fraction of the context window at which auto-compaction fires. */
    public double threshold() {
        return clamp01(NaruTaskConfig.getDouble(task, THRESHOLD, DEFAULT_THRESHOLD));
    }

    /**
     * Fraction of the context window compaction aims to land at.
     *
     * <p>Distinct from {@link #threshold()}: the gap between them is the headroom that stops
     * compaction from firing again immediately after it ran. Defaulting both to the same
     * value would produce a request that is already back over the line.
     */
    public double target() {
        return clamp01(NaruTaskConfig.getDouble(task, TARGET, DEFAULT_TARGET));
    }

    public NaruSummaryLevel level() {
        String raw = NaruTaskConfig.getString(task, LEVEL, null);
        NaruSummaryLevel parsed = NaruSummaryLevel.parse(raw);
        return parsed == null ? DEFAULT_LEVEL : parsed;
    }

    /** The keep window, parsed from e.g. {@code 4turns}. */
    public NaruWindowSpec keep() {
        String raw = NaruTaskConfig.getString(task, KEEP, DEFAULT_KEEP);
        try {
            NaruWindowSpec parsed = NaruWindowSpec.parse(raw);
            return parsed == null ? NaruWindowSpec.parse(DEFAULT_KEEP) : parsed;
        } catch (RuntimeException e) {
            // A malformed keep value must not stop the session working: fall back to the
            // default and let the caller notice via /compact status.
            return NaruWindowSpec.parse(DEFAULT_KEEP);
        }
    }

    /** Ordered model keys to try for summarization. Empty means "no explicit list". */
    public List<String> models() {
        return NaruTaskConfig.getStringList(task, MODELS, List.of());
    }

    /**
     * Whether the task's current model is appended as a last resort.
     *
     * <p>On by default because the alternative is a configuration that cannot compact at all
     * until someone has enumerated a model list, and a summarizer call on the current model
     * is usually better than no compaction.
     */
    public boolean includeCurrentModel() {
        return NaruTaskConfig.getBoolean(task, MODELS_INCLUDE_CURRENT, DEFAULT_MODELS_INCLUDE_CURRENT);
    }

    public Long maxTokens() {
        if (!NaruTaskConfig.find(task, MAX_TOKENS).isPresent()) {
            return null;
        }
        String raw = NaruTaskConfig.getString(task, MAX_TOKENS, null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * How many items must be added before auto-compaction may fire again.
     *
     * <p>The anti-loop guard. A compaction that leaves the context still above the threshold
     * would otherwise fire on every single request, each time paying for a summarizer call.
     */
    public int minGap() {
        int v = (int) NaruTaskConfig.getLong(task, MIN_GAP, DEFAULT_MIN_GAP);
        return Math.max(0, v);
    }

    public StalePolicy onStale() {
        return StalePolicy.parse(NaruTaskConfig.getString(task, ON_STALE, null));
    }

    /**
     * Whether the agent may call the compaction tool itself.
     *
     * <p>Off by default. An agent that can compact its own context can also decide to discard
     * it, and a model that has been squeezed for context is exactly the kind of component
     * that decides the squeeze was the problem. Compaction stays under the user's control
     * until they say otherwise.
     */
    public boolean modelCanCompact() {
        return NaruTaskConfig.getBoolean(task, MODEL_CAN_COMPACT, DEFAULT_MODEL_CAN_COMPACT);
    }

    public boolean cacheEnabled() {
        return NaruTaskConfig.getBoolean(task, CACHE_ENABLED, DEFAULT_CACHE_ENABLED);
    }

    public int cacheMaxEntries() {
        int v = (int) NaruTaskConfig.getLong(task, CACHE_MAX_ENTRIES, DEFAULT_CACHE_MAX_ENTRIES);
        return Math.max(1, v);
    }

    /** Tool-output policy, falling back to the level's when not set explicitly. */
    public NaruToolOutputPolicy toolOutputs() {
        return level().toolOutputs();
    }

    /**
     * The token target for a compaction, derived when not set explicitly.
     *
     * <p>Prefers {@link #maxTokens()} because an explicit cap is an instruction. Otherwise
     * takes {@link #target()} of the window, which is what makes auto-compaction land where
     * it was asked to rather than merely under the threshold.
     */
    public long targetTokens(long contextWindow, long coveredTokens) {
        Long explicit = maxTokens();
        if (explicit != null && explicit > 0) {
            return explicit;
        }
        if (contextWindow > 0) {
            return Math.max(1L, (long) (contextWindow * target()));
        }
        // No window to aim at: aim at the level's ratio of what is actually there.
        return Math.max(1L, (long) (coveredTokens * level().targetRatio()));
    }

    /**
     * The key list, resolved, for reporting in {@code /compact status}.
     *
     * <p>Shows what is actually in force rather than what was configured, which is the whole
     * point of asking when a setting did not do what the user expected.
     */
    public List<String> effectiveValues() {
        List<String> out = new ArrayList<>();
        out.add(AUTO + " = " + auto());
        out.add(THRESHOLD + " = " + threshold());
        out.add(TARGET + " = " + target());
        out.add(LEVEL + " = " + level());
        out.add(KEEP + " = " + keep());
        out.add(MODELS + " = " + models());
        out.add(MODELS_INCLUDE_CURRENT + " = " + includeCurrentModel());
        out.add(MAX_TOKENS + " = " + maxTokens());
        out.add(MIN_GAP + " = " + minGap());
        out.add(ON_STALE + " = " + onStale());
        out.add(MODEL_CAN_COMPACT + " = " + modelCanCompact());
        out.add(CACHE_ENABLED + " = " + cacheEnabled());
        out.add(CACHE_MAX_ENTRIES + " = " + cacheMaxEntries());
        return out;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return DEFAULT_THRESHOLD;
        }
        return Math.max(0.01d, Math.min(1.0d, v));
    }
}