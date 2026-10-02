package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelInfo;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks which model summarizes, and says why the ones it skipped were skipped.
 *
 * <p>Walks a configured list in order and takes the first available model. Order is the whole
 * configuration surface: putting a cheap local model first and an expensive one second means
 * compaction is free in the normal case and still works when the local one is busy.
 *
 * <h2>What "available" means</h2>
 *
 * <p>All of, not any of:
 *
 * <ul>
 *   <li>the provider is enabled and reports itself reachable;</li>
 *   <li>credentials are present -- a provider needing a key and not having one fails at call
 *       time with a message that says nothing useful about compaction;</li>
 *   <li>it is not currently rate limited, per the provider's own reported buckets;</li>
 *   <li>the budget allows the call;</li>
 *   <li>its window is large enough to be worth calling at all.</li>
 * </ul>
 *
 * <p>Rate limits and budget are checked through the extension-provided state rather than the
 * core, because the core records both but enforces neither: {@code naru-budget} owns them and
 * may not be installed. When it is absent, those two checks pass -- there is nothing to be
 * limited by -- which is the right default, since refusing to compact because a metering
 * extension is missing would be a strange failure.
 */
public class NaruSummaryModelSelector {

    /** One candidate, and what became of it. */
    public static final class Candidate {
        public final NaruModelKey key;
        public final boolean available;
        public final String reason;

        Candidate(NaruModelKey key, boolean available, String reason) {
            this.key = key;
            this.available = available;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return key + (available ? " (available)" : " (skipped: " + reason + ")");
        }
    }

    private final NaruTask task;
    private final NaruSession session;
    private final NaruCompactRateLimitState rateLimits;

    public NaruSummaryModelSelector(NaruTask task) {
        this(task, null);
    }

    public NaruSummaryModelSelector(NaruTask task, NaruCompactRateLimitState rateLimits) {
        this.task = task;
        this.session = task == null ? null : task.session();
        this.rateLimits = rateLimits;
    }

    /**
     * The ordered candidate list, current model last.
     *
     * <p>{@code includeCurrent} defaults to true: with no explicit list configured, the task's
     * own model is the only candidate, and refusing to compact because nobody wrote a config
     * key would make the feature useless out of the box.
     */
    public List<NaruModelKey> candidates(List<String> configured, boolean includeCurrent,
                                         NaruModelKey current) {
        // a LinkedHashSet so a model named in the config and also current appears once, at
        // its configured position rather than duplicated
        Set<NaruModelKey> keys = new LinkedHashSet<>();
        for (String s : configured == null ? List.<String>of() : configured) {
            if (s == null || s.isBlank()) {
                continue;
            }
            NOptional<NaruModelKey> parsed = NaruModelKey.parse(s.trim());
            if (parsed.isPresent()) {
                keys.add(parsed.get());
            }
        }
        if (includeCurrent && current != null && !keys.contains(current)) {
            keys.add(current);
        }
        return new ArrayList<>(keys);
    }

    /**
     * The first available model, or null when none is.
     *
     * @param skipped collects every candidate that was passed over, with the reason, so the
     *                caller can report why the chosen model was not the first one listed
     */
    public NaruModelKey select(List<NaruModelKey> candidates, List<String> skipped) {
        for (NaruModelKey key : candidates) {
            Candidate c = evaluate(key);
            if (c.available) {
                return key;
            }
            if (skipped != null) {
                skipped.add(key.provider() + "/" + key.model() + ": " + c.reason);
            }
        }
        return null;
    }

    /** Evaluates one candidate against every availability rule. */
    public Candidate evaluate(NaruModelKey key) {
        if (session == null) {
            return new Candidate(key, false, "no session to resolve the provider in");
        }
        NOptional<NaruModelProvider> providerOpt = session.registry().provider(key.provider());
        if (!providerOpt.isPresent()) {
            return new Candidate(key, false, "no provider named '" + key.provider() + "'");
        }
        NaruModelProvider provider = providerOpt.get();
        if (!provider.isEnabled()) {
            return new Candidate(key, false, "provider is disabled");
        }
        // A provider that probes reachability returns false for a server that is down. This
        // check can be slow for some providers, which is why it is last of the cheap ones.
        if (!provider.isAvailable(session)) {
            return new Candidate(key, false, "provider is not reachable");
        }
        NOptional<String> apiKey = provider.apiKey(session);
        if (apiKey != null && !apiKey.isPresent()) {
            return new Candidate(key, false, "no API key configured");
        }
        long window = contextWindow(key);
        if (window > 0 && window < NaruCompactConfig.MIN_USABLE_WINDOW) {
            return new Candidate(key, false, "context window of " + window + " is too small to be useful");
        }
        String rateLimit = rateLimits == null ? null : rateLimits.exhaustedReason(key.provider());
        if (rateLimit != null) {
            return new Candidate(key, false, rateLimit);
        }
        return new Candidate(key, true, null);
    }

    /**
     * The model's context window, or -1 when nothing reports one.
     *
     * <p>Unknown is not disqualifying. Chunking handles an input larger than the window, and
     * refusing a model for lacking a published figure would exclude exactly the local models
     * a compaction config most wants to prefer.
     */
    public long contextWindow(NaruModelKey key) {
        if (session == null) {
            return -1;
        }
        for (NaruModelInfo info : session.registry().modelsInfos(session)) {
            if (info != null && info.key() != null && info.key().equals(key)) {
                NaruModelCapabilities caps = info.capabilities();
                if (caps != null && caps.contextLength() > 0) {
                    return caps.contextLength();
                }
                return -1;
            }
        }
        return -1;
    }

    /**
     * The current model, when it is set.
     *
     * <p>Never throws. Selection has to survive a task with no model configured, so that the
     * error the user sees is about compaction rather than about a null.
     */
    public NaruModelKey currentModel() {
        if (task == null) {
            return null;
        }
        try {
            NaruModelConfig cfg = task.model();
            return cfg == null ? null : cfg.key();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A message for {@code /compact status} listing why candidates were passed over. */
    public static String explain(List<String> skipped) {
        if (skipped == null || skipped.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("skipped models: ");
        sb.append(String.join("; ", skipped));
        return sb.toString();
    }

    /** Parses a comma-separated model list, as typed on a directive. */
    public static List<String> parseList(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    /** Exposed for tests: whether a model key can be resolved at all. */
    public boolean resolvable(NaruModelKey key) {
        return session != null && session.registry().findModel(key.provider() + "/" + key.model(), session).isPresent();
    }
}