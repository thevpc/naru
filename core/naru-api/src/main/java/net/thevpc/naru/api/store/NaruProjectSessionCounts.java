package net.thevpc.naru.api.store;

import java.util.Map;

/**
 * How many sessions name each project, plus the standalone count. One pass over the
 * session headers; the counts are never stored on the project side (sessions move by
 * changing {@code projectId} alone, so a stored count could only be stale).
 *
 * @param counts    sessions per project id; project ids that no session names are absent
 * @param noProject sessions with {@code projectId == null}
 */
public record NaruProjectSessionCounts(Map<String, Long> counts, long noProject) {

    public NaruProjectSessionCounts {
        counts = counts == null ? Map.of() : Map.copyOf(counts);
    }

    public long of(String projectId) {
        if (projectId == null) {
            return noProject;
        }
        return counts.getOrDefault(projectId, 0L);
    }

    public long total() {
        long sum = noProject;
        for (Long c : counts.values()) {
            sum += c;
        }
        return sum;
    }
}
