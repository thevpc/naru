package net.thevpc.naru.api.store;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;

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

    NOptional<NaruSessionData> loadData(String uuid, NaruSessionScope scope);

    void saveData(NaruSessionData data, NaruSessionScope scope);

    // ---------------------------------------------------------------- tasks

    /**
     * Writes a task's skeleton and its history together.
     *
     * <p>Atomic with respect to {@link #loadTask}: a caller either sees the old task or
     * the new one, never a skeleton whose history ids point at messages that were not
     * written yet.
     */
    void saveTask(String uuid, NaruSessionScope scope, NaruTaskState task);

    NOptional<NaruTaskState> loadTask(String uuid, NaruSessionScope scope, long taskId);

    List<Long> taskIds(String uuid, NaruSessionScope scope);

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