package net.thevpc.naru.api.access;

/**
 * The role context an actor is acting in — as opposed to {@link NaruPrincipalType},
 * which says what the actor <em>is</em>.
 *
 * <p>The distinction matters exactly once and everywhere: an agent principal acts
 * with kind {@code AGENT} even though a human ultimately stands behind it, and a
 * rule that says "agents may not read credentials" keys off the kind, not the
 * principal type. The owning user is still reachable through
 * {@link NaruActor#owner()}, so a rule can be written for the user as well.
 */
public enum NaruActorKind {
    /** A human or service acting directly. */
    USER,
    /** An agent acting on behalf of its owner. */
    AGENT,
    /** The runtime acting on its own behalf. */
    SYSTEM
}
