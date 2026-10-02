package net.thevpc.naru.impl.store;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.store.*;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.io.NPath;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Moves session state written by earlier layouts into the current one.
 *
 * <p>Three generations of layout have existed, and all three can be present in one project
 * because a project that was never cleaned up has all of them:
 *
 * <ol>
 *   <li>{@code .naru/local/snapshot/<uuid>/} -- the working snapshot, one per session, from
 *       before sessions were saved in place;</li>
 *   <li>{@code .naru/local/sessions/snapshot/} -- one project-wide snapshot, the older
 *       generation of the same idea;</li>
 *   <li>{@code .naru/local/sessions/snapshot.tson} -- a file, not a folder, and not what
 *       its name suggests. It is a whole session state for a uuid of its own, with tasks
 *       inline. Nothing reads it; it is simply a session the current code cannot see.</li>
 * </ol>
 *
 * <h2>Nothing is deleted</h2>
 * A migration that removed its input would be unrecoverable the moment it had a bug, and
 * "the user's session vanished on upgrade" is not a failure mode worth trading disk for.
 * So every input is <em>copied</em> into the new layout, read back, and only then moved
 * aside under {@code .legacy/}. Both copies exist afterwards. If the read-back disagrees
 * with the original, the input stays exactly where it was and the migration reports the
 * failure for that session instead of continuing past it.
 */
public class NaruLegacyMigration {

    /**
     * Where migrated-from inputs go. Named to be obvious in a directory listing, and dotted
     * so that {@code /session list} does not offer them as sessions.
     */
    public static final String LEGACY_DIR = ".legacy";

    private final NaruFileSessionStore store;

    public NaruLegacyMigration(NaruFileSessionStore store) {
        this.store = store;
    }

    /** What a migration did, per session, so the caller can report or refuse. */
    public static class Result {
        private final List<String> migrated = new ArrayList<>();
        private final List<String> alreadyCurrent = new ArrayList<>();
        private final List<String> failed = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();

        public List<String> migrated() {
            return migrated;
        }

        /** Sessions whose state was already in the current layout; nothing was touched. */
        public List<String> alreadyCurrent() {
            return alreadyCurrent;
        }

        public List<String> failed() {
            return failed;
        }

        public List<String> problems() {
            return problems;
        }

        public boolean changedAnything() {
            return !migrated.isEmpty();
        }

        @Override
        public String toString() {
            return "Migration{migrated=" + migrated + ", already=" + alreadyCurrent
                    + ", failed=" + failed + (problems.isEmpty() ? "" : ", problems=" + problems) + '}';
        }
    }

    /**
     * Runs the migration. Safe to call on every start: a project already in the current
     * layout is detected and left alone.
     *
     * <p>Three kinds of input, in the order they are handled:
     *
     * <ol>
     * <li>the working snapshot each session used to keep for itself,
     * <li>{@code snapshot.tson}, a whole session in one file, and
     * <li>sessions already sitting in {@code sessions/} or {@code local/sessions/} whose
     * tasks are still in the old shape.
     * </ol>
     *
     * <p>The third is the one that is easy to forget and expensive to get wrong. Those
     * sessions are already in the right place, so nothing about their location looks like
     * it needs attention -- but their tasks are {@code tasks/<id>.tson} files holding their
     * messages inline, where this store looks for {@code tasks/<id>/task.tson}. A reader
     * that only looked at locations would find them, list them, and then show every one of
     * them as a session with no tasks, which is what a corrupted session looks like and is
     * indistinguishable from one.
     */
    public Result migrate() {
        Result result = new Result();
        for (NPath legacy : legacySessionDirs()) {
            String uuid = legacy.name();
            try {
                migrateFolder(legacy, uuid, result);
            } catch (Exception e) {
                result.failed().add(uuid);
                result.problems().add(uuid + ": " + e.getMessage());
            }
        }
        migrateLooseSnapshotFile(result);
        upgradeSessionsInPlace(result);
        return result;
    }

