package net.thevpc.naru.api.store;

import java.time.Instant;

/**
 * One session header row, as a query returns it: everything a tree or listing needs, and
 * nothing that requires opening the session's tasks.
 *
 * <p>Lighter than loading full state on purpose: a store answers queries by scanning
 * {@code session.tson} headers, so the cost of a listing stays proportional to metadata,
 * not to conversation size.
 *
 * <p>{@code projectMissing} is <b>not</b> a store fact: the session store knows nothing
 * about projects (the stores are dissociated; the only link is {@code projectId}). It is
 * computed by the service layer, which can see both stores, using
 * {@link #withProjectMissing(boolean)}. A row straight from any store has it {@code false};
 * a {@code true} here means the service found no project carrying this row's
 * {@code projectId}. Such a session is tolerated, never an error: public sessions committed
 * to git may name a project that exists only on the author's machine.
 *
 * @param uuid               session id
 * @param name               session name, may be null
 * @param scope              where the row physically lives (public or private root)
 * @param creationInstant    creation time
 * @param modificationInstant last touch; the key most listings sort on
 * @param sessionDir         the session's anchor directory, as stored; may be null
 * @param workingDir         the session's working directory, as stored; may be null
 * @param projectId          owning project id, or null for a standalone session
 * @param user               opaque owner string, or null
 * @param version            the metadata CAS token at read time
 * @param projectMissing     service-computed; see above
 */
public record NaruSessionSummary(String uuid, String name, NaruSessionScope scope,
                                 Instant creationInstant, Instant modificationInstant,
                                 String sessionDir, String workingDir,
                                 String projectId, String user,
                                 long version, boolean projectMissing) {

    /** Same row, re-stamped by the service after comparing projectId against the project store. */
    public NaruSessionSummary withProjectMissing(boolean missing) {
        return new NaruSessionSummary(uuid, name, scope, creationInstant, modificationInstant,
                sessionDir, workingDir, projectId, user, version, missing);
    }
}
