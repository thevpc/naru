package net.thevpc.naru.ext.budget;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionUsageListener;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruProviderRateLimitInfo;
import net.thevpc.naru.api.registry.NaruSessionExtension;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NStringUtils;

import java.time.Instant;
import java.util.Map;

/**
 * Makes token budget available to a session, without the core knowing what a token costs.
 * <p>
 * The core's only involvement is the {@link NaruSessionUsageListener} seam: it announces what
 * a provider reported, and this extension decides what that means. Removing
 * {@code naru-budget} from the classpath removes {@code /stat} and the accounting with it,
 * and the engine keeps running — it just stops adding up numbers nobody asked for.
 *
 * <h2>Scope</h2>
 * Totals are project-wide. The store and the price table are owned by the agent (the
 * project level) via {@link NaruBudgetAgentExtension}, and every session appends to the
 * same one. A report is scoped to the current session by <em>filtering</em> on the
 * {@code session} label, not by keeping a separate store: a level (session, task) is a
 * filter, so a project-wide rollup is the same store with a different filter.
 *
 * <h2>Persistence</h2>
 * Spend is persisted once, in the project-level store, appended per transaction so a
 * cancel or a kill loses nothing. A reloaded session therefore sees the project's spend
 * rather than starting from zero — {@code /budget} answers "what has this project cost",
 * not "what has this JVM run cost". This reverses the earlier per-session, never-persisted
 * stance. Sessions persist only their labels (in task state), never transactions.
 */
public class NaruBudgetExtension implements NaruSessionExtension, NaruSessionUsageListener {

    public static final String NAME = "budget";

    private NaruSession session;
    private NaruBudgetService budgetService;

    @Override
    public String name() {
        return NAME;
    }

    /**
     * The session's token accounting.
     * <p>
     * The accessor and the extension ship in the same jar and are registered together, so
     * the lookup only fails if a caller deliberately removed the extension mid-session.
     */
    public static NaruBudgetService budget(NaruSession session) {
        return session.registry().extension(NAME, NaruBudgetExtension.class)
                .map(NaruBudgetExtension::budget)
                .orElseThrow(() -> new IllegalStateException(
                        "the budget extension is not installed in this session"));
    }

    NaruBudgetService budget() {
        if (budgetService == null) {
            throw new IllegalStateException("budget accessed before the extension was opened");
        }
        return budgetService;
    }

    @Override
    public void open(NaruSession session) {
        // Idempotent on purpose. A listener registered twice would report every call twice,
        // and a doubled total is a worse bug than a missing one.
        if (this.session == session && budgetService != null) {
            return;
        }
        this.session = session;
        this.budgetService = new NaruBudgetServiceImpl(session, NaruBudgetAgentExtension.of(session.agent()));
        session.addUsageListener(this);
    }

    @Override
    public void onModelCall(NaruTask task, NaruModelKey model, long promptTokens, long completionTokens,
                            long cacheWriteTokens, long cacheReadTokens, NDuration duration) {
        if (budgetService == null) {
            return;
        }
        NaruModelConfig modelConfig = new NaruModelConfig(model);
        String sessionId = session == null ? null : session.uuid();
        String userId = session == null || session.user() == null ? null : session.user().id();
        budgetService.trackTransaction(new NaruTokenTransaction(
                buildLabels(task, modelConfig, sessionId, userId),
                sessionId,
                userId,
                modelConfig,
                promptTokens,
                completionTokens,
                // passed through as reported, including the -1 that means "this provider
                // does not do cache accounting"; the service normalises that to zero so it
                // cannot run a running total backwards
                cacheWriteTokens,
                cacheReadTokens,
                Instant.now(),
                duration
        ));
    }

    /**
     * Builds the full label set for one call: the built-in dimensions derived from the
     * call, then the task's user-defined labels, which never override a built-in key.
     *
     * <p>TODO: per-routine attribution. A {@code call}ed routine has no label of its
     * own, so its spend is attributed to the enclosing task. A {@code routine} label
     * taken from the top routine frame is the intended later addition.
     */
    private static Labels buildLabels(NaruTask task, NaruModelConfig model, String sessionId, String userId) {
        Labels.Builder b = Labels.builder();
        if (model != null) {
            if (model.provider() != null) {
                b.add("provider", model.provider());
            }
            if (model.model() != null) {
                b.add("model", model.model());
            }
        }
        if (sessionId != null) {
            b.add("session", sessionId);
        }
        String uid = NStringUtils.stripToNull(userId);
        if (uid != null) {
            b.add("user", uid);
        }
        if (task != null) {
            b.add("task-id", String.valueOf(task.id()));
            String tname = NStringUtils.stripToNull(task.name());
            if (tname != null) {
                b.add("task-name", tname);
            }
            b.add("task-path", taskPath(task));
        }
        Map<String, String> builtin = b.build().asMap();
        Labels.Builder all = Labels.builder();
        for (Map.Entry<String, String> e : builtin.entrySet()) {
            all.add(e.getKey(), e.getValue());
        }
        if (task != null) {
            for (Map.Entry<String, String> e : task.labels().entrySet()) {
                // user labels never override a built-in dimension
                if (!builtin.containsKey(e.getKey())) {
                    all.add(e.getKey(), e.getValue());
                }
            }
        }
        return all.build();
    }

    /**
     * The task's ancestry path: ancestor ids from the root down to this task, joined by
     * {@code /}. A prefix match on this label rolls a task and everything it spawned into
     * one group.
     */
    private static String taskPath(NaruTask task) {
        java.util.Deque<Long> ids = new java.util.ArrayDeque<>();
        NaruTask t = task;
        int guard = 0;
        while (t != null && guard++ < 1000) {
            ids.addFirst(t.id());
            t = t.parent().orNull();
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    @Override
    public void onProviderRateLimits(NaruProviderRateLimitInfo info) {
        if (budgetService != null) {
            budgetService.trackProviderStats(info);
        }
    }

    @Override
    public void close() {
        // Note: the core does not currently call this on session shutdown, so it is a
        // safety net rather than the normal path. The listener list dies with the session
        // anyway; what this protects against is the service outliving the extension and
        // being written to after the fact.
        if (session != null) {
            session.removeUsageListener(this);
        }
        this.session = null;
        this.budgetService = null;
    }
}
