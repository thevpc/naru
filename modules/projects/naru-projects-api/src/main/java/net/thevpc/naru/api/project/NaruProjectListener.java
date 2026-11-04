package net.thevpc.naru.api.project;

/**
 * In-process notifications from {@link NaruProjectService}, fired on the calling thread
 * after a successful operation, never for a failed one.
 *
 * <p>Listener exceptions are caught and logged by the service: one broken listener can
 * neither fail a committed write nor starve the other listeners.
 *
 * <p>This stream is deliberately small — it covers what the project side changes. A UI
 * keeping a Project → Session tree fresh combines it with the session lifecycle stream
 * (sessions starting and stopping come from {@code NaruSessionListener} / the session
 * event log, not from here): {@code SESSION_MOVED} re-parents a row, {@code PROJECT_*}
 * events add or remove branches, and session creation/death stays on the session side.
 * There is no transport in v1: no HTTP, no SSE — in-process only.
 */
public interface NaruProjectListener {

    /** A project was created. */
    default void onProjectCreated(NaruProject project) {
    }

    /** A project was updated; both the row as it was and the row as it now is. */
    default void onProjectUpdated(NaruProject before, NaruProject after) {
    }

    /** A project row was removed (whatever happened to its sessions is in the report). */
    default void onProjectRemoved(NaruProject project) {
    }

    /**
     * A session changed project. Ids, not names: after the move the name could belong to
     * a deleted row, the id is what every listener already keys on. {@code fromProjectId}
     * or {@code toProjectId} may be null — the endpoints of a detach or an adoption.
     */
    default void onSessionMoved(String sessionId, String fromProjectId, String toProjectId) {
    }
}
