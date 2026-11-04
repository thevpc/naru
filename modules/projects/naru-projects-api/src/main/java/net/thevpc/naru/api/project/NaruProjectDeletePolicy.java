package net.thevpc.naru.api.project;

/**
 * What happens to a project's sessions when the project is removed.
 *
 * <ul>
 *   <li>{@link #REJECT} (the default): removal fails while any session points at the
 *       project; the error carries the count and up to ten session identifiers. Nothing
 *       changes.</li>
 *   <li>{@link #DETACH}: every session of the project is reassigned to "no project"
 *       (only {@code projectId} changes), then the project is deleted. Sessions that fail
 *       to detach are reported, not hidden.</li>
 *   <li>{@link #CASCADE}: refuses outright if any session is live (held in memory with
 *       running or blocked-on-input tasks), otherwise deletes the sessions through the
 *       session store's normal delete path and the project last. A partial failure leaves
 *       the project in place with whatever sessions remain, precisely reported. The caller
 *       is responsible for confirming the deletion; the service does not prompt.</li>
 * </ul>
 */
public enum NaruProjectDeletePolicy {
    REJECT,
    DETACH,
    CASCADE
}
