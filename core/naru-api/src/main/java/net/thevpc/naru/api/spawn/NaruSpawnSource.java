package net.thevpc.naru.api.spawn;

/**
 * Where a resolved spawn item came from.
 * <p>
 * Precedence, lowest to highest: {@link #DEFAULT} (extension / spawn-kind / strategy
 * defaults) → {@link #POLICY} (named spawn policy) → {@link #FLAG} (call-site flags) →
 * {@link #CONTRACT} (target contract; skills only — a contract may never add tags).
 * An item contributed at a higher precedence overrides the same item contributed at a
 * lower one; equal-precedence additions win over equal-precedence revocations.
 */
public enum NaruSpawnSource {
    /** Extension or spawn-kind default, applied first. */
    DEFAULT,
    /** Named spawn policy, applied after the extension defaults. */
    POLICY,
    /** Call-site flags, applied after the policy. */
    FLAG,
    /** Target contract, applied last and validated afterwards. */
    CONTRACT;

    @Override
    public String toString() {
        return name().toLowerCase();
    }
}