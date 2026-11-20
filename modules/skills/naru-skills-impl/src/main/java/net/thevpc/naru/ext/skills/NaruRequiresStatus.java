package net.thevpc.naru.ext.skills;

/**
 * The request-build outcome of a skill's {@code requires} tool-tag expression against the
 * task's held tags.
 */
public enum NaruRequiresStatus {
    /** The skill declares no {@code requires} expression. */
    NONE,

    /** The expression holds for the task's tags. */
    SATISFIED,

    /**
     * The expression does not hold (a positive tag is missing or a negated tag is held),
     * but every tag it references is registered — the task could satisfy it in principle.
     */
    UNSATISFIED,

    /**
     * The expression references at least one tool tag no provider declares. No task can
     * ever satisfy it, so it is reported separately from a plain unsatisfied state: the
     * fix is a provider, not a tag grant.
     */
    UNSATISFIABLE
}