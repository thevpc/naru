package net.thevpc.naru.api.store;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.List;

/**
 * The durable state of NARU sessions, as logical records.
 *
 * <p>Every method takes and returns values. There is no path in this interface, no file
 * name, no "write {@code session.tson}" -- which is the whole point. A caller that knows
 * how a message is laid out on disk has already lost the ability to swap the layout out,
 * and every other backend would have to be reverse-engineered back out of the engine.
 *
 * <h2>What a store must guarantee</h2>
 * <ul>
 *   <li><b>Atomicity per record.</b> A record is either wholly there or not there. A
 *       process killed mid-write must never leave a half-written task behind, because the
 *       session is expected to survive that.</li>
 *   <li><b>Durability on return.</b> When a write returns, it is readable. Nothing is left
 *       in a buffer "to be flushed later" across a call the caller believes finished.</li>
 *   <li><b>One writer.</b> A session is written through a single writer -- the agent's
 *       mailbox -- so a store may assume its own operations on one session do not overlap.
 *       It must still be safe against a second process, because a user can absolutely run
 *       two.</li>
 *   <li><b>Never lose an id.</b> Ids handed out for history items are never reused for
 *       different content, so a stale reference cannot resolve to the wrong message.</li>
 * </ul>
 *
 * <h2>What a store need not guarantee</h2>
 * <ul>
 *   <li>Ordering of the catalog. {@link #list} is sorted by modification time, newest
 *       first, by this interface's contract, so callers need not sort.</li>
 *   <li>Cheap {@link #find}. It may scan.</li>
 * </ul>
 */
public interface NaruSessionStore extends AutoCloseable {

    /**
     * Query one task's durable message records. Stores with separate message records should
     * override this method to read only the requested page; the fallback keeps older store
     * implementations source compatible.
     */
    default NaruMessagePage queryMessages(String uuid, NaruSessionScope scope, long taskId,
                                           NaruMessageQuery query) {
        NaruTaskState state = loadTask(uuid, scope, taskId).orNull();
        if (state == null) return new NaruMessagePage(List.of(), null, null, false);
        List<NaruStoredMessage> all = new java.util.ArrayList<>();
        for (int i = 0; i < state.historyIds().size() && i < state.history().size(); i++) {
            NaruMessage message = state.history().get(i);
            if (!query.roles().isEmpty() && !query.roles().contains(message.getRole())) continue;
            String id = state.historyIds().get(i);
            all.add(new NaruStoredMessage(id, sequence(id), message));
        }
        all.sort(java.util.Comparator.comparingLong(NaruStoredMessage::creationSequence));
        if (query.direction() == NaruMessageQueryDirection.BACKWARD) java.util.Collections.reverse(all);
        long cursor = query.cursor() == null ? Long.MIN_VALUE : sequence(query.cursor());
        List<NaruStoredMessage> selected = new java.util.ArrayList<>();
        for (NaruStoredMessage item : all) {
            if (query.cursor() != null && (query.direction() == NaruMessageQueryDirection.FORWARD
                    ? item.creationSequence() <= cursor : item.creationSequence() >= cursor)) continue;
            selected.add(item);
            if (selected.size() > query.limit()) break;
        }
        boolean more = selected.size() > query.limit();
        if (more) selected.remove(selected.size() - 1);
        String next = selected.isEmpty() ? null : selected.get(selected.size() - 1).id();
        String previous = selected.isEmpty() ? null : selected.get(0).id();
        return new NaruMessagePage(selected, next, previous, more);
    }

