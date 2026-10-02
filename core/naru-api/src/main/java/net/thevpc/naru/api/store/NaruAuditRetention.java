package net.thevpc.naru.api.store;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How far back an audit prune reaches.
 *
 * <p>Two independent axes, because an audit log grows along both and a user asking to keep
 * "the last week" does not care which of them was the binding one:
 *
 * <ul>
 *   <li>{@code olderThan} drops whole records by timestamp;</li>
 *   <li>{@code maxRecordsPerTask} keeps at most N of the most recent records for a task,
 *       which is what protects a task that has run a hundred provider calls today.</li>
 * </ul>
 *
 * <p>Neither is expressed in bytes, on purpose. Predicting how many bytes a request body
 * becomes requires knowing the provider's compression, and a limit the user cannot
 * predict is a limit they will set wrong.
 */
public class NaruAuditRetention {

    private final Instant olderThan;
    private final int maxRecordsPerTask;
    private final Map<Long, Integer> maxRecordsForTask = new LinkedHashMap<>();

    public NaruAuditRetention(Instant olderThan, int maxRecordsPerTask) {
        this.olderThan = olderThan;
        this.maxRecordsPerTask = maxRecordsPerTask;
    }

    public static NaruAuditRetention all() {
        return new NaruAuditRetention(null, -1);
    }

    public NaruAuditRetention maxRecordsForTask(long taskId, int max) {
        maxRecordsForTask.put(taskId, max);
        return this;
    }

    public Instant olderThan() {
        return olderThan;
    }

    public int maxRecordsPerTask() {
        return maxRecordsPerTask;
    }

    public Map<Long, Integer> maxRecordsForTask() {
        return maxRecordsForTask;
    }

    /**
     * Whether a record survives on timestamp alone. Count-based limits are applied
     * separately, after the survivors are in order.
     */
    public boolean keeps(Instant timestamp) {
        if (olderThan == null) {
            return true;
        }
        return timestamp != null && timestamp.isAfter(olderThan);
    }

    public boolean isEmpty() {
        return olderThan == null && maxRecordsPerTask < 0 && maxRecordsForTask.isEmpty();
    }

    @Override
    public String toString() {
        return "NaruAuditRetention{olderThan=" + olderThan + ", maxPerTask=" + maxRecordsPerTask
                + ", perTask=" + maxRecordsForTask + '}';
    }
}