    /**
     * Rewrites the tasks of sessions that are already in place but still use the old
     * one-file-per-task shape.
     *
     * <p>Each task is rewritten through the store rather than moved, so the messages are
     * re-serialized into {@code history/} files the same way a save would. The old file is
     * only removed once the new one reads back with the same messages.
     */
    private void upgradeSessionsInPlace(Result result) {
        for (NaruSessionScope scope : NaruSessionScope.values()) {
            for (NPath dir : NaruFileIo.children(store.rootOf(scope))) {
                if (!NaruFileIo.isDirectory(dir) || !NaruFileIo.isFile(dir.resolve("session.tson"))) {
                    continue;
                }
                try {
                    upgradeSessionInPlace(dir, scope, result);
                } catch (Exception e) {
                    result.failed().add(dir.name());
                    result.problems().add(dir.name() + " (task layout): " + e.getMessage());
                }
            }
        }
    }

    private void upgradeSessionInPlace(NPath sessionDir, NaruSessionScope scope, Result result) {
        NPath tasksDir = sessionDir.resolve("tasks");
        if (!NaruFileIo.isDirectory(tasksDir)) {
            return;
        }
        boolean upgradedAny = false;
        for (NPath taskFile : NaruFileIo.children(tasksDir)) {
            if (!NaruFileIo.isFile(taskFile) || !taskFile.name().endsWith(".tson")) {
                continue;
            }
            long id = parseTaskId(taskFile.name());
            if (id < 0) {
                continue;
            }
            NElement element = NaruFileSessionStore.read(taskFile);
            String uuid = NaruSessionData.of(readMeta(sessionDir)).uuid();
            List<NaruMessage> expected = NaruTaskState.ofInline(id, element).history();
            store.saveTask(uuid, scope, NaruTaskState.ofInline(id, element));
            // the old file is not deleted until the new task reads back with every message
            // it had; a rewrite that lost one would be silent and permanent
            List<NaruMessage> actual = store.loadHistory(uuid, scope, id);
            if (actual.size() != expected.size()) {
                throw new IllegalStateException("task " + id + " lost messages being rewritten");
            }
            NaruFileIo.deleteQuietly(taskFile);
            upgradedAny = true;
        }
        if (upgradedAny) {
            result.migrated().add(sessionDir.name());
        }
    }

