package net.thevpc.naru.api.project;

import net.thevpc.naru.api.registry.NaruAgentExtension;
import net.thevpc.naru.api.store.NaruPage;
import net.thevpc.naru.api.store.NaruSessionQuery;
import net.thevpc.naru.api.store.NaruSessionSummary;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;

import java.util.List;

/**
 * The project service: the <b>only</b> component allowed to touch both stores — the
 * project store (projects) and the session store (sessions) — because the two are
 * deliberately dissociated and the only link between them is {@code session.projectId}.
 *
 * <p>It is an agent extension, reached with
 * {@code agent.extension(NaruProjectService.EXTENSION, NaruProjectService.class)}: a build
 * without the projects module simply has no service, and the kernel never asks for one. The
 * implementation also registers itself as a
 * {@link net.thevpc.naru.api.agent.NaruSessionGroupResolver}, which is how
 * {@code newSession().project(ref)} seeds a session from a project.
 *
 * <h2>What a project does and does not do</h2>
 * A project supplies <em>initial</em> values to new sessions: its {@code folder} becomes
 * the session's {@code sessionDir} and its {@code user} becomes the session's {@code user}
 * — copied at creation, and never read through afterwards. Editing a project never
 * propagates to existing sessions. Moving a session between projects changes only
 * {@code projectId}: never {@code sessionDir}, {@code user}, {@code workingDir}, env,
 * tasks, messages, or the on-disk location of the session — which is why moves are legal
 * even while a session is live.
 *
 * <h2>References</h2>
 * Public operations accept {@code idOrName}: exact id first, else case-insensitive
 * normalized name among all users; more than one name match is
 * {@link NaruProjectAmbiguousException} (candidate ids attached), none is
 * {@link NaruProjectNotFoundException}. Lower-level callers (stores) take ids only;
 * resolution is this service's job.
 *
 * <h2>Target of a move</h2>
 * A move target is a project {@code idOrName}, or standalone: pass null or the literal
 * {@value #NO_PROJECT}. Dropping a session onto the tree's "no project" node is exactly
 * {@code moveSession(id, NO_PROJECT)}.
 */
public interface NaruProjectService extends NaruAgentExtension {

    /** The {@link #name()} this service is installed under. */
    String EXTENSION = "project";

    /** The move-target literal meaning "no project" (case-insensitive); null works too. */
    String NO_PROJECT = "none";

    // ---------------------------------------------------------------- crud

    /**
     * Creates a project. {@code folder} is required — there is no implicit default — and
     * an absent folder fails validation. A folder that does not exist on disk succeeds
     * with a warning (it may be unmounted); the warning travels in the result.
     *
     * @throws NaruProjectNameConflictException when {@code (user, name)} is taken
     */
    NaruProjectResult<NaruProject> add(String name, NPath folder, String user);

    /**
     * Applies a patch: unset fields stay exactly as stored, and only {@code user} accepts
     * an explicit clear. Name uniqueness is re-checked when {@code name} or {@code user}
     * changes. A new folder that does not exist is a warning, not an error.
     * Editing never touches existing sessions.
     *
     * @throws NaruProjectConflictException     when the version moved under the caller
     * @throws NaruProjectNameConflictException when the new pair is taken
     * @throws NaruProjectNotFoundException     when the reference resolves to nothing
     * @throws NaruProjectAmbiguousException    when the name is not unique
     */
    NaruProjectResult<NaruProject> edit(String idOrName, long expectedVersion, NaruProjectPatch patch);

    /**
     * Removes a project according to {@code policy}; see
     * {@link NaruProjectDeletePolicy} for the exact semantics of each. The caller is
     * responsible for confirming a {@code CASCADE}; this service does not prompt.
     *
     * @throws NaruProjectNotEmptyException when the policy is REJECT and sessions remain
     */
    NaruRemoveReport remove(String idOrName, long expectedVersion, NaruProjectDeletePolicy policy);

    /** Resolves an id-or-name reference, or empty when nothing matches. */
    NOptional<NaruProject> find(String idOrName);

    // ---------------------------------------------------------------- tree

    /**
     * One page of projects with their live session counts — the top level of the desktop
     * tree. Counts are computed from the session store's headers
     * ({@code countByProject()}), never stored on the project row. The synthetic
     * "no project" node is not a stored project and does not appear here: render it from
     * {@link #countStandaloneSessions()} plus
     * {@code sessionStore.findByQuery(... projectFilter none ...)}, and the
     * "unknown project" node from {@link #findOrphanedSessions(String, int)}.
     */
    NaruPage<NaruProjectNode> tree(NaruProjectQuery query);

    /** Sessions with no project ({@code projectId == null}) — the no-project node's count. */
    long countStandaloneSessions();

    /**
     * Sessions whose {@code projectId} names a project that no longer exists, each row
     * stamped {@code projectMissing = true}. These are tolerated reads, never errors: a
     * public session committed to git may reference a project that exists only on its
     * author's machine. Such sessions are listed under no project node and are not
     * counted as standalone.
     */
    NaruPage<NaruSessionSummary> findOrphanedSessions(String cursor, int limit);

    /** One page of the tree's second level: sessions of one project, or standalone ones. */
    NaruPage<NaruSessionSummary> sessions(NaruSessionQuery query);

    // ---------------------------------------------------------------- migration

    /**
     * Re-points one session at another project (or at standalone). Changes only
     * {@code projectId}; works for live, hibernated and dead sessions alike, and for a
     * live session goes through the session's own in-memory state so its next checkpoint
     * cannot lose the write. Moving a session into the project it already belongs to is a
     * successful no-op reported as unchanged. Optimistic concurrency: a version clash is
     * retried once, then reported as a failure for that session.
     *
     * @param targetProjectIdOrName a project reference, or null / {@value #NO_PROJECT}
     *                              to detach
     */
    NaruMoveReport moveSession(String sessionId, String targetProjectIdOrName);

    /**
     * Moves several sessions. Atomic per session, never across sessions: one failure is
     * recorded in the report and the others proceed.
     */
    NaruMoveReport moveSessions(List<String> sessionIds, String targetProjectIdOrName);

    // ---------------------------------------------------------------- notifications

    /**
     * Registers an in-process listener. Events fire after successful operations, on the
     * calling thread; listener exceptions are caught and logged, never propagated.
     */
    void addListener(NaruProjectListener listener);

    void removeListener(NaruProjectListener listener);
}