    /** Hierarchical view of this session's task records. Existing flat APIs remain supported. */
    default NaruTaskStore tasks(String uuid, NaruSessionScope scope) {
        NaruSessionStore owner = this;
        return new NaruTaskStore() {
            private final java.util.Map<Long, NaruMessageStore> messageStores = new java.util.HashMap<>();
            public List<Long> list() { return owner.taskIds(uuid, scope); }
            public NOptional<NaruTaskState> load(long id) { return owner.loadTask(uuid, scope, id); }
            public NOptional<NaruTaskState> loadSkeleton(long id) { return owner.loadTaskSkeleton(uuid, scope, id); }
            public void save(NaruTaskState task) { owner.saveTask(uuid, scope, task); }
            public void delete(long id) { owner.deleteTask(uuid, scope, id); }
            public synchronized NaruMessageStore messages(long taskId) {
                return messageStores.computeIfAbsent(taskId, id -> new NaruMessageStore() {
                    private NaruTaskState state() {
                        return owner.loadTask(uuid, scope, id).orNull();
                    }
                    private NaruStoredMessage wrap(String messageId, net.thevpc.naru.api.model.NaruMessage message) {
                        return new NaruStoredMessage(messageId, sequence(messageId), message);
                    }
                    public synchronized NaruStoredMessage append(net.thevpc.naru.api.model.NaruMessage message, String key) {
                        NaruTaskState s = state();
                        java.util.List<net.thevpc.naru.api.model.NaruMessage> history = s == null
                                ? new java.util.ArrayList<>() : new java.util.ArrayList<>(s.history());
                        history.add(message);
                        NaruHistorySave saved = owner.saveHistory(uuid, scope, id, history);
                        return wrap(saved.ids().get(saved.ids().size() - 1), message);
                    }
                    public synchronized NaruStoredMessage insertAfter(String messageId,
                            net.thevpc.naru.api.model.NaruMessage message, String key) {
                        NaruTaskState s = state();
                        if (s == null) throw new IllegalArgumentException("unknown task " + id);
                        java.util.List<net.thevpc.naru.api.model.NaruMessage> history = new java.util.ArrayList<>(s.history());
                        int index = s.historyIds().indexOf(messageId);
                        if (index < 0) throw new IllegalArgumentException("unknown message " + messageId);
                        history.add(index + 1, message);
                        NaruHistorySave saved = owner.saveHistory(uuid, scope, id, history);
                        return wrap(saved.ids().get(index + 1), message);
                    }
                    public synchronized void delete(java.util.List<String> ids) {
                        NaruTaskState s = state();
                        if (s == null) return;
                        java.util.List<net.thevpc.naru.api.model.NaruMessage> history = new java.util.ArrayList<>();
                        for (int i = 0; i < s.historyIds().size(); i++) {
                            if (!ids.contains(s.historyIds().get(i))) history.add(s.history().get(i));
                        }
                        owner.saveHistory(uuid, scope, id, history);
                    }
                    public synchronized java.util.List<NaruStoredMessage> get(java.util.List<String> ids) {
                        NaruTaskState s = state();
                        java.util.List<NaruStoredMessage> out = new java.util.ArrayList<>();
                        if (s != null) for (int i = 0; i < s.historyIds().size(); i++) {
                            if (ids.contains(s.historyIds().get(i))) out.add(wrap(s.historyIds().get(i), s.history().get(i)));
                        }
                        return out;
                    }
                    public synchronized NaruMessagePage query(NaruMessageQuery query) {
                        return owner.queryMessages(uuid, scope, id, query);
                    }
                });
            }
        };
    }

    /** Loads only the task skeleton when supported. */
    default NOptional<NaruTaskState> loadTaskSkeleton(String uuid, NaruSessionScope scope, long taskId) {
        return loadTask(uuid, scope, taskId);
    }

    private static long sequence(String id) {
        try { return Long.parseLong(id); } catch (RuntimeException ignored) { return id.hashCode() & 0x7fffffffL; }
    }

    private static long parseCursor(String cursor) {
        if (cursor == null) return -1;
        return sequence(cursor);
    }

    // ---------------------------------------------------------------- catalog

    /** Sessions in one scope, newest modification first. */
    List<NaruSessionRef> list(NaruSessionScope scope);

    /** Both scopes, newest first. */
    List<NaruSessionRef> list();

    /**
     * Resolves a uuid, a name, or a 1-based index into the listing, to a uuid. Empty when
     * nothing matches. Indexes are into {@link #list()} so that what a user counted in
     * {@code /session list} is what they get back.
     */
    NOptional<String> findUuid(String uuidOrNameOrIndex);

    /** Where a session's state is, read off the location rather than remembered. */
    NOptional<NaruSessionScope> scopeOf(String uuid);

    boolean exists(String uuid, NaruSessionScope scope);

    // ---------------------------------------------------------------- metadata

    void create(NaruSessionData data, NaruSessionScope scope);

    /** Atomically creates a session only when no record with this id exists. */
    default boolean createIfAbsent(NaruSessionData data, NaruSessionScope scope) {
        if (exists(data.uuid(), scope)) return false;
        create(data, scope);
        return true;
    }

    NOptional<NaruSessionData> loadData(String uuid, NaruSessionScope scope);

    void saveData(NaruSessionData data, NaruSessionScope scope);

    /** CAS metadata checkpoint. A successful call increments and returns the version. */
    default long compareAndSetData(NaruSessionData data, NaruSessionScope scope, long expectedVersion) {
        NaruSessionData current = loadData(data.uuid(), scope).orNull();
        long actual = current == null ? -1 : current.version();
        if (actual != expectedVersion) throw new NaruSessionConflictException(data.uuid(), expectedVersion, actual);
        data.version(expectedVersion + 1);
        saveData(data, scope);
        return data.version();
    }

    // ---------------------------------------------------------------- project-aware queries

