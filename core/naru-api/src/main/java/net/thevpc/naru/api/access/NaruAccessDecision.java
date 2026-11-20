package net.thevpc.naru.api.access;

import net.thevpc.nuts.util.NOptional;

import java.util.Collections;
import java.util.List;

/**
 * The answer to an {@link NaruAccessRequest}: allowed or not, which effect
 * decided it, and which rule (if any) was responsible.
 *
 * <p>The matched rule is kept even when it is {@code null} (nothing matched and
 * the default effect decided), because "denied by default" and "denied by rule X"
 * are different facts and a denial message should not blur them.
 */
public final class NaruAccessDecision {

    private final boolean allowed;
    private final NaruAccessEffect effect;
    private final NaruPermissionRule matchedRule;
    private final String reason;
    private final List<NaruAccessTrace> trace;

    private NaruAccessDecision(boolean allowed, NaruAccessEffect effect, NaruPermissionRule matchedRule,
                               String reason, List<NaruAccessTrace> trace) {
        this.allowed = allowed;
        this.effect = effect;
        this.matchedRule = matchedRule;
        this.reason = reason;
        this.trace = trace == null ? Collections.emptyList() : Collections.unmodifiableList(trace);
    }

    public static NaruAccessDecision allow(NaruPermissionRule rule, String reason) {
        return new NaruAccessDecision(true, NaruAccessEffect.ALLOW, rule, reason, null);
    }

    public static NaruAccessDecision deny(NaruPermissionRule rule, String reason) {
        return new NaruAccessDecision(false, NaruAccessEffect.DENY, rule, reason, null);
    }

    public NaruAccessDecision withTrace(List<NaruAccessTrace> trace) {
        return new NaruAccessDecision(allowed, effect, matchedRule, reason, trace);
    }

    public boolean allowed() {
        return allowed;
    }

    public NaruAccessEffect effect() {
        return effect;
    }

    public NOptional<NaruPermissionRule> matchedRule() {
        // NB: NOptional.ofNullable(...) in this codebase is a "valid" optional even
        // when the value is null, so it cannot express "no rule matched". Build the
        // empty case explicitly.
        return matchedRule == null ? NOptional.ofEmpty() : NOptional.of(matchedRule);
    }

    public String reason() {
        return reason;
    }

    public List<NaruAccessTrace> trace() {
        return trace;
    }

    @Override
    public String toString() {
        return (allowed ? "ALLOW" : "DENY") + " (" + effect + ")"
                + (matchedRule != null ? " rule=" + matchedRule.id() : " rule=<default>")
                + (reason == null ? "" : " -- " + reason);
    }
}
