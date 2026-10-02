package net.thevpc.naru.api.store;

import java.util.Collections;
import java.util.List;

/**
 * What a {@link NaruSessionStore#saveHistory} call actually did.
 *
 * <p>Returned rather than left to inspection because the interesting cases are the ones
 * where very little happened, and "nothing" needs to be distinguishable from "I did not
 * look":
 *
 * <ul>
 *   <li>{@code created} is zero when the list was unchanged -- the case that must be
 *       cheap, since it is the case that happens on every statement of every turn;</li>
 *   <li>{@code reused} counts messages whose file was already there. A run that resumes a
 *       session and immediately appends five messages reuses the whole earlier
 *       conversation and writes five files;</li>
 *   <li>{@code deleted} counts files that this call orphaned. Non-zero means the caller's
 *       change actually dropped messages, not merely appended.</li>
 * </ul>
 */
public class NaruHistorySave {

    private final long taskId;
    private final int created;
    private final int reused;
    private final int deleted;
    private final List<String> ids;

    public NaruHistorySave(long taskId, int created, int reused, int deleted, List<String> ids) {
        this.taskId = taskId;
        this.created = created;
        this.reused = reused;
        this.deleted = deleted;
        this.ids = ids == null ? Collections.emptyList() : Collections.unmodifiableList(ids);
    }

    public static NaruHistorySave unchanged(long taskId) {
        return new NaruHistorySave(taskId, 0, 0, 0, Collections.emptyList());
    }

    public long taskId() {
        return taskId;
    }

    public int created() {
        return created;
    }

    public int reused() {
        return reused;
    }

    public int deleted() {
        return deleted;
    }

    /**
     * The ids now in force for this task, in order. Two identical messages in a row get two
     * distinct ids, because dropping either one would change the conversation.
     */
    public List<String> ids() {
        return ids;
    }

    @Override
    public String toString() {
        return "NaruHistorySave{task=" + taskId + ", created=" + created
                + ", reused=" + reused + ", deleted=" + deleted + '}';
    }
}