    /**
     * One keyset page of session headers. The default implementation scans the catalog and
     * loads headers row by row — correct on every backend, lazy on none; file stores
     * should override with a single header pass.
     *
     * <p>Pagination is keyset over {@code (modificationInstant, uuid)}, stable under
     * concurrent inserts: no duplicates, and no row that existed for the whole traversal
     * is skipped. The cursor belongs to the sort that minted it.
     */
    default NaruPage<NaruSessionSummary> findByQuery(NaruSessionQuery query) {
        final NaruSessionQuery q = query == null ? NaruSessionQuery.first() : query;
        if (q.sort() != NaruSessionSort.UPDATED_DESC) {
            throw new NIllegalArgumentException(NMsg.ofC("unsupported session sort: %s", q.sort()));
        }
        List<NaruSessionSummary> rows = new ArrayList<>();
        for (NaruSessionRef ref : list()) {
            NaruSessionData data = loadData(ref.uuid(), ref.scope()).orNull();
            if (data == null) continue;
            NaruSessionSummary s = NaruSessionSummary.of(data, ref.scope());
            if (!q.projectFilter().matches(s.projectId())) continue;
            if (!q.user().matches(s.user())) continue;
            rows.add(s);
        }
        rows.sort(java.util.Comparator.comparing(
                (NaruSessionSummary s) -> NaruPageCursor.instantKey(s.modificationInstant())).reversed()
                .thenComparing(NaruSessionSummary::uuid));
        String[] cursor = NaruPageCursor.decode(q.cursor(), NaruSessionSort.UPDATED_DESC.name());
        if (cursor != null) {
            rows.removeIf(s -> !NaruPageCursor.isAfter(NaruPageCursor.instantKey(s.modificationInstant()),
                    s.uuid(), cursor, true));
        }
        List<NaruSessionSummary> items = rows.size() <= q.limit()
                ? new ArrayList<>(rows) : new ArrayList<>(rows.subList(0, q.limit()));
        String next = rows.size() > q.limit() && !items.isEmpty()
                ? NaruPageCursor.encode(NaruSessionSort.UPDATED_DESC.name(),
                NaruPageCursor.instantKey(items.get(items.size() - 1).modificationInstant()),
                items.get(items.size() - 1).uuid())
                : null;
        return new NaruPage<>(items, next);
    }

    /**
     * How many sessions name each project, plus the standalone count. The default pages
     * through {@link #findByQuery}; stores are expected to answer this with one pass over
     * their session headers, because the tree calls it for every listing.
     */
    default NaruProjectSessionCounts countByProject() {
        java.util.Map<String, Long> counts = new java.util.HashMap<>();
        long none = 0;
        String cursor = null;
        while (true) {
            NaruPage<NaruSessionSummary> page = findByQuery(
                    new NaruSessionQuery(cursor, NaruSessionSort.UPDATED_DESC,
                            NaruProjectFilter.any(), NaruUserFilter.any(), NaruSessionQuery.MAX_LIMIT));
            for (NaruSessionSummary s : page.items()) {
                if (s.projectId() == null) none++;
                else counts.merge(s.projectId(), 1L, Long::sum);
            }
            if (page.nextCursor() == null) break;
            cursor = page.nextCursor();
        }
        return new NaruProjectSessionCounts(counts, none);
    }

    /**
     * Re-points one session at a project — a metadata change only: never the directory,
     * never the location, never any task, message, or environment content. The header is
     * rewritten under the store's own version check and the version token is bumped.
     *
     * @return the stored row after the move
     */
    default NaruSessionData reassignProject(String uuid, NaruSessionScope scope,
                                            long expectedVersion, String projectIdOrNull) {
        NaruSessionData current = loadData(uuid, scope).orElseThrow(
                () -> new NIllegalArgumentException(NMsg.ofC("no session %s in scope %s", uuid, scope)));
        current.projectId(projectIdOrNull);
        compareAndSetData(current, scope, expectedVersion);
        return current;
    }

    /** Same, resolving the scope from the location. */
    default NaruSessionData reassignProject(String uuid, long expectedVersion, String projectIdOrNull) {
        NaruSessionScope scope = scopeOf(uuid).orNull();
        if (scope == null) {
            throw new NIllegalArgumentException(NMsg.ofC("no session %s", uuid));
        }
        return reassignProject(uuid, scope, expectedVersion, projectIdOrNull);
    }

    /** The ids of the sessions of one project (null for the standalone ones). */
    default List<String> findSessionIdsByProject(String projectIdOrNull) {
        List<String> out = new ArrayList<>();
        String cursor = null;
        while (true) {
            NaruPage<NaruSessionSummary> page = findByQuery(
                    new NaruSessionQuery(cursor, NaruSessionSort.UPDATED_DESC,
                            projectIdOrNull == null ? NaruProjectFilter.none() : NaruProjectFilter.ofId(projectIdOrNull),
                            NaruUserFilter.any(), NaruSessionQuery.MAX_LIMIT));
            for (NaruSessionSummary s : page.items()) out.add(s.uuid());
            if (page.nextCursor() == null) break;
            cursor = page.nextCursor();
        }
        return out;
    }

