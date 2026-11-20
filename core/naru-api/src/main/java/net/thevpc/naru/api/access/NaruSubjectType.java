package net.thevpc.naru.api.access;

/**
 * How a {@link NaruSubject} names the actor(s) a rule applies to.
 *
 * <p>Each kind answers a different question an operator actually asks: "this
 * user" ({@link #USER}), "anyone in this group" ({@link #GROUP}), "anyone
 * holding this role" ({@link #ROLE}), "this exact agent" ({@link #ACTOR}), "any
 * agent at all" ({@link #AGENT}), "the runtime" ({@link #SYSTEM}), or "everyone"
 * ({@link #ANY}).
 */
public enum NaruSubjectType {
    /** Matches every actor. */
    ANY,
    /** A user by id, including a user that owns the acting agent. */
    USER,
    /** Any actor whose group memberships contain the id. */
    GROUP,
    /** Any actor whose role labels contain the id. */
    ROLE,
    /** A specific principal id, whether it is a user or an agent. */
    ACTOR,
    /** Any agent actor, regardless of owner. */
    AGENT,
    /** The runtime acting on its own behalf. */
    SYSTEM
}
