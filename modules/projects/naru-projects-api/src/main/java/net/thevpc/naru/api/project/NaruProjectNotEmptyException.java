package net.thevpc.naru.api.project;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalStateException;

import java.util.List;

/**
 * A {@link NaruProjectDeletePolicy#REJECT} removal found sessions still pointing at the
 * project, so nothing changed. The error names the count and up to ten of the sessions
 * (ids or names) so the caller can decide between detach, cascade, or doing nothing.
 */
public class NaruProjectNotEmptyException extends NIllegalStateException {

    private final String projectId;
    private final long sessionCount;
    private final List<String> sampleSessions;

    public NaruProjectNotEmptyException(String projectId, long sessionCount, List<String> sampleSessions) {
        super(NMsg.ofC("project %s still has %s session(s): %s%s",
                projectId, sessionCount, sampleSessions,
                sessionCount > sampleSessions.size() ? "…" : ""));
        this.projectId = projectId;
        this.sessionCount = sessionCount;
        this.sampleSessions = sampleSessions == null ? List.of() : List.copyOf(sampleSessions);
    }

    public String projectId() {
        return projectId;
    }

    public long sessionCount() {
        return sessionCount;
    }

    /** Up to ten session ids or names, for the message the user sees. */
    public List<String> sampleSessions() {
        return sampleSessions;
    }
}