    // ---------------------------------------------------------------- tasks

    /**
     * Writes a task's skeleton and its history together.
     *
     * <p>Atomic with respect to {@link #loadTask}: a caller either sees the old task or
     * the new one, never a skeleton whose history ids point at messages that were not
     * written yet.
     */
    void saveTask(String uuid, NaruSessionScope scope, NaruTaskState task);

    /** Updates task metadata while preserving the already committed history bodies. */
    default void saveTaskSkeleton(String uuid, NaruSessionScope scope, NaruTaskState task) {
        saveTask(uuid, scope, task);
    }

    /** Saves a task and returns its committed position-ordered message ids. */
    default List<String> saveTaskAndGetHistoryIds(String uuid, NaruSessionScope scope, NaruTaskState task) {
        saveTask(uuid, scope, task);
        NaruTaskState saved = loadTask(uuid, scope, task.id()).orNull();
        return saved == null ? List.of() : saved.historyIds();
    }

    NOptional<NaruTaskState> loadTask(String uuid, NaruSessionScope scope, long taskId);

    List<Long> taskIds(String uuid, NaruSessionScope scope);

    /** Task ids whose persisted skeleton marks them as conversations, including terminal tasks. */
    default List<Long> conversationTaskIds(String uuid, NaruSessionScope scope) {
        List<Long> out = new java.util.ArrayList<>();
        for (long id : taskIds(uuid, scope)) {
            NaruTaskState state = loadTaskSkeleton(uuid, scope, id).orNull();
            if (state != null && state.skeleton().asObject().get()
                    .getBooleanValue("conversation").orElse(false)) out.add(id);
        }
        return java.util.Collections.unmodifiableList(out);
    }

    void deleteTask(String uuid, NaruSessionScope scope, long taskId);

    // ---------------------------------------------------------------- history

    /**
     * Replaces a task's history with the given messages.
     *
     * <p>Reconciles against what is already stored, by content: messages that are already
     * there keep their file, so the common case -- appending one message to a long
     * conversation -- writes one file and rewrites the skeleton, not the conversation.
     *
     * <p>Duplicates are kept as duplicates. Two identical messages in a row are two
     * messages; deduplicating them would silently change what the model sees on the next
     * turn. Where two identical messages land side by side, each gets its own id.
     *
     * <p>An id is never reused for different content. Files the new list does not refer to
     * are deleted <em>after</em> the skeleton that stopped referring to them is committed,
     * so a crash in between leaves an unreferenced file rather than a dangling reference.
     */
    NaruHistorySave saveHistory(String uuid, NaruSessionScope scope, long taskId, List<NaruMessage> messages);

    List<NaruMessage> loadHistory(String uuid, NaruSessionScope scope, long taskId);

    // ---------------------------------------------------------------- routines

    List<String> routineNames(String uuid, NaruSessionScope scope);

    NOptional<NElement> loadRoutine(String uuid, NaruSessionScope scope, String name);

    void saveRoutine(String uuid, NaruSessionScope scope, String name, NElement element);

    void deleteRoutine(String uuid, NaruSessionScope scope, String name);

    // ---------------------------------------------------------------- extensions

    /**
     * Extension-owned state. Opaque to the core, which is what lets an extension persist
     * whatever shape it likes without a store change.
     */
    NOptional<NElement> loadExtensionState(String uuid, NaruSessionScope scope, String extension);

    void saveExtensionState(String uuid, NaruSessionScope scope, String extension, NElement element);

    void deleteExtensionState(String uuid, NaruSessionScope scope, String extension);

    // ---------------------------------------------------------------- lifecycle

    /**
     * Moves a session from one scope to the other, carrying everything under the session --
     * tasks, history, routines, extension state, versions and audit -- to the new location.
     *
     * <p>Atomic where the platform allows it. Must fail, and change nothing, if a session
     * already exists at the destination.
     */
    void move(String uuid, NaruSessionScope from, NaruSessionScope to);

    boolean delete(String uuid, NaruSessionScope scope);

    /** Removes every session in one scope. Returns how many. */
    int purge(NaruSessionScope scope);

    // ---------------------------------------------------------------- history of state

    /**
     * Versioned state for this session, in this scope.
     *
     * <p>Distinct from {@link #list} on purpose: what is on disk right now is not a version
     * until it has been committed, and a restore that quietly wrote into the live store
     * would make the two indistinguishable.
     */
    NaruSessionVersionStore versions(String uuid, NaruSessionScope scope);

    /** The audit log for this session. */
    NaruAuditStore audit(String uuid, NaruSessionScope scope);

    @Override
    void close();
}
