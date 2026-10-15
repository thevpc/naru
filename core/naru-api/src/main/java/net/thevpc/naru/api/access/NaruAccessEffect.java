package net.thevpc.naru.api.access;

/**
 * What a rule does when it matches: grants the action or forbids it.
 *
 * <p>Both effects are needed, not just {@code ALLOW}, because rules are ordered
 * and first match wins. {@code DENY} is how an exception is carved out ahead of
 * a broader grant — {@code deny-agents-write-src} placed before
 * {@code devs-write-src} narrows it without rewriting it.
 */
public enum NaruAccessEffect {
    ALLOW,
    DENY;

    public boolean allows() {
        return this == ALLOW;
    }
}
