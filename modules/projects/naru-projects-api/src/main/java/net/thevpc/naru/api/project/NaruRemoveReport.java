package net.thevpc.naru.api.project;

import java.util.List;
import java.util.Map;

/**
 * The outcome of a {@code remove} — what happened to the project and to each of its
 * sessions, exactly as it happened. The service must not hide failures behind a clean
 * report: everything that failed is named here.
 *
 * @param projectId       the project the operation targeted
 * @param policy          the delete policy that was asked for
 * @param projectDeleted  whether the project row itself is gone now
 * @param sessionsDetached ids detached under {@code DETACH} (successful ones)
 * @param sessionsDeleted  ids deleted under {@code CASCADE} (successful ones)
 * @param failedSessions  session id to failure reason, for every session the operation
 *                        could not move out of the way
 * @param remainingSessions session ids still pointing at the project after the call —
 *                        non-empty only when {@code projectDeleted} is false
 */
public final class NaruRemoveReport {

    private final String projectId;
    private final NaruProjectDeletePolicy policy;
    private final boolean projectDeleted;
    private final List<String> sessionsDetached;
    private final List<String> sessionsDeleted;
    private final Map<String, String> failedSessions;
    private final List<String> remainingSessions;

    public NaruRemoveReport(String projectId, NaruProjectDeletePolicy policy, boolean projectDeleted,
                            List<String> sessionsDetached, List<String> sessionsDeleted,
                            Map<String, String> failedSessions, List<String> remainingSessions) {
        this.projectId = projectId;
        this.policy = policy;
        this.projectDeleted = projectDeleted;
        this.sessionsDetached = sessionsDetached == null ? List.of() : List.copyOf(sessionsDetached);
        this.sessionsDeleted = sessionsDeleted == null ? List.of() : List.copyOf(sessionsDeleted);
        this.failedSessions = failedSessions == null ? Map.of() : Map.copyOf(failedSessions);
        this.remainingSessions = remainingSessions == null ? List.of() : List.copyOf(remainingSessions);
    }

    public String projectId() {
        return projectId;
    }

    public NaruProjectDeletePolicy policy() {
        return policy;
    }

    public boolean projectDeleted() {
        return projectDeleted;
    }

    public List<String> sessionsDetached() {
        return sessionsDetached;
    }

    public List<String> sessionsDeleted() {
        return sessionsDeleted;
    }

    public Map<String, String> failedSessions() {
        return failedSessions;
    }

    public List<String> remainingSessions() {
        return remainingSessions;
    }

    /** True when nothing failed; a partial cascade can still report false here while deleting some rows. */
    public boolean complete() {
        return failedSessions.isEmpty();
    }

    @Override
    public String toString() {
        return "NaruRemoveReport{project=" + projectId + ", policy=" + policy
                + ", deleted=" + projectDeleted
                + ", detached=" + sessionsDetached.size()
                + ", removed=" + sessionsDeleted.size()
                + ", failed=" + failedSessions.size()
                + ", remaining=" + remainingSessions.size() + '}';
    }
}
