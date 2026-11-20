package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.access.NaruAccessDecision;
import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAccessPolicy;
import net.thevpc.naru.api.access.NaruAccessPolicyListener;
import net.thevpc.naru.api.access.NaruAccessRequest;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruPermissionStore;
import net.thevpc.naru.desktop.util.EDT;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The view-model behind the Permissions tab (plan §5, Phase 2). EDT-confined and Swing-free, like
 * {@link ToolCallModel}, so it can be tested headless and reused by a front end that is not Swing.
 * <p>
 * It owns no policy of its own: the rules live on the session ({@code NaruSession.accessPolicy()},
 * because the same ordered list is consulted by files, urls, tools, directives and the shell). This
 * class is a <em>projection</em> of that list plus the traffic through it, and every mutation here
 * goes straight to the engine's object, so an edit is in force on the next tool call rather than on
 * the next restart.
 * <p>
 * Two threading facts drive the design:
 * <ul>
 *   <li>{@code NaruAccessPolicy} is thread-safe and its listener fires on the thread that asked the
 *       question — a scheduler worker. Rule-shape changes are marshalled to the EDT with
 *       {@code invokeLater} and coalesced, so a burst of changes is one repaint.</li>
 *   <li>{@code onDecision} fires on <b>every</b> check, which is far too often to repaint for. It
 *       only bumps a counter in a concurrent map; the numbers reach the screen when something else
 *       repaints (a rule change, or the session model's 2 Hz poll). A count that is a moment stale is
 *       honest; a repaint per tool call is not.</li>
 * </ul>
 */
public final class PermissionsModel {

    /** One row of the table: the rule as the engine holds it now, and how often it fired. */
    public record Row(NaruPermissionRule rule, long hits, long grants, long blocks) {
        public String id() {
            return rule.id();
        }
    }

    public interface Listener {
        void permissionsChanged();
    }

    /** Source tag the {@code naru-permissions} extension gives rules loaded from the project file. */
    public static final String SOURCE_PROJECT = NaruPermissionStore.SOURCE_PROJECT;
    /** Source tag for runtime rules, which live in the session state rather than in the file. */
    public static final String SOURCE_SESSION = NaruPermissionStore.SOURCE_SESSION;

    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, AtomicLong> hits = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> grants = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> blocks = new ConcurrentHashMap<>();
    private final AtomicLong defaultGrants = new AtomicLong();
    private final AtomicLong defaultBlocks = new AtomicLong();

    private NaruAccessPolicy policy;
    /**
     * The committed-file surface, from the {@code naru-permissions} extension through the registry.
     * Null when that extension is not installed — the pane then says so instead of writing the file
     * with a parser of its own.
     */
    private NaruPermissionStore store;
    private NaruAccessPolicyListener bridge;
    private List<Row> rows = List.of();
    private NaruAccessEffect defaultEffect = NaruAccessEffect.ALLOW;
    private boolean attached;
    private volatile boolean refreshScheduled;
    /** Set when a decision arrives so the next repaint can show counts without polling the engine. */
    private volatile boolean countsDirty;

    private final Runnable refreshOnEdt = this::refresh;

    // ── binding ───────────────────────────────────────────────────────────────

    /**
     * Bind to a live policy, with no committed file available. Used by tests and by a session whose
     * profile has no {@code naru-permissions} extension.
     */
    public void attach(NaruAccessPolicy policy) {
        attach(policy, null);
    }

    /**
     * Bind to a live policy and to the extension that owns the committed file. Called on the EDT
     * once the session exists; the desktop boots its session on a worker thread, so this cannot
     * happen in a constructor.
     *
     * @param store the project's rule store, or {@code null} when the extension is not installed
     */
    public void attach(NaruAccessPolicy policy, NaruPermissionStore store) {
        EDT.check("PermissionsModel.attach");
        if (this.policy == policy && this.store == store && attached) {
            refresh();
            return;
        }
        detach();
        this.policy = policy;
        this.store = store;
        this.attached = policy != null;
        if (policy != null) {
            bridge = new NaruAccessPolicyListener() {
                @Override
                public void onRuleAdded(NaruPermissionRule rule) {
                    scheduleRefresh();
                }

                @Override
                public void onRuleUpdated(NaruPermissionRule oldRule, NaruPermissionRule newRule) {
                    scheduleRefresh();
                }

                @Override
                public void onRuleRemoved(NaruPermissionRule rule) {
                    scheduleRefresh();
                }

                @Override
                public void onRulesReordered(List<String> orderedIds) {
                    scheduleRefresh();
                }

                @Override
                public void onDefaultEffectChanged(NaruAccessEffect oldEffect, NaruAccessEffect newEffect) {
                    scheduleRefresh();
                }

                @Override
                public void onDecision(NaruAccessRequest request, NaruAccessDecision decision) {
                    // worker thread: count, never touch EDT state
                    record(request, decision);
                }
            };
            policy.addListener(bridge);
            refresh();
        } else {
            rows = List.of();
            fire();
        }
    }

    /** Unbind. The policy outlives this model only if the session does, so never leak the bridge. */
    public void detach() {
        EDT.check("PermissionsModel.detach");
        if (policy != null && bridge != null) {
            try {
                policy.removeListener(bridge);
            } catch (RuntimeException ignored) {
                // a stopped session may refuse listener changes; there is nothing left to unbind anyway
            }
        }
        bridge = null;
        policy = null;
        store = null;
        attached = false;
        // the rows come from the policy, so unbinding it and still showing them would leave a
        // closed session's rules on screen and editable-looking — the first gate run of this model
        // failed on exactly that: detach cleared the binding but not the projection
        rows = List.of();
        defaultEffect = NaruAccessEffect.ALLOW;
        fire();
    }

    public boolean isAttached() {
        return attached;
    }

    // ── reads ─────────────────────────────────────────────────────────────────

    public List<Row> rows() {
        return rows;
    }

    public NaruAccessEffect defaultEffect() {
        return defaultEffect;
    }

    public Row row(String id) {
        for (Row r : rows) {
            if (r.id() != null && r.id().equals(id)) {
                return r;
            }
        }
        return null;
    }

    /** True when counts changed since the last repaint — the poll timer uses this to skip no-op fires. */
    public boolean countsDirty() {
        return countsDirty;
    }

    /** Total decisions through the policy, including the ones the fallback answered. */
    public long totalDecisions() {
        long n = defaultGrants.get() + defaultBlocks.get();
        for (AtomicLong v : hits.values()) {
            n += v.get();
        }
        return n;
    }

    // ── writes (all apply to the live policy immediately) ─────────────────────

    /**
     * Add or replace a rule. Replacing keeps the position {@code NaruAccessPolicy.putRule} gives it,
     * which is the documented behaviour and the right one: editing a rule must not silently change
     * its precedence.
     */
    public void putRule(NaruPermissionRule rule) {
        if (policy == null || rule == null) {
            return;
        }
        policy.putRule(rule);
        refresh();
    }

    public void removeRule(String id) {
        if (policy == null || id == null) {
            return;
        }
        policy.removeRule(id);
        refresh();
    }

    public void setEnabled(String id, boolean enabled) {
        NaruPermissionRule r = ruleOf(id);
        if (r != null) {
            putRule(r.enabled(enabled));
        }
    }

    /**
     * Move a rule up or down. Order is precedence — the policy evaluates first match wins — so this
     * is the edit that changes behaviour the most and the one a text file handles worst.
     */
    public void move(String id, int delta) {
        if (policy == null || id == null || delta == 0) {
            return;
        }
        List<NaruPermissionRule> current = policy.rules();
        int at = -1;
        for (int i = 0; i < current.size(); i++) {
            if (id.equals(current.get(i).id())) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return;
        }
        int to = Math.max(0, Math.min(current.size() - 1, at + delta));
        if (to == at) {
            return;
        }
        policy.moveRule(id, to);
        refresh();
    }

    public void setDefaultEffect(NaruAccessEffect effect) {
        if (policy == null || effect == null) {
            return;
        }
        policy.defaultEffect(effect);
        refresh();
    }

    /**
     * The store that owns the committed file, or {@code null} when the {@code naru-permissions}
     * extension is not installed. The pane greys Reload and Save on this, rather than reimplementing
     * the file format locally: two parsers of one format drift, and the drift is invisible until a
     * reload drops a rule.
     */
    public NaruPermissionStore projectStore() {
        return store;
    }

    /** The committed file for this session's project, or {@code null} when there is none to show. */
    public net.thevpc.nuts.io.NPath projectFile() {
        NaruPermissionStore s = store;
        if (s == null) {
            return null;
        }
        try {
            return s.projectFile();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Reload the committed project rules through the extension that owns them, replacing exactly the
     * rules tagged {@code perm:project} — the same call the {@code /permission reload} directive
     * makes, so the two hosts cannot disagree about what "reload" means.
     */
    public String reloadFromProject() {
        if (policy == null) {
            return "no session";
        }
        NaruPermissionStore s = store;
        if (s == null) {
            return "the naru-permissions extension is not installed, so there is no project policy "
                    + "file to read; the live rules below are still editable";
        }
        String error;
        try {
            error = s.reloadProjectRules();
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        refresh();
        if (error != null) {
            return error;
        }
        return "project rules reloaded from " + s.projectFile();
    }

    /**
     * Write the project-scoped rules back to the committed file, through the extension that owns it.
     * This is the only thing the desktop does that touches a committed file, so the pane asks first
     * (plan §3.3: the runtime must not rewrite that file as a side effect; a human pressing Save is
     * the deliberate act it allows).
     */
    public String saveToProject() {
        if (policy == null) {
            return "no session";
        }
        NaruPermissionStore s = store;
        if (s == null) {
            return "the naru-permissions extension is not installed, so the desktop will not write "
                    + "a policy file of its own";
        }
        List<NaruPermissionRule> project = new ArrayList<>();
        for (NaruPermissionRule r : policy.rules()) {
            if (SOURCE_PROJECT.equals(r.source())) {
                project.add(r);
            }
        }
        String error;
        try {
            error = s.writeProjectRules(project, policy.defaultEffect());
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        return error == null
                ? project.size() + " rule(s) written to " + s.projectFile()
                : error;
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private NaruPermissionRule ruleOf(String id) {
        Row r = row(id);
        return r == null ? null : r.rule();
    }

    private void record(NaruAccessRequest request, NaruAccessDecision decision) {
        if (decision == null) {
            return;
        }
        String id = decision.matchedRule().isPresent() ? decision.matchedRule().get().id() : null;
        boolean allowed = decision.allowed();
        if (id == null) {
            if (allowed) {
                defaultGrants.incrementAndGet();
            } else {
                defaultBlocks.incrementAndGet();
            }
        } else {
            hits.computeIfAbsent(id, k -> new AtomicLong()).incrementAndGet();
            (allowed ? grants : blocks).computeIfAbsent(id, k -> new AtomicLong()).incrementAndGet();
        }
        countsDirty = true;
    }

    /** Re-read the policy snapshot. Safe on any caller thread; fires listeners on the EDT. */
    private void scheduleRefresh() {
        if (refreshScheduled) {
            return;
        }
        refreshScheduled = true;
        if (SwingUtilities.isEventDispatchThread()) {
            refreshScheduled = false;
            refresh();
        } else {
            SwingUtilities.invokeLater(() -> {
                refreshScheduled = false;
                refresh();
            });
        }
    }

    /** Pull rows and the default effect from the engine. EDT only. */
    public void refresh() {
        EDT.check("PermissionsModel.refresh");
        if (policy == null) {
            rows = List.of();
            fire();
            return;
        }
        List<Row> out = new ArrayList<>();
        for (NaruPermissionRule r : snapshot()) {
            String id = r.id();
            out.add(new Row(r, count(hits, id), count(grants, id), count(blocks, id)));
        }
        rows = List.copyOf(out);
        defaultEffect = policy.defaultEffect();
        countsDirty = false;
        fire();
    }

    private List<NaruPermissionRule> snapshot() {
        try {
            return policy.rules();
        } catch (RuntimeException e) {
            // a session stopped mid-read still has to leave the table showing something coherent
            return List.of();
        }
    }

    private static long count(Map<String, AtomicLong> m, String id) {
        AtomicLong v = id == null ? null : m.get(id);
        return v == null ? 0 : v.get();
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void fire() {
        for (Listener l : new ArrayList<>(listeners)) {
            l.permissionsChanged();
        }
    }

    /** Forget the counters, e.g. after a session reload rewrote the rule set. */
    public void resetCounts() {
        EDT.check("PermissionsModel.resetCounts");
        hits.clear();
        grants.clear();
        blocks.clear();
        defaultGrants.set(0);
        defaultBlocks.set(0);
        refresh();
    }
}
