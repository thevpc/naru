package net.thevpc.naru.api.access;

import java.util.List;

/**
 * Observes a {@link NaruAccessPolicy}: rule edits, reorders, default changes and
 * decisions.
 *
 * <p>The event-driven permissions extension uses this in both directions — as an
 * audit sink for {@code onDecision} and as a notification when a rule it cares
 * about changes. Every method has a default, so a listener implements only what
 * it needs.
 */
public interface NaruAccessPolicyListener {

    default void onRuleAdded(NaruPermissionRule rule) {
    }

    default void onRuleRemoved(NaruPermissionRule rule) {
    }

    default void onRuleUpdated(NaruPermissionRule oldRule, NaruPermissionRule newRule) {
    }

    default void onRulesReordered(List<String> ids) {
    }

    default void onDefaultEffectChanged(NaruAccessEffect oldEffect, NaruAccessEffect newEffect) {
    }

    default void onDecision(NaruAccessRequest request, NaruAccessDecision decision) {
    }
}
