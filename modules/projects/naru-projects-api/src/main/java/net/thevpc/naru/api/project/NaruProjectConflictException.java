package net.thevpc.naru.api.project;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalStateException;

/**
 * A project write lost the compare-and-set on the version token: the row changed between
 * the read that produced {@code expectedVersion} and this write. The caller re-reads and
 * retries, or tells the user; nothing was changed.
 */
public class NaruProjectConflictException extends NIllegalStateException {

    private final String projectId;
    private final long expectedVersion;
    private final long actualVersion;

    public NaruProjectConflictException(String projectId, long expectedVersion, long actualVersion) {
        super(NMsg.ofC("project %s version conflict: expected %s, found %s",
                projectId, expectedVersion, actualVersion));
        this.projectId = projectId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public String projectId() {
        return projectId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public long actualVersion() {
        return actualVersion;
    }
}