    private NElement readMeta(NPath sessionDir) {
        try {
            return NaruContentAddressedStore.parse(NaruFileIo.readString(sessionDir.resolve("session.tson")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Session folders in the old locations.
     *
     * <p>A folder counts only if it holds a {@code session.tson}: that is what made it a
     * session, and requiring it keeps a stray directory from being migrated into a session
     * that never existed.
     */
    private List<NPath> legacySessionDirs() {
        List<NPath> out = new ArrayList<>();
        NPath perSession = store.legacyRoot();
        if (NaruFileIo.isDirectory(perSession)) {
            for (NPath d : NaruFileIo.children(perSession)) {
                if (NaruFileIo.isDirectory(d) && NaruFileIo.isFile(d.resolve("session.tson"))) {
                    out.add(d);
                }
            }
        }
        NPath projectWide = store.privateRoot().resolve("snapshot");
        if (NaruFileIo.isDirectory(projectWide) && NaruFileIo.isFile(projectWide.resolve("session.tson"))) {
            out.add(projectWide);
        }
        return out;
    }

    private void migrateFolder(NPath legacy, String uuid, Result result) {
        NPath target = store.privateRoot().resolve(uuid);
        if (NaruFileIo.isFile(target.resolve("session.tson"))) {
            result.alreadyCurrent().add(uuid);
            return;
        }
        NaruSessionData data = readData(legacy);
        String realUuid = data != null && data.uuid() != null ? data.uuid() : uuid;
        NPath destination = store.privateRoot().resolve(realUuid);
        if (NaruFileIo.isFile(destination.resolve("session.tson"))) {
            result.alreadyCurrent().add(realUuid);
            return;
        }
        writeSession(legacy, destination, realUuid);
        verify(destination, realUuid, legacy);
        park(legacy);
        result.migrated().add(realUuid);
    }

    /**
     * The loose {@code snapshot.tson} file.
     *
     * <p>It is a complete session state for a uuid of its own, not an index, so it is
     * migrated as the session it names. Treating it as an index -- folding it into whatever
     * session was current -- would have destroyed the only copy of a conversation.
     */
    private void migrateLooseSnapshotFile(Result result) {
        NPath file = store.privateRoot().resolve("snapshot.tson");
        if (!NaruFileIo.isFile(file)) {
            return;
        }
        NElement element;
        try {
            element = NaruContentAddressedStore.parse(NaruFileIo.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        NObjectElement o = element.asObject().get();
        String uuid = o.getStringValue("uuid").orNull();
        if (uuid == null || uuid.isBlank()) {
            result.problems().add("snapshot.tson: no uuid; left untouched");
            return;
        }
        NPath destination = store.privateRoot().resolve(uuid);
        if (NaruFileIo.isFile(destination.resolve("session.tson"))) {
            result.alreadyCurrent().add(uuid);
            return;
        }
        writeInlineSession(element, destination, uuid);
        verifyInline(destination, uuid, element);
        park(file);
        result.migrated().add(uuid);
    }

    private NaruSessionData readData(NPath legacy) {
        try {
            NElement e = NaruContentAddressedStore.parse(NaruFileIo.readString(legacy.resolve("session.tson")));
            return NaruSessionData.of(e);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Copies a legacy folder into the current layout.
     *
     * <p>Tasks come through {@link NaruTaskState#ofInline}, which takes the inline history
     * and turns it into per-message files -- the same conversion a live save does, so a
     * migrated session and a session saved today have the same shape and the same code
     * reads both.
     */
    private void writeSession(NPath legacy, NPath destination, String uuid) {
        NaruSessionData data = readData(legacy);
        if (data == null) {
            throw new IllegalStateException("cannot read " + legacy.resolve("session.tson"));
        }
        data.uuid(uuid);
        store.create(data, NaruSessionScope.PRIVATE);
        for (NPath taskFile : NaruFileIo.children(legacy.resolve("tasks"))) {
            if (!NaruFileIo.isFile(taskFile) || !taskFile.name().endsWith(".tson")) {
                continue;
            }
            long id = parseTaskId(taskFile.name());
            if (id < 0) {
                continue;
            }
            NElement taskElement = NaruFileSessionStore.read(taskFile);
            store.saveTask(uuid, NaruSessionScope.PRIVATE, NaruTaskState.ofInline(id, taskElement));
        }
        for (NPath routineFile : NaruFileIo.children(legacy.resolve("routines"))) {
            if (NaruFileIo.isFile(routineFile) && routineFile.name().endsWith(".tson")) {
                store.saveRoutine(uuid, NaruSessionScope.PRIVATE,
                        routineFile.name().substring(0, routineFile.name().length() - 5),
                        NaruFileSessionStore.read(routineFile));
            }
        }
        for (NPath extFile : NaruFileIo.children(legacy.resolve("ext"))) {
            if (NaruFileIo.isFile(extFile) && extFile.name().endsWith(".tson")) {
                store.saveExtensionState(uuid, NaruSessionScope.PRIVATE,
                        extFile.name().substring(0, extFile.name().length() - 5),
                        NaruFileSessionStore.read(extFile));
            }
        }
        copyAudit(legacy, destination);
    }

    /** A session written as one file, tasks inline. */
    private void writeInlineSession(NElement element, NPath destination, String uuid) {
        NaruSessionData data = NaruSessionData.of(element);
        data.uuid(uuid);
        store.create(data, NaruSessionScope.PRIVATE);
        NElement tasks = element.asObject().get().get("tasks").orNull();
        if (tasks != null && tasks.isAnyArray()) {
            for (NElement t : tasks.asArray().get()) {
                long id = t.asObject().get().getLongValue("id").orElse(-1L);
                if (id >= 0) {
                    store.saveTask(uuid, NaruSessionScope.PRIVATE, NaruTaskState.ofInline(id, t));
                }
            }
        }
    }

    private void copyAudit(NPath legacy, NPath destination) {
        // the old per-task audit files are copied verbatim into the new log: they are an
        // append-only record of provider calls, and re-serializing them would be editing
        // a record of what happened rather than moving it
        for (NPath taskAudit : NaruFileIo.children(legacy.resolve("audit"))) {
            if (!NaruFileIo.isFile(taskAudit)) {
                continue;
            }
            try {
                String content = NaruFileIo.readString(taskAudit);
                NPath log = destination.resolve("audit").resolve("records.log");
                NaruFileIo.mkdirs(destination.resolve("audit"));
                log.writeString(content, net.thevpc.nuts.io.NPathOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Reads the migrated session back and checks it against what was there.
     *
     * <p>This is the step that makes moving data at startup defensible. If the migrated
     * session does not load -- a task missing, a history that came back empty -- the input
     * is still where it was and the caller is told, rather than the project quietly
     * starting with an empty session.
     */
    private void verify(NPath destination, String uuid, NPath original) {
        requireLoads(uuid);
        for (NPath legacyTask : NaruFileIo.children(original.resolve("tasks"))) {
            if (!NaruFileIo.isFile(legacyTask) || !legacyTask.name().endsWith(".tson")) {
                continue;
            }
            long id = parseTaskId(legacyTask.name());
            if (id < 0) {
                continue;
            }
            NaruTaskState state = store.loadTask(uuid, NaruSessionScope.PRIVATE, id).orNull();
            if (state == null) {
                throw new IllegalStateException("migrated task " + id + " did not load back");
            }
            requireSameMessages(uuid, id, NaruTaskState.ofInline(id,
                    NaruContentAddressedStore.parse(NaruFileIo.readStringOrThrow(legacyTask))).history());
        }
    }

    /**
     * Verifies a session migrated from one inline document.
     *
     * <p>Separate from the folder case because the two inputs hold tasks differently: a
     * legacy folder has one file per task, while the loose {@code snapshot.tson} has all of
     * them in a single array. Checking the folder-shaped way would silently skip every task
     * in the loose file -- the exact sessions the migration exists to save.
     */
    private void verifyInline(NPath destination, String uuid, NElement original) {
        requireLoads(uuid);
        NElement tasks = original.asObject().get().get("tasks").orNull();
        if (tasks == null || !tasks.isListContainer()) {
            return;
        }
        for (NElement task : tasks.asListContainer().get().asArray().get()) {
            NElement id = task.asObject().get().get("id").orNull();
            if (id == null) {
                continue;
            }
            long taskId = id.asLongValue().orElse(-1L);
            if (taskId < 0) {
                continue;
            }
            NaruTaskState state = store.loadTask(uuid, NaruSessionScope.PRIVATE, taskId).orNull();
            if (state == null) {
                throw new IllegalStateException("migrated task " + taskId + " did not load back");
            }
            requireSameMessages(uuid, taskId, NaruTaskState.ofInline(taskId, task).history());
        }
    }

    private void requireLoads(String uuid) {
        if (store.loadData(uuid, NaruSessionScope.PRIVATE).isEmpty()) {
            throw new IllegalStateException("migrated session " + uuid + " did not load back");
        }
    }

    /**
     * Checks the messages of a migrated task, one by one.
     *
     * <p>Counting would be enough to catch a lost write and is much cheaper, but a
     * migration is the one place where silently losing a turn survives every later test --
     * the conversation still looks reasonable, just shorter. The cost is bounded by the
     * input, which has already been read once to migrate it.
     */
    private void requireSameMessages(String uuid, long taskId, List<NaruMessage> expected) {
        NaruTaskState state = store.loadTask(uuid, NaruSessionScope.PRIVATE, taskId).orNull();
        List<NaruMessage> actual = state == null ? List.of() : state.history();
        if (actual.size() != expected.size()) {
            throw new IllegalStateException("migrated task " + taskId + " came back with "
                    + actual.size() + " messages instead of " + expected.size());
        }
        for (int i = 0; i < expected.size(); i++) {
            // compared through the store's canonical form rather than toString(): what a
            // formatter prints is its own business, and a check that can fail because a
            // formatter changed its spacing would report a migration failure that did not
            // happen
            if (!NaruContentAddressedStore.compact(expected.get(i).toElement())
                    .equals(NaruContentAddressedStore.compact(actual.get(i).toElement()))) {
                throw new IllegalStateException("migrated task " + taskId
                        + " message " + i + " did not survive the migration");
            }
        }
    }

    private long parseTaskId(String name) {
        try {
            return Long.parseLong(name.substring(0, name.length() - 5));
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Moves the input aside rather than deleting it.
     *
     * <p>Kept under a dotted name so nothing lists it as a session, and so that a user who
     * wants their old data back can find it without having to know what this class did.
     */
    private void park(NPath legacy) {
        try {
            NPath parking = store.privateRoot().resolve(LEGACY_DIR);
            NaruFileIo.mkdirs(parking);
            NPath target = parking.resolve(legacy.name());
            int n = 1;
            while (NaruFileIo.exists(target)) {
                target = parking.resolve(legacy.name() + "." + n);
                n++;
            }
            NaruFileIo.moveTree(legacy, target);
        } catch (IOException e) {
            // failing to tidy up is not a reason to fail the migration; the input stays
            // where it is and the next run will try again
        }
    }
}