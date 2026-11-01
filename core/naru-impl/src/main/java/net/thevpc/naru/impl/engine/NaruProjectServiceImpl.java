package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.project.NaruProject;
import net.thevpc.naru.api.project.NaruProjectAmbiguousException;
import net.thevpc.naru.api.project.NaruProjectConflictException;
import net.thevpc.naru.api.project.NaruProjectDeletePolicy;
import net.thevpc.naru.api.project.NaruProjectDraft;
import net.thevpc.naru.api.project.NaruProjectListener;
import net.thevpc.naru.api.project.NaruProjectNotEmptyException;
import net.thevpc.naru.api.project.NaruProjectNotFoundException;
import net.thevpc.naru.api.project.NaruProjectNode;
import net.thevpc.naru.api.project.NaruProjectPatch;
import net.thevpc.naru.api.project.NaruProjectQuery;
import net.thevpc.naru.api.project.NaruProjectResult;
import net.thevpc.naru.api.project.NaruProjectService;
import net.thevpc.naru.api.project.NaruProjectStore;
import net.thevpc.naru.api.project.NaruRemoveReport;
import net.thevpc.naru.api.project.NaruMoveReport;
import net.thevpc.naru.api.store.NaruPage;
import net.thevpc.naru.api.store.NaruPageCursor;
import net.thevpc.naru.api.store.NaruProjectFilter;
import net.thevpc.naru.api.store.NaruProjectSessionCounts;
import net.thevpc.naru.api.store.NaruSessionConflictException;
import net.thevpc.naru.api.store.NaruSessionData;
import net.thevpc.naru.api.store.NaruSessionQuery;
import net.thevpc.naru.api.store.NaruSessionScope;
import net.thevpc.naru.api.store.NaruSessionStore;
import net.thevpc.naru.api.store.NaruSessionSummary;
import net.thevpc.naru.api.store.NaruUserFilter;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NIllegalStateException;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The only component allowed to touch both stores. The project store answers for the
 * project rows; the session store answers for the session headers; everything that
 * crosses the boundary — counts, the tree, orphan detection, moves — is composed here,
 * because neither store is permitted to know the other exists.
 *
 * <p>The session store handle is the workspace catalog's own handle, never a second one:
 * two handles on one folder are how the same session once listed twice and then failed
 * to delete.
 */
public class NaruProjectServiceImpl implements NaruProjectService {

    private final NaruWorkspaceImpl workspace;
    private final NaruSessionStore sessions;
    private volatile NaruProjectStore projects;
    private final List<NaruProjectListener> listeners = new CopyOnWriteArrayList<>();

    public NaruProjectServiceImpl(NaruWorkspaceImpl workspace, NaruSessionStore sessions) {
        this.workspace = workspace;
        this.sessions = sessions;
    }

    // ---------------------------------------------------------------- project store

