package net.thevpc.naru.api.project;

/**
 * One row of the desktop tree: a project and how many sessions currently name it as
 * their project. The count is computed from the session store's headers, never stored on
 * the project: sessions move by changing {@code session.projectId} alone, so any count
 * written into the project row would be wrong the moment a move landed elsewhere.
 *
 * <p>The synthetic "no project" node is deliberately not a {@code NaruProjectNode}: it is
 * not a stored project. A tree renders it from
 * {@link NaruProjectService#countStandaloneSessions()} plus a session query filtered to
 * {@code ProjectFilter.none()}, and an "unknown project" node from
 * {@link NaruProjectService#findOrphanedSessions(String, int)}.
 */
public record NaruProjectNode(NaruProject project, long sessionCount) {
}
