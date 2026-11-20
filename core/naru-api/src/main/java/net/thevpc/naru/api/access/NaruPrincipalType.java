package net.thevpc.naru.api.access;

/**
 * What kind of thing a {@link NaruPrincipal} identifies.
 *
 * <p>This is the durable taxonomy, distinct from the ephemeral {@link NaruActorKind}:
 * a principal says <em>who exists</em> (a user, a group of users, an agent identity,
 * the system itself), while an actor says <em>who is acting right now</em> and in
 * what role context. A {@code USER} principal can act directly (actor kind
 * {@code USER}) or through an agent it owns (actor kind {@code AGENT}).
 */
public enum NaruPrincipalType {
    /** A human or service identity. */
    USER,
    /** An agent identity, typically owned by a user. */
    AGENT,
    /** A named set of users, used as a subject for bulk grants. */
    GROUP,
    /** The runtime itself; acts on its own behalf, never on a user's. */
    SYSTEM
}
