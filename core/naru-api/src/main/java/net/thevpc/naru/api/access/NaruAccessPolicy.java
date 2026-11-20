package net.thevpc.naru.api.access;

import net.thevpc.nuts.util.NOptional;

import java.util.List;

/**
 * The transversal access policy: an ordered list of {@link NaruPermissionRule}
 * plus a fallback effect, and the one question every domain asks it.
 *
 * <p>The policy lives on the session, not on any one extension, because the same
 * question is asked by the file tools, the web fetch tool, the shell tool, the
 * directive dispatcher and the tool gate. A domain does not own a policy; it
 * consults the session's.
 *
 * <p><b>Order matters.</b> Rules are evaluated first-match-wins, so the list is
 * not a set: inserting a {@code DENY} ahead of a broad {@code ALLOW} narrows it
 * without rewriting it. The mutating methods here therefore expose order as a
 * first-class thing ({@link #reorder}, {@link #moveRuleBefore},
 * {@link #moveRuleAfter}), not as an afterthought.
 *
 * <p>A fresh policy starts with no rules and a default effect of
 * {@link NaruAccessEffect#ALLOW}, so enabling the feature changes no behaviour
 * until someone writes a rule. Hosts that want a fail-closed posture flip
 * {@link #defaultEffect(NaruAccessEffect)}.
 */
public interface NaruAccessPolicy {

    /**
     * Source tag for rules that ship with the build. A built-in is declared once
     * with {@link #addBuiltin(NaruPermissionRule)} and is re-seeded by
     * {@link #clearRules()}, so a safe default survives a wipe instead of being
     * something an operator can lose by accident.
     */
    String SOURCE_CORE = "core";

    // ── read ────────────────────────────────────────────────────────────────

    /** All rules, in evaluation order. */
    List<NaruPermissionRule> rules();

    /** Rules whose {@link NaruPermissionRule#source()} equals {@code source}. */
    List<NaruPermissionRule> rules(String source);

    NOptional<NaruPermissionRule> rule(String id);

    /** Rules that carry a pattern matching {@code glob} (used by "which rule covers this path" queries). */
    List<NaruPermissionRule> findRulesByPattern(String glob);

    NaruAccessEffect defaultEffect();

    // ── write ───────────────────────────────────────────────────────────────

    /**
     * Adds a rule, or replaces in place when a rule with the same id already
     * exists — the replacement keeps the old rule's position, so editing a rule
     * never silently changes its precedence.
     */
    NaruPermissionRule putRule(NaruPermissionRule rule);

    boolean removeRule(String id);

    /** Removes every rule from {@code source} and returns how many were removed. */
    int removeRules(String source);

    /** Removes all rules. Built-ins are re-seeded unless the policy was built without them. */
    NaruAccessPolicy clearRules();

    /**
     * Declares a built-in safe default: the rule is force-tagged
     * {@link #SOURCE_CORE}, installed at the front of the list (a baseline
     * guarantee must not be shadowed by a broad rule added later) and remembered
     * so {@link #clearRules()} re-seeds it.
     *
     * <p>Calling this twice with the same id replaces the earlier declaration in
     * place, so an extension may install its built-ins on every session start
     * without accumulating duplicates.
     */
    NaruAccessPolicy addBuiltin(NaruPermissionRule rule);

    // ── order (first match wins) ─────────────────────────────────────────────

    /** Reorders to exactly {@code orderedIds}; the argument must be a permutation of the current ids. */
    NaruAccessPolicy reorder(List<String> orderedIds);

    NaruAccessPolicy moveRule(String id, int newIndex);

    NaruAccessPolicy moveRuleBefore(String id, String beforeId);

    NaruAccessPolicy moveRuleAfter(String id, String afterId);

    // ── default effect ───────────────────────────────────────────────────────

    NaruAccessPolicy defaultEffect(NaruAccessEffect effect);

    // ── evaluation ───────────────────────────────────────────────────────────

    NaruAccessDecision check(NaruAccessRequest request);

    NaruAccessDecision check(NaruResource resource, NaruAction action, NaruActor actor);

    /** Like {@link #check(NaruAccessRequest)} but records a trace of every rule weighed. */
    NaruAccessDecision explain(NaruAccessRequest request);

    boolean isAllowed(NaruResource resource, NaruAction action, NaruActor actor);

    void requireAccess(NaruResource resource, NaruAction action, NaruActor actor)
            throws NaruAccessDeniedException;

    // ── observation ──────────────────────────────────────────────────────────

    void addListener(NaruAccessPolicyListener listener);

    void removeListener(NaruAccessPolicyListener listener);
}
