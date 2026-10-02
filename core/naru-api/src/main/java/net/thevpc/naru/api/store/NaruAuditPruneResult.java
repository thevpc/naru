package net.thevpc.naru.api.store;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What an audit prune removed.
 *
 * <p>Both counts matter, and they are not redundant. Dropping records is what the user
 * asked for; deleting blobs is what frees the disk. A prune that drops 400 records and
 * reclaims 0 bytes is a prune that removed 400 conversation prefixes and left the disk
 * exactly as full as it was -- worth seeing, and the reason {@code bytesReclaimed} is
 * reported rather than assumed.
 */
public class NaruAuditPruneResult {

    private final int recordsDropped;
    private final int blobsDeleted;
    private final long bytesReclaimed;
    private final int blobsKept;
    private final Map<String, Integer> droppedPerTask = new LinkedHashMap<>();

    public NaruAuditPruneResult(int recordsDropped, int blobsDeleted, long bytesReclaimed,
                                int blobsKept, Map<String, Integer> droppedPerTask) {
        this.recordsDropped = recordsDropped;
        this.blobsDeleted = blobsDeleted;
        this.bytesReclaimed = bytesReclaimed;
        this.blobsKept = blobsKept;
        if (droppedPerTask != null) {
            this.droppedPerTask.putAll(droppedPerTask);
        }
    }

    public int recordsDropped() {
        return recordsDropped;
    }

    public int blobsDeleted() {
        return blobsDeleted;
    }

    public long bytesReclaimed() {
        return bytesReclaimed;
    }

    /**
     * Blobs that are still referenced and were kept. Reported so that a caller can tell
     * "nothing was reachable" from "the sweep ran and found work still in use".
     */
    public int blobsKept() {
        return blobsKept;
    }

    public Map<String, Integer> droppedPerTask() {
        return droppedPerTask;
    }

    @Override
    public String toString() {
        return "NaruAuditPruneResult{records=" + recordsDropped + ", blobsDeleted=" + blobsDeleted
                + ", bytesReclaimed=" + bytesReclaimed + ", blobsKept=" + blobsKept + '}';
    }
}