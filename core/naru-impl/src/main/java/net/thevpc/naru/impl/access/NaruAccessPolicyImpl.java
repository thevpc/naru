package net.thevpc.naru.impl.access;

import net.thevpc.naru.api.access.NaruAccessDecision;
import net.thevpc.naru.api.access.NaruAccessDeniedException;
import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAccessPolicy;
import net.thevpc.naru.api.access.NaruAccessPolicyListener;
import net.thevpc.naru.api.access.NaruAccessRequest;
import net.thevpc.naru.api.access.NaruAccessTrace;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruActor;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruResource;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NOptional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The session-scoped access policy: an ordered rule list, first match wins.
 *
 * <p>Reads are frequent and mutations are rare, so the ordered list is the
 * single source of truth behind a lock, and every mutation publishes an
 * immutable snapshot that {@link #check} iterates without locking. A check is
 * therefore O(rules) with no contention, and a rule edit never races a decision
 * halfway through the list.
 *
 * <p>The default effect starts at {@link NaruAccessEffect#ALLOW} so that
 * introducing the policy changes no behaviour until a rule is written. A host
 * that wants a fail-closed posture flips it to {@code DENY}, and from then on the
 * rules are what grant.
 *
 * <p><b>Pattern base.</b> A file rule written {@code src/**} is project-relative
 * and is resolved against the requesting task's project directory; a pattern
 * starting with {@code ~} expands to the user home; a pattern starting with
 * {@code /} is already absolute. This resolution happens here, at check time,
 * because a rule added under one project must still mean the right thing if the
 * session later changes project.
 */
public class NaruAccessPolicyImpl implements NaruAccessPolicy {

    private final Object lock = new Object();
    private final List<NaruPermissionRule> ordered = new ArrayList<>();
    private final Map<String, Integer> indexById = new HashMap<>();
    private final List<NaruAccessPolicyListener> listeners = new CopyOnWriteArrayList<>();
    /** Declared-safe defaults (source {@link #SOURCE_CORE}) re-seeded by {@link #clearRules()}. */
    private final List<NaruPermissionRule> builtins = new ArrayList<>();
    private boolean seedBuiltins = true;

    private NaruAccessEffect defaultEffect = NaruAccessEffect.ALLOW;
    private int idCounter = 0;
    /** Immutable snapshot, replaced on every mutation and read without the lock. */
    private volatile List<NaruPermissionRule> snapshot = Collections.emptyList();

    public NaruAccessPolicyImpl() {
        resnapshot();
    }

    // ── read ────────────────────────────────────────────────────────────────

    @Override
    public List<NaruPermissionRule> rules() {
        return snapshot;
    }

    @Override
    public List<NaruPermissionRule> rules(String source) {
        List<NaruPermissionRule> all = new ArrayList<>();
        for (NaruPermissionRule r : snapshot) {
            if (eq(source, r.source())) {
                all.add(r);
            }
        }
        return all;
    }

    @Override
    public NOptional<NaruPermissionRule> rule(String id) {
        Integer i = indexById.get(id);
        if (i == null) {
            return NOptional.ofNamedEmpty(NMsg.ofC("rule %s", id));
        }
        return NOptional.of(ordered.get(i));
    }

    @Override
    public List<NaruPermissionRule> findRulesByPattern(String glob) {
        List<NaruPermissionRule> all = new ArrayList<>();
        for (NaruPermissionRule r : snapshot) {
            for (String p : r.patterns()) {
                if (p.equals(glob)) {
                    all.add(r);
                    break;
                }
            }
        }
        return all;
    }

    @Override
    public NaruAccessEffect defaultEffect() {
        return defaultEffect;
    }

    // ── write ───────────────────────────────────────────────────────────────

    @Override
    public NaruPermissionRule putRule(NaruPermissionRule rule) {
        if (rule == null) {
            throw new NIllegalArgumentException(NMsg.ofC("rule must not be null"));
        }
        NaruPermissionRule stored = rule;
        if (NBlankable.isBlank(stored.id())) {
            stored = stored.id("rule-" + nextId());
        }
        if (stored.createdAt() == null) {
            stored = stored.createdAt(Instant.now());
        }
        NaruPermissionRule old;
        synchronized (lock) {
            Integer existing = indexById.get(stored.id());
            if (existing != null) {
                old = ordered.set(existing, stored);
            } else {
                old = null;
                indexById.put(stored.id(), ordered.size());
                ordered.add(stored);
            }
            resnapshot();
        }
        if (old != null) {
            for (NaruAccessPolicyListener l : listeners) {
                l.onRuleUpdated(old, stored);
            }
        } else {
            for (NaruAccessPolicyListener l : listeners) {
                l.onRuleAdded(stored);
            }
        }
        return stored;
    }

    @Override
    public boolean removeRule(String id) {
        NaruPermissionRule removed;
        synchronized (lock) {
            Integer i = indexById.get(id);
            if (i == null) {
                return false;
            }
            removed = ordered.remove((int) i);
            reindex();
            resnapshot();
        }
        for (NaruAccessPolicyListener l : listeners) {
            l.onRuleRemoved(removed);
        }
        return true;
    }

    @Override
    public int removeRules(String source) {
        List<NaruPermissionRule> removed = new ArrayList<>();
        synchronized (lock) {
            for (int i = ordered.size() - 1; i >= 0; i--) {
                if (eq(source, ordered.get(i).source())) {
                    removed.add(ordered.remove(i));
                }
            }
            if (!removed.isEmpty()) {
                reindex();
                resnapshot();
            }
        }
        for (NaruPermissionRule r : removed) {
            for (NaruAccessPolicyListener l : listeners) {
                l.onRuleRemoved(r);
            }
        }
        return removed.size();
    }

    @Override
    public NaruAccessPolicy clearRules() {
        List<NaruPermissionRule> removed;
        synchronized (lock) {
            removed = new ArrayList<>(ordered);
            ordered.clear();
            indexById.clear();
            if (seedBuiltins) {
                for (NaruPermissionRule b : builtins) {
                    indexById.put(b.id(), ordered.size());
                    ordered.add(b);
                }
            }
            resnapshot();
        }
        for (NaruPermissionRule r : removed) {
            for (NaruAccessPolicyListener l : listeners) {
                l.onRuleRemoved(r);
            }
        }
        return this;
    }

    @Override
    public NaruAccessPolicy addBuiltin(NaruPermissionRule rule) {
        if (rule == null) {
            throw new NIllegalArgumentException(NMsg.ofC("built-in rule must not be null"));
        }
        NaruPermissionRule stored = rule.source(NaruAccessPolicy.SOURCE_CORE);
        if (NBlankable.isBlank(stored.id())) {
            stored = stored.id("core-" + nextId());
        }
        if (stored.createdAt() == null) {
            stored = stored.createdAt(Instant.now());
        }
        NaruPermissionRule old;
        synchronized (lock) {
            boolean known = false;
            for (int i = 0; i < builtins.size(); i++) {
                if (stored.id().equals(builtins.get(i).id())) {
                    builtins.set(i, stored);
                    known = true;
                    break;
                }
            }
            if (!known) {
                builtins.add(stored);
            }
            Integer existing = indexById.get(stored.id());
            if (existing != null) {
                old = ordered.set(existing, stored);
            } else {
                old = null;
                // a built-in is the baseline, so it sits before every rule that is
                // not itself a built-in, in declaration order among built-ins
                int pos = 0;
                while (pos < ordered.size() && SOURCE_CORE.equals(ordered.get(pos).source())) {
                    pos++;
                }
                ordered.add(pos, stored);
                reindex();
            }
            resnapshot();
        }
        if (old != null) {
            for (NaruAccessPolicyListener l : listeners) {
                l.onRuleUpdated(old, stored);
            }
        } else {
            for (NaruAccessPolicyListener l : listeners) {
                l.onRuleAdded(stored);
            }
        }
        return this;
    }

    /** Whether {@link #clearRules()} re-seeds the built-in safe defaults. */
    public NaruAccessPolicyImpl setBuiltins(boolean seedBuiltins) {
        this.seedBuiltins = seedBuiltins;
        return this;
    }

    // ── order ───────────────────────────────────────────────────────────────

    @Override
    public NaruAccessPolicy reorder(List<String> orderedIds) {
        synchronized (lock) {
            Set<String> want = new LinkedHashSet<>(orderedIds);
            if (want.size() != orderedIds.size()) {
                throw new NIllegalArgumentException(NMsg.ofC("reorder ids contain duplicates"));
            }
            if (!want.equals(new LinkedHashSet<>(indexById.keySet()))) {
                throw new NIllegalArgumentException(NMsg.ofC("reorder ids must be a permutation of the current rule ids"));
            }
            List<NaruPermissionRule> next = new ArrayList<>(ordered.size());
            for (String id : orderedIds) {
                next.add(ordered.get(indexById.get(id)));
            }
            ordered.clear();
            ordered.addAll(next);
            reindex();
            resnapshot();
        }
        List<String> ids = List.copyOf(orderedIds);
        for (NaruAccessPolicyListener l : listeners) {
            l.onRulesReordered(ids);
        }
        return this;
    }

    @Override
    public NaruAccessPolicy moveRule(String id, int newIndex) {
        synchronized (lock) {
            Integer i = indexById.get(id);
            if (i == null) {
                throw new NIllegalArgumentException(NMsg.ofC("unknown rule %s", id));
            }
            NaruPermissionRule r = ordered.remove((int) i);
            int idx = Math.max(0, Math.min(newIndex, ordered.size()));
            ordered.add(idx, r);
            reindex();
            resnapshot();
        }
        return this;
    }

    @Override
    public NaruAccessPolicy moveRuleBefore(String id, String beforeId) {
        synchronized (lock) {
            Integer anchor = indexById.get(beforeId);
            if (anchor == null) {
                throw new NIllegalArgumentException(NMsg.ofC("unknown rule %s", beforeId));
            }
            Integer i = indexById.get(id);
            if (i == null) {
                throw new NIllegalArgumentException(NMsg.ofC("unknown rule %s", id));
            }
            NaruPermissionRule r = ordered.remove((int) i);
            int anchorAfterRemoval = i < anchor ? anchor - 1 : anchor;
            ordered.add(anchorAfterRemoval, r);
            reindex();
            resnapshot();
        }
        return this;
    }

    @Override
    public NaruAccessPolicy moveRuleAfter(String id, String afterId) {
        synchronized (lock) {
            Integer anchor = indexById.get(afterId);
            if (anchor == null) {
                throw new NIllegalArgumentException(NMsg.ofC("unknown rule %s", afterId));
            }
            Integer i = indexById.get(id);
            if (i == null) {
                throw new NIllegalArgumentException(NMsg.ofC("unknown rule %s", id));
            }
            NaruPermissionRule r = ordered.remove((int) i);
            int anchorAfterRemoval = i < anchor ? anchor - 1 : anchor;
            ordered.add(anchorAfterRemoval + 1, r);
            reindex();
            resnapshot();
        }
        return this;
    }

    // ── default effect ───────────────────────────────────────────────────────

    @Override
    public NaruAccessPolicy defaultEffect(NaruAccessEffect effect) {
        NaruAccessEffect old = this.defaultEffect;
        this.defaultEffect = effect == null ? NaruAccessEffect.ALLOW : effect;
        if (old != this.defaultEffect) {
            for (NaruAccessPolicyListener l : listeners) {
                l.onDefaultEffectChanged(old, this.defaultEffect);
            }
        }
        return this;
    }

    // ── evaluation ───────────────────────────────────────────────────────────

    @Override
    public NaruAccessDecision check(NaruAccessRequest request) {
        return evaluate(request, false);
    }

    @Override
    public NaruAccessDecision check(NaruResource resource, NaruAction action, NaruActor actor) {
        return evaluate(NaruAccessRequest.of(resource, action, actor), false);
    }

    @Override
    public NaruAccessDecision explain(NaruAccessRequest request) {
        return evaluate(request, true);
    }

    @Override
    public boolean isAllowed(NaruResource resource, NaruAction action, NaruActor actor) {
        return check(resource, action, actor).allowed();
    }

    @Override
    public void requireAccess(NaruResource resource, NaruAction action, NaruActor actor)
            throws NaruAccessDeniedException {
        NaruAccessDecision d = check(resource, action, actor);
        if (!d.allowed()) {
            throw new NaruAccessDeniedException(d);
        }
    }

    private NaruAccessDecision evaluate(NaruAccessRequest request, boolean trace) {
        List<NaruPermissionRule> rules = snapshot;
        String base = patternBase(request);
        NaruResourceType type = request.resource().type();
        List<NaruAccessTrace> traces = trace ? new ArrayList<>() : null;
        NaruAccessDecision decision = null;
        for (NaruPermissionRule rule : rules) {
            if (!rule.enabled()) {
                if (trace) {
                    traces.add(new NaruAccessTrace(rule, false, "disabled"));
                }
                continue;
            }
            List<String> effective = resolvePatterns(rule.patterns(), base, type);
            boolean matched = rule.matches(request.resource(), request.action(), request.actor(), effective);
            if (trace) {
                traces.add(new NaruAccessTrace(rule, matched, matched ? "matched" : "filter did not match"));
            }
            if (matched) {
                decision = rule.effect() == NaruAccessEffect.ALLOW
                        ? NaruAccessDecision.allow(rule, reason("allow", request, rule))
                        : NaruAccessDecision.deny(rule, reason("deny", request, rule));
                break;
            }
        }
        if (decision == null) {
            decision = defaultEffect == NaruAccessEffect.ALLOW
                    ? NaruAccessDecision.allow(null, reason("allow", request, null))
                    : NaruAccessDecision.deny(null, reason("deny", request, null));
        }
        if (trace) {
            decision = decision.withTrace(traces);
        }
        for (NaruAccessPolicyListener l : listeners) {
            l.onDecision(request, decision);
        }
        return decision;
    }

    private static String reason(String verb, NaruAccessRequest request, NaruPermissionRule rule) {
        StringBuilder sb = new StringBuilder();
        sb.append("permission ").append(verb).append("ed: ");
        if (request.actor() != null) {
            sb.append("actor ").append(request.actor()).append(' ');
        }
        sb.append("may ").append(request.action().id()).append(' ')
                .append(request.resource().type().id()).append(' ')
                .append(request.resource().locator());
        if (rule != null) {
            sb.append(" (rule ").append(rule.id()).append(')');
        } else {
            sb.append(" (default)");
        }
        return sb.toString();
    }

    // ── pattern base resolution ──────────────────────────────────────────────

    private static String patternBase(NaruAccessRequest request) {
        NaruResourceType type = request.resource().type();
        boolean pathLike = NaruResourceType.FILE.equals(type) || NaruResourceType.DIR.equals(type);
        if (!pathLike || request.task() == null) {
            return null;
        }
        NaruTask task = request.task();
        return task.projectDir() == null ? null : task.projectDir().toString();
    }

    private static List<String> resolvePatterns(List<String> patterns, String base, NaruResourceType type) {
        if (patterns.isEmpty() || base == null) {
            return patterns;
        }
        boolean changed = false;
        List<String> resolved = new ArrayList<>(patterns.size());
        for (String p : patterns) {
            String r = resolvePattern(p, base);
            if (!r.equals(p)) {
                changed = true;
            }
            resolved.add(r);
        }
        return changed ? resolved : patterns;
    }

    static String resolvePattern(String pattern, String projectDir) {
        if (NBlankable.isBlank(pattern)) {
            return pattern;
        }
        if (pattern.startsWith("~")) {
            String home = System.getProperty("user.home", "~");
            return home + pattern.substring(1);
        }
        if (pattern.startsWith("/")) {
            return pattern;
        }
        // a pattern that already matches anywhere keeps its meaning
        if (pattern.startsWith("**") || pattern.startsWith("*")) {
            return pattern;
        }
        String base = projectDir;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + pattern;
    }

    // ── observation ──────────────────────────────────────────────────────────

    @Override
    public void addListener(NaruAccessPolicyListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(NaruAccessPolicyListener listener) {
        listeners.remove(listener);
    }

    // ── internals ────────────────────────────────────────────────────────────

    private int nextId() {
        synchronized (lock) {
            return ++idCounter;
        }
    }

    private void reindex() {
        indexById.clear();
        for (int i = 0; i < ordered.size(); i++) {
            indexById.put(ordered.get(i).id(), i);
        }
    }

    private void resnapshot() {
        snapshot = Collections.unmodifiableList(new ArrayList<>(ordered));
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    public String toString() {
        return "NaruAccessPolicy[" + ordered.size() + " rules, default=" + defaultEffect + "]";
    }
}