    private NaruProjectStore projectStore() {
        NaruProjectStore current = projects;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (projects == null) {
                NPath dir = workspace.projectDirectory() != null
                        ? workspace.projectDirectory()
                        : workspace.defaultSessionDir();
                projects = net.thevpc.naru.impl.store.NaruProjectStores.open(
                        NaruProjectStoreConfigs.forWorkspace(dir, workspace.env()),
                        NaruProjectStoreConfigs.storeName(workspace.env()),
                        workspace.classLoader());
            }
            return projects;
        }
    }

    // ---------------------------------------------------------------- id-or-name resolution

    @Override
    public NOptional<NaruProject> find(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            return NOptional.ofEmpty();
        }
        String ref = idOrName.trim();
        NOptional<NaruProject> byId = projectStore().findById(ref);
        if (byId.isPresent()) {
            return byId;
        }
        // find is the non-throwing lookup: nothing named returns empty. An ambiguous name
        // is not "nothing found" -- it is several things -- and still reports the names.
        List<NaruProject> matches = findByName(ref);
        if (matches.isEmpty()) {
            return NOptional.ofEmpty();
        }
        if (matches.size() > 1) {
            throw ambiguous(ref, matches);
        }
        return NOptional.of(matches.get(0));
    }

    /** Exact id first, then a case-insensitive normalized name among all users. */
    private NaruProject resolve(String idOrName) {
        NOptional<NaruProject> found = find(idOrName);
        if (found.isPresent()) {
            return found.get();
        }
        throw new NaruProjectNotFoundException(idOrName);
    }

    private List<NaruProject> findByName(String ref) {
        String key = ref.toLowerCase(Locale.ROOT);
        List<NaruProject> matches = new ArrayList<>();
        String cursor = null;
        while (true) {
            NaruPage<NaruProject> page = projectStore().findByQuery(
                    new NaruProjectQuery(cursor, null, NaruUserFilter.any(), null, NaruProjectQuery.MAX_LIMIT));
            for (NaruProject p : page.items()) {
                if (p.name().toLowerCase(Locale.ROOT).equals(key)) {
                    matches.add(p);
                }
            }
            if (page.nextCursor() == null) {
                break;
            }
            cursor = page.nextCursor();
        }
        return matches;
    }

    private static NaruProjectAmbiguousException ambiguous(String ref, List<NaruProject> matches) {
        List<String> ids = new ArrayList<>();
        for (NaruProject p : matches) {
            ids.add(p.id() + (p.user() == null ? "" : "@" + p.user()));
        }
        return new NaruProjectAmbiguousException(ref, ids);
    }

    // ---------------------------------------------------------------- crud

    @Override
    public NaruProjectResult<NaruProject> add(String name, NPath folder, String user) {
        if (folder == null) {
            // there is no implicit default: a project must state the directory it points at
            throw new NIllegalArgumentException(NMsg.ofC("folder required: a project must name its directory"));
        }
        NaruProject created = projectStore().create(NaruProjectDraft.of(name, folder, user));
        List<String> warnings = warningsFor(created);
        fire(l -> l.onProjectCreated(created));
        return NaruProjectResult.of(created, warnings);
    }

    @Override
    public NaruProjectResult<NaruProject> edit(String idOrName, long expectedVersion, NaruProjectPatch patch) {
        NaruProject before = resolve(idOrName);
        NaruProject after = projectStore().update(before.id(), expectedVersion, patch);
        List<String> warnings = warningsFor(after);
        fire(l -> l.onProjectUpdated(before, after));
        return NaruProjectResult.of(after, warnings);
    }

    /** Folder-missing is a warning, never an error: an unmounted path is still a legal project. */
    private static List<String> warningsFor(NaruProject project) {
        NPath folder = project.folder();
        if (folder != null && !folder.isDirectory()) {
            return List.of("folder does not exist: " + folder);
        }
        return List.of();
    }

    @Override
    public NaruRemoveReport remove(String idOrName, long expectedVersion, NaruProjectDeletePolicy policy) {
        NaruProjectDeletePolicy usePolicy = policy == null ? NaruProjectDeletePolicy.REJECT : policy;
        NaruProject project = resolve(idOrName);
        List<NaruSessionSummary> members = collectMembers(project.id());
        switch (usePolicy) {
            case REJECT: {
                if (!members.isEmpty()) {
                    List<String> samples = new ArrayList<>();
                    for (int i = 0; i < Math.min(10, members.size()); i++) {
                        NaruSessionSummary s = members.get(i);
                        samples.add(s.name() == null ? s.uuid() : s.name() + "(" + s.uuid() + ")");
                    }
                    throw new NaruProjectNotEmptyException(project.id(), members.size(), samples);
                }
                projectStore().delete(project.id(), expectedVersion);
                fire(l -> l.onProjectRemoved(project));
                return new NaruRemoveReport(project.id(), usePolicy, true,
                        List.of(), List.of(), Map.of(), List.of());
            }
            case DETACH: {
                // deliberate order: sessions first, project last. A crash in between leaves
                // sessions pointing at a still-existing project (minus some members) or at a
                // deleted project -- both tolerated reads (3.3), neither a lost conversation.
                List<String> detached = new ArrayList<>();
                Map<String, String> failed = new LinkedHashMap<>();
                for (NaruSessionSummary s : members) {
                    try {
                        // moveOne fires SESSION_MOVED itself: do not fire it twice
                        moveOne(s.uuid(), null);
                        detached.add(s.uuid());
                    } catch (Exception e) {
                        failed.put(s.uuid(), String.valueOf(e.getMessage()));
                    }
                }
                projectStore().delete(project.id(), expectedVersion);
                fire(l -> l.onProjectRemoved(project));
                return new NaruRemoveReport(project.id(), usePolicy, true,
                        detached, List.of(), failed, List.of());
            }
            case CASCADE: {
                List<String> liveNames = new ArrayList<>();
                for (NaruSessionSummary s : members) {
                    NaruSession live = runningSession(s.uuid());
                    if (live != null) {
                        liveNames.add(s.name() == null ? s.uuid() : s.name() + "(" + s.uuid() + ")");
                    }
                }
                if (!liveNames.isEmpty()) {
                    // nothing is deleted when any member is live: a running session's state
                    // is not the store's to drop
                    throw new NIllegalStateException(NMsg.ofC(
                            "cannot cascade project %s: live session(s) %s", project.id(), liveNames));
                }
                List<String> deleted = new ArrayList<>();
                Map<String, String> failed = new LinkedHashMap<>();
                for (NaruSessionSummary s : members) {
                    NaruSessionScope scope = sessions.scopeOf(s.uuid()).orNull();
                    if (scope == null) {
                        failed.put(s.uuid(), "no stored row found for this session");
                        continue;
                    }
                    try {
                        sessions.delete(s.uuid(), scope);
                        deleted.add(s.uuid());
                    } catch (Exception e) {
                        failed.put(s.uuid(), String.valueOf(e.getMessage()));
                    }
                }
                boolean projectDeleted = failed.isEmpty();
                if (projectDeleted) {
                    projectStore().delete(project.id(), expectedVersion);
                    fire(l -> l.onProjectRemoved(project));
                }
                List<String> remaining = new ArrayList<>();
                for (NaruSessionSummary s : members) {
                    if (!deleted.contains(s.uuid())) {
                        remaining.add(s.uuid());
                    }
                }
                return new NaruRemoveReport(project.id(), usePolicy, projectDeleted,
                        List.of(), deleted, failed, remaining);
            }
            default:
                throw new NIllegalArgumentException(NMsg.ofC("unknown delete policy: %s", usePolicy));
        }
    }

    private List<NaruSessionSummary> collectMembers(String projectId) {
        List<NaruSessionSummary> out = new ArrayList<>();
        String cursor = null;
        while (true) {
            NaruPage<NaruSessionSummary> page = sessions.findByQuery(new NaruSessionQuery(
                    cursor, null, NaruProjectFilter.ofId(projectId), NaruUserFilter.any(),
                    NaruSessionQuery.MAX_LIMIT));
            out.addAll(page.items());
            if (page.nextCursor() == null) {
                break;
            }
            cursor = page.nextCursor();
        }
        // a session that was created under a project and has not been saved yet exists
        // only in memory -- and it is exactly the one a CASCADE must refuse to delete:
        // include running sessions whose projectId matches, whether or not they have a row
        for (NaruSession s : workspace.sessions()) {
            boolean known = false;
            for (NaruSessionSummary m : out) {
                if (m.uuid().equals(s.uuid())) {
                    known = true;
                    break;
                }
            }
            if (!known && projectId.equals(s.projectId())) {
                out.add(new NaruSessionSummary(s.uuid(), s.name(), null,
                        s.creationInstant(), s.modificationInstant(),
                        String.valueOf(s.sessionDir()), null, projectId, s.userName(), 0, false));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- tree

    @Override
    public NaruPage<NaruProjectNode> tree(NaruProjectQuery query) {
        NaruPage<NaruProject> page = projectStore().findByQuery(query);
        NaruProjectSessionCounts counts = sessions.countByProject();
        List<NaruProjectNode> nodes = new ArrayList<>();
        for (NaruProject p : page.items()) {
            nodes.add(new NaruProjectNode(p, counts.of(p.id())));
        }
        return new NaruPage<>(nodes, page.nextCursor());
    }

    @Override
    public long countStandaloneSessions() {
        return sessions.countByProject().noProject();
    }

    @Override
    public NaruPage<NaruSessionSummary> findOrphanedSessions(String cursor, int limit) {
        Set<String> known = allProjectIds();
        List<NaruSessionSummary> out = new ArrayList<>();
        String scan = cursor;
        String scanCursor = cursor;
        boolean exhausted = false;
        while (out.size() < limit) {
            NaruPage<NaruSessionSummary> page = sessions.findByQuery(new NaruSessionQuery(
                    scanCursor, null, NaruProjectFilter.any(), NaruUserFilter.any(),
                    NaruSessionQuery.MAX_LIMIT));
            for (NaruSessionSummary s : page.items()) {
                if (s.projectId() == null || known.contains(s.projectId())) {
                    continue;
                }
                out.add(s.withProjectMissing(true));
                scan = NaruPageCursor.encode("UPDATED_DESC",
                        NaruPageCursor.instantKey(s.modificationInstant()), s.uuid());
                if (out.size() >= limit) {
                    break;
                }
            }
            if (out.size() >= limit) {
                break;
            }
            if (page.nextCursor() == null) {
                exhausted = true;
                break;
            }
            scanCursor = page.nextCursor();
        }
        // the caller resumes from the last orphan emitted; "more may exist" is only known
        // to be false when the source traversal itself ran out
        String next = exhausted ? null : scan;
        return new NaruPage<>(out, next);
    }

    @Override
    public NaruPage<NaruSessionSummary> sessions(NaruSessionQuery query) {
        NaruPage<NaruSessionSummary> page = sessions.findByQuery(query);
        Set<String> known = allProjectIds();
        List<NaruSessionSummary> stamped = new ArrayList<>();
        for (NaruSessionSummary s : page.items()) {
            stamped.add(s.projectId() != null && !known.contains(s.projectId())
                    ? s.withProjectMissing(true) : s);
        }
        return new NaruPage<>(stamped, page.nextCursor());
    }

    private Set<String> allProjectIds() {
        Set<String> ids = new HashSet<>();
        String cursor = null;
        while (true) {
            NaruPage<NaruProject> page = projectStore().findByQuery(new NaruProjectQuery(
                    cursor, null, NaruUserFilter.any(), null, NaruProjectQuery.MAX_LIMIT));
            for (NaruProject p : page.items()) {
                ids.add(p.id());
            }
            if (page.nextCursor() == null) {
                break;
            }
            cursor = page.nextCursor();
        }
        return ids;
    }

    // ---------------------------------------------------------------- migration

    @Override
    public NaruMoveReport moveSession(String sessionId, String targetProjectIdOrName) {
        String targetId = resolveTarget(targetProjectIdOrName);
        Map<String, String> moved = new HashMap<>();
        List<String> unchanged = new ArrayList<>();
        Map<String, String> failed = new HashMap<>();
        try {
            String outcome = moveOne(sessionId, targetId);
            if (outcome == MOVED_MARK) {
                moved.put(sessionId, targetId);
            } else {
                unchanged.add(sessionId);
            }
        } catch (Exception e) {
            failed.put(sessionId, String.valueOf(e.getMessage()));
        }
        return new NaruMoveReport(moved, unchanged, failed);
    }

    @Override
    public NaruMoveReport moveSessions(List<String> sessionIds, String targetProjectIdOrName) {
        // the target resolves once: a target that does not exist fails the whole call,
        // which is the caller's error, not a per-session one
        String targetId = resolveTarget(targetProjectIdOrName);
        Map<String, String> moved = new HashMap<>();
        List<String> unchanged = new ArrayList<>();
        Map<String, String> failed = new HashMap<>();
        for (String sessionId : sessionIds) {
            try {
                String outcome = moveOne(sessionId, targetId);
                if (outcome == MOVED_MARK) {
                    moved.put(sessionId, targetId);
                } else {
                    unchanged.add(sessionId);
                }
            } catch (Exception e) {
                // atomic per session, never across them: one failure does not abort the rest
                failed.put(sessionId, String.valueOf(e.getMessage()));
            }
        }
        return new NaruMoveReport(moved, unchanged, failed);
    }

    private static final String MOVED_MARK = "moved";

    private String resolveTarget(String targetProjectIdOrName) {
        if (targetProjectIdOrName == null || targetProjectIdOrName.isBlank()
                || NO_PROJECT.equalsIgnoreCase(targetProjectIdOrName.trim())) {
            return null;
        }
        return resolve(targetProjectIdOrName).id();
    }

    /**
     * Moves one session, returning {@link #MOVED_MARK} when the project id changed and
     * null when it was already there (a successful no-op). Throws when the session or its
     * stored row cannot be found or the version race cannot be won.
     */
    private String moveOne(String sessionId, String targetProjectId) {
        final NaruSession live = runningSession(sessionId);
        final String uuid;
        if (live != null) {
            uuid = live.uuid();
        } else {
            uuid = sessions.findUuid(sessionId).orNull();
            if (uuid == null) {
                throw new NIllegalArgumentException(NMsg.ofC("session not found: %s", sessionId));
            }
        }
        if (live != null) {
            // a live session may rewrite its own session.tson at any checkpoint: the move
            // must go through its in-memory state and persist on the engine's own path,
            // never behind its back
            String from = live.projectId();
            if (Objects.equals(from, targetProjectId)) {
                return null;
            }
            live.projectId(targetProjectId);
            live.save();
            fire(l -> l.onSessionMoved(uuid, from, targetProjectId));
            return MOVED_MARK;
        }
        NaruSessionScope scope = sessions.scopeOf(uuid).orNull();
        if (scope == null) {
            throw new NIllegalArgumentException(NMsg.ofC("session not found: %s", sessionId));
        }
        NaruSessionData current = sessions.loadData(uuid, scope).orNull();
        if (current == null) {
            throw new NIllegalArgumentException(NMsg.ofC("session not found: %s", sessionId));
        }
        if (Objects.equals(current.projectId(), targetProjectId)) {
            return null;
        }
        String from = current.projectId();
        // optimistic concurrency: re-read the version on conflict and retry once, then fail
        for (int attempt = 0; ; attempt++) {
            long expected = current.version();
            try {
                sessions.reassignProject(uuid, scope, expected, targetProjectId);
                break;
            } catch (NaruSessionConflictException lost) {
                if (attempt >= 1) {
                    throw lost;
                }
                current = sessions.loadData(uuid, scope).orNull();
                if (current == null) {
                    throw lost;
                }
                if (Objects.equals(current.projectId(), targetProjectId)) {
                    return null; // somebody already moved it to the same place
                }
            }
        }
        fire(l -> l.onSessionMoved(uuid, from, targetProjectId));
        return MOVED_MARK;
    }

    private NaruSession runningSession(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        for (NaruSession s : workspace.sessions()) {
            if (s.uuid().equals(sessionId)) {
                return s;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- notifications

    @Override
    public void addListener(NaruProjectListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(NaruProjectListener listener) {
        listeners.remove(listener);
    }

    private interface ListenerBody {
        void accept(NaruProjectListener listener);
    }

    /**
     * Fires on the calling thread after a successful operation. A listener exception is
     * logged and swallowed: one broken listener can neither fail a committed write nor
     * starve the listeners behind it.
     */
    private void fire(ListenerBody body) {
        for (NaruProjectListener l : listeners) {
            try {
                body.accept(l);
            } catch (Exception e) {
                workspace.log(NaruLogMode.SCHEDULER,
                        NMsg.ofC("project listener failed: %s", e).asError());
            }
        }
    }
}
