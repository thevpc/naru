package net.thevpc.naru.api.registry;

/** Whether a live tool resource prevents a session from hibernating. */
public enum NaruHibernationPolicy {
    PINS,
    RESTARTABLE
}
