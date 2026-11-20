package net.thevpc.naru.api.store;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalStateException;

/**
 * Thrown when a checkpoint loses the compare-and-swap on a session's version token.
 *
 * <p>The loser must not write tasks, history, routines or extension state: the version
 * check is what keeps two processes from interleaving two conversations into one folder.
 */
public class NaruSessionConflictException extends NIllegalStateException {

    private final String uuid;
    private final long expectedVersion;
    private final long actualVersion;

    public NaruSessionConflictException(String uuid, long expectedVersion, long actualVersion) {
        super(NMsg.ofC("session %s version conflict: expected %s, found %s",
                uuid, expectedVersion, actualVersion));
        this.uuid = uuid;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public String uuid() {
        return uuid;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public long actualVersion() {
        return actualVersion;
    }
}
