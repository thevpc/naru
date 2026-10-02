package net.thevpc.naru.impl.store;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.store.*;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The session store, on the filesystem.
 *
 * <p>Layout, and why each piece is shaped the way it is:
 *
 * <pre>
 * .naru/sessions/&lt;uuid&gt;/
 *     session.tson                        metadata, written once per save
 *     tasks/&lt;taskId&gt;/task.tson          skeleton + history ids
 *     tasks/&lt;taskId&gt;/history/&lt;n&gt;.tson  one file per message
 *     routines/&lt;name&gt;.tson
 *     ext/&lt;extension&gt;.tson
 *     .versions/                          version history
 *     audit/                              audit log
 * .naru/local/sessions/&lt;uuid&gt;/        the same tree, for private sessions
 * </pre>
 *
 * <p>The history is one file per message rather than an array in the task for a reason that
 * is easy to miss until you measure: the session is saved after every statement, so an
 * array-in-the-task rewrites the entire conversation once per statement, and a task on its
 * hundredth turn rewrites a hundred turns of history a hundred times. With one file per
 * message, appending costs one new file. The ids in {@code task.tson} are what makes the
 * order recoverable without one file holding the order.
 *
 * <p>Ids are decimal counters in a file named {@code next-id}, monotonic per task, never
 * reused. They are zero-padded to a fixed width so that lexical file order is numeric id
 * order, which is what makes the conversation readable straight off the filesystem. Monotonic matters: an id that came back around would let a stale reference --
 * a reader mid-read, a version manifest written before the message was deleted -- resolve
 * to a <em>different</em> message instead of to nothing. {@code next-id} is never decremented
 * or rolled back.
 */
public class NaruFileSessionStore implements NaruSessionStore {

    /**
     * Names of directories inside a session folder that are bookkeeping, not sessions.
     * Kept explicit rather than a dot-prefix filter so that a user's session can never be
     * hidden by a naming convention.
     */
    private static final Set<String> RESERVED_DIRS = Set.of(".versions", "audit");

    private final NaruStoreConfig config;
    private final NPath publicRoot;
    private final NPath privateRoot;
    private final NPath legacyRoot;

    public NaruFileSessionStore(NaruStoreConfig config) {
        this.config = config;
        this.publicRoot = config.storeDir().resolve("sessions");
        this.privateRoot = config.storeDir().resolve("local").resolve("sessions");
        this.legacyRoot = config.storeDir().resolve("local").resolve("snapshot");
    }

    public NaruStoreConfig config() {
        return config;
    }

    // ---------------------------------------------------------------- catalog

    NPath rootOf(NaruSessionScope scope) {
        return scope == NaruSessionScope.PUBLIC ? publicRoot : privateRoot;
    }

    /**
     * The directory a session's state lives in, if it lives anywhere.
     *
     * <p>Private is checked first, deliberately. A uuid exists in at most one scope, and
     * preferring private means an interrupted move -- where both exist -- resolves to the
     * state the user did not mean to share.
     */
    public NPath sessionDir(String uuid) {
        NPath p = privateRoot.resolve(uuid);
        if (NaruFileIo.isDirectory(p) && NaruFileIo.isFile(p.resolve("session.tson"))) {
            return p;
        }
        NPath q = publicRoot.resolve(uuid);
        if (NaruFileIo.isDirectory(q) && NaruFileIo.isFile(q.resolve("session.tson"))) {
            return q;
        }
        return null;
    }

    private NPath requireDir(String uuid, NaruSessionScope scope) {
        NPath dir = rootOf(scope).resolve(uuid);
        if (!NaruFileIo.isDirectory(dir)) {
            throw new IllegalArgumentException("no session " + uuid + " in scope " + scope);
        }
        return dir;
    }

    @Override
    public List<NaruSessionRef> list(NaruSessionScope scope) {
        List<NaruSessionRef> out = new ArrayList<>();
        for (NPath dir : NaruFileIo.children(rootOf(scope))) {
            NaruSessionRef ref = describe(dir, scope);
            if (ref != null) {
                out.add(ref);
            }
        }
        out.sort(Comparator
                .comparing((NaruSessionRef r) -> r.modificationInstant(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(r -> r.uuid()));
        return out;
    }

    @Override
    public List<NaruSessionRef> list() {
        List<NaruSessionRef> out = new ArrayList<>(list(NaruSessionScope.PRIVATE));
        out.addAll(list(NaruSessionScope.PUBLIC));
        out.sort(Comparator
                .comparing((NaruSessionRef r) -> r.modificationInstant(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(r -> r.uuid()));
        return out;
    }

    /**
     * Describes a session folder, or null if it is not one.
     *
     * <p>Counts tasks and messages from the filesystem rather than from any recorded
     * summary: a summary would have to be kept correct, and the filesystem is the thing
     * that is actually there. The cost is a directory listing per session, which is what a
     * listing is for.
     */
    private NaruSessionRef describe(NPath dir, NaruSessionScope scope) {
        if (!NaruFileIo.isDirectory(dir)) {
            return null;
        }
        String name = dir.name();
        if (RESERVED_DIRS.contains(name) || name.startsWith(".")) {
            return null;
        }
        NPath meta = dir.resolve("session.tson");
        if (!NaruFileIo.isFile(meta)) {
            return null;
        }
        try {
            NElement e = NaruContentAddressedStore.parse(NaruFileIo.readString(meta));
            NObjectElement o = e.asObject().get();
            int taskCount = 0;
            int messageCount = 0;
            for (NPath taskDir : NaruFileIo.children(dir.resolve("tasks"))) {
                if (NaruFileIo.isDirectory(taskDir)) {
                    taskCount++;
                    // only the message files: the history directory also holds next-id,
                    // which is a counter and not something anyone said
                    messageCount += sortedHistoryFiles(taskDir.resolve("history")).size();
                }
            }
            return new NaruSessionRef(
                    o.getStringValue("uuid").orElse(name),
                    o.getStringValue("name").orNull(),
                    scope,
                    o.getInstantValue("creationDate").orNull(),
                    o.getInstantValue("modificationDate").orNull(),
                    taskCount, messageCount);
        } catch (Exception e) {
            // one unreadable session must not make /session list unusable for the rest
            return null;
        }
    }

    @Override
    public NOptional<String> findUuid(String uuidOrNameOrIndex) {
        if (uuidOrNameOrIndex == null || uuidOrNameOrIndex.isBlank()) {
            return NOptional.ofEmpty();
        }
        String key = uuidOrNameOrIndex.trim();
        List<NaruSessionRef> all = list();
        for (NaruSessionRef r : all) {
            if (key.equals(r.uuid())) {
                return NOptional.of(r.uuid());
            }
        }
        for (NaruSessionRef r : all) {
            if (r.name() != null && r.name().equalsIgnoreCase(key)) {
                return NOptional.of(r.uuid());
            }
        }
        try {
            int index = Integer.parseInt(key);
            if (index >= 1 && index <= all.size()) {
                return NOptional.of(all.get(index - 1).uuid());
            }
        } catch (NumberFormatException ignored) {
            // not an index; nothing more to try
        }
        return NOptional.ofEmpty();
    }

    @Override
    public NOptional<NaruSessionScope> scopeOf(String uuid) {
        if (NaruFileIo.isFile(privateRoot.resolve(uuid).resolve("session.tson"))) {
            return NOptional.of(NaruSessionScope.PRIVATE);
        }
        if (NaruFileIo.isFile(publicRoot.resolve(uuid).resolve("session.tson"))) {
            return NOptional.of(NaruSessionScope.PUBLIC);
        }
        return NOptional.ofEmpty();
    }

    @Override
    public boolean exists(String uuid, NaruSessionScope scope) {
        return NaruFileIo.isFile(rootOf(scope).resolve(uuid).resolve("session.tson"));
    }

    // ---------------------------------------------------------------- metadata

    @Override
    public void create(NaruSessionData data, NaruSessionScope scope) {
        NPath dir = rootOf(scope).resolve(data.uuid());
        if (NaruFileIo.isFile(dir.resolve("session.tson"))) {
            throw new IllegalStateException("session already exists: " + data.uuid());
        }
        NaruFileIo.mkdirs(dir);
        writeSessionData(dir, data);
    }

    @Override
    public NOptional<NaruSessionData> loadData(String uuid, NaruSessionScope scope) {
        NPath meta = rootOf(scope).resolve(uuid).resolve("session.tson");
        if (!NaruFileIo.isFile(meta)) {
            return NOptional.ofEmpty();
        }
        return NOptional.of(NaruSessionData.of(read(meta)));
    }

    @Override
    public void saveData(NaruSessionData data, NaruSessionScope scope) {
        writeSessionData(requireDir(data.uuid(), scope), data);
    }

    private void writeSessionData(NPath dir, NaruSessionData data) {
        try {
            NaruFileIo.writeAtomic(dir.resolve("session.tson"), pretty(data.toElement()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- tasks

    private NPath taskDir(NPath sessionDir, long taskId) {
        return sessionDir.resolve("tasks").resolve(Long.toString(taskId));
    }

    @Override
    public void saveTask(String uuid, NaruSessionScope scope, NaruTaskState task) {
        NPath sessionDir = requireDir(uuid, scope);
        NPath dir = taskDir(sessionDir, task.id());
        NaruFileIo.mkdirs(dir);
        // messages first: the skeleton names them, and a skeleton must never name a message
        // that is not there yet
        HistoryWrite write = writeHistory(dir, task.history());
        commitHistory(dir, task.skeleton(), write);
    }

    private static NElement withHistoryIds(NElement skeleton, List<String> ids) {
        NObjectElementBuilder b = NObjectElementBuilder.of();
        for (NElement child : skeleton.asObject().get().children()) {
            if (child.isNamedPair()) {
                b.add(child.asPair().get());
            }
        }
        net.thevpc.nuts.elem.NArrayElementBuilder a = net.thevpc.nuts.elem.NArrayElementBuilder.of();
        for (String id : ids) {
            a.add(NElement.ofString(id));
        }
        b.set("history", a.build());
        return b.build();
    }

    @Override
    public NOptional<NaruTaskState> loadTask(String uuid, NaruSessionScope scope, long taskId) {
        NPath sessionDir = rootOf(scope).resolve(uuid);
        if (!NaruFileIo.isDirectory(sessionDir)) {
            return NOptional.ofEmpty();
        }
        NPath taskFile = taskDir(sessionDir, taskId).resolve("task.tson");
        if (!NaruFileIo.isFile(taskFile)) {
            return NOptional.ofEmpty();
        }
        NElement element = read(taskFile);
        return NOptional.of(NaruTaskState.ofStored(taskId, element,
                readHistory(taskDir(sessionDir, taskId), element.asObject().get())));
    }

    @Override
    public List<Long> taskIds(String uuid, NaruSessionScope scope) {
        List<Long> ids = new ArrayList<>();
        for (NPath dir : NaruFileIo.children(rootOf(scope).resolve(uuid).resolve("tasks"))) {
            if (NaruFileIo.isDirectory(dir)) {
                try {
                    ids.add(Long.parseLong(dir.name()));
                } catch (NumberFormatException ignored) {
                    // not a task id; a stray directory is not a task
                }
            }
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    @Override
    public void deleteTask(String uuid, NaruSessionScope scope, long taskId) {
        NPath dir = taskDir(rootOf(scope).resolve(uuid), taskId);
        try {
            NaruFileIo.deleteTreeIfExists(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- history

    /**
     * Reads a task's messages, in the order the skeleton gives.
     *
     * <p>The order is the skeleton's, not the filesystem's. A conversation can be reordered
     * without any file changing -- a message edited in place moves from last to first, say
     * -- and then the file names are no longer the conversation. The skeleton is the only
     * thing that knows the order.
     *
     * <p>Two shapes are accepted. A skeleton whose {@code history} holds ids names files in
     * {@code history/}, which is what this store writes. A skeleton whose {@code history}
     * holds the messages themselves is a task written by an older version, where every
     * message lived inside {@code task.tson}; those are read in place. Without that second
     * case every session saved before the split would load as an empty conversation, which
     * is indistinguishable from a task that never said anything.
     */
    private List<NaruMessage> readHistory(NPath taskDir, NObjectElement skeleton) {
        List<NaruMessage> out = new ArrayList<>();
        NElement ids = skeleton.get("history").orNull();
        if (ids == null || !ids.isListContainer()) {
            return out;
        }
        for (NElement entry : ids.asListContainer().get().asArray().get()) {
            String name = entry.asStringValue().orNull();
            if (name == null) {
                // an inline message from an older layout, not a reference to a file
                try {
                    out.add(NaruMessage.of(entry));
                } catch (Exception ignored) {
                    // a corrupt message is skipped, not faked: a placeholder would be
                    // indistinguishable from something the model actually said
                }
                continue;
            }
            NPath f = historyFile(taskDir.resolve("history"), name);
            if (!NaruFileIo.isFile(f)) {
                continue;
            }
            try {
                out.add(NaruMessage.of(read(f)));
            } catch (Exception ignored) {
                // same as above: skipping is honest, substituting is not
            }
        }
        return out;
    }

    /**
     * The ids a skeleton names, in order. Used to find files no longer referenced.
     */
    private static Set<String> referencedHistoryIds(NPath taskDir) {
        Set<String> out = new LinkedHashSet<>();
        NPath meta = taskDir.resolve("task.tson");
        if (!NaruFileIo.isFile(meta)) {
            return out;
        }
        try {
            NElement ids = NaruContentAddressedStore.parse(readText(meta))
                    .asObject().get().get("history").orNull();
            if (ids != null && ids.isListContainer()) {
                for (NElement id : ids.asListContainer().get().asArray().get()) {
                    id.asStringValue().ifPresent(out::add);
                }
            }
        } catch (Exception ignored) {
            // an unreadable skeleton means nothing can be said about what is referenced,
            // and deleting on that basis would lose the conversation
        }
        return out;
    }

    /**
     * The message files in a {@code history} directory, in id order.
     *
     * <p>Takes the history directory itself rather than the task directory: every caller
     * already has one in hand, and resolving {@code history} again from there would look
     * in {@code history/history}, quietly find nothing, and turn reuse and deletion into
     * no-ops that look like they work.
     */
    private static List<NPath> sortedHistoryFiles(NPath historyDir) {
        List<NPath> files = new ArrayList<>();
        for (NPath f : NaruFileIo.children(historyDir)) {
            if (NaruFileIo.isFile(f) && f.name().endsWith(".tson")) {
                files.add(f);
            }
        }
        files.sort(Comparator.comparing(NPath::name));
        return files;
    }

    /**
     * Writes messages as {@code <id>.tson}, reusing files whose content already matches.
     *
     * <p>Reuse is what keeps appending cheap, and matching is by content hash rather than
     * by name: the caller knows what the conversation is, not which files back it.
     *
     * <p>Nothing is deleted here. Files that are no longer part of the conversation are
     * returned instead, to be removed by {@link #commitHistory} once the skeleton naming
     * the survivors is safely on disk. Deleting first would mean a crash leaves a skeleton
     * pointing at messages that are already gone.
     *
     * <p>Ids are reused in order, so identical messages stay distinct: a conversation with
     * the same sentence twice keeps two files, in the two places it was said.
     */
    private HistoryWrite writeHistory(NPath taskDir, List<NaruMessage> messages) {
        NPath historyDir = taskDir.resolve("history");
        NaruFileIo.mkdirs(historyDir);
        Map<String, Deque<String>> byHash = indexExisting(historyDir);
        long nextId = nextId(historyDir);
        Set<String> written = new LinkedHashSet<>();
        List<String> ids = new ArrayList<>(messages.size());
        for (NaruMessage message : messages) {
            NElement element = message.toElement();
            String hash = NaruContentAddressedStore.hashOf(element);
            Deque<String> candidates = byHash.get(hash);
            String id;
            if (candidates != null && !candidates.isEmpty()) {
                id = candidates.poll();
            } else {
                id = idOf(nextId++);
                NaruFileIo.mkdirs(historyDir);
                try {
                    NaruFileIo.writeAtomic(historyFile(historyDir, id), pretty(element));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            written.add(id);
            ids.add(id);
        }
        try {
            NaruFileIo.writeAtomic(historyDir.resolve("next-id"), Long.toString(nextId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> stale = new ArrayList<>();
        for (NPath f : sortedHistoryFiles(historyDir)) {
            String id = f.name().substring(0, f.name().length() - 5);
            if (!written.contains(id)) {
                stale.add(id);
            }
        }
        return new HistoryWrite(ids, stale);
    }

    /**
     * Commits a skeleton naming {@code ids}, then removes the messages it does not name.
     *
     * <p>Skeleton first, deletion second, always. The reverse order would let a crash
     * between the two destroy part of the conversation; this order can only ever leave a
     * file nobody references, which {@link #writeHistory} collects on the next save.
     */
    private void commitHistory(NPath taskDir, NElement skeleton, HistoryWrite write) {
        try {
            NaruFileIo.writeAtomic(taskDir.resolve("task.tson"),
                    pretty(withHistoryIds(skeleton, write.ids())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String id : write.stale()) {
            NaruFileIo.deleteQuietly(historyFile(taskDir.resolve("history"), id));
        }
    }

    /** The ids and the leftovers of one history write. */
    private static final class HistoryWrite {
        private final List<String> ids;
        private final List<String> stale;

        HistoryWrite(List<String> ids, List<String> stale) {
            this.ids = ids;
            this.stale = stale;
        }

        List<String> ids() {
            return ids;
        }

        List<String> stale() {
            return stale;
        }
    }

    /**
     * The file name for an id.
     *
     * <p>Fixed width, so that a directory listing sorts into conversation order. A variable
     * width would sort "10" before "2" and quietly reorder a conversation for anyone
     * reading it by hand.
     */
    private static String idOf(long id) {
        return String.format("%016d", id);
    }

    private static NPath historyFile(NPath historyDir, String id) {
        return historyDir.resolve(id + ".tson");
    }

    private static Map<String, Deque<String>> indexExisting(NPath historyDir) {
        Map<String, Deque<String>> out = new LinkedHashMap<>();
        for (NPath f : sortedHistoryFiles(historyDir)) {
            try {
                String hash = NaruContentAddressedStore.hashOf(read(f));
                out.computeIfAbsent(hash, k -> new ArrayDeque<>()).add(f.name().substring(0, f.name().length() - 5));
            } catch (Exception ignored) {
                // an unreadable file simply is not a reuse candidate
            }
        }
        return out;
    }

    private static long nextId(NPath historyDir) {
        NPath f = historyDir.resolve("next-id");
        if (NaruFileIo.isFile(f)) {
            try {
                return Long.parseLong(NaruFileIo.readString(f).trim());
            } catch (Exception ignored) {
                // fall through: an unreadable counter must not reuse ids, so start high
                // enough that it cannot collide with what is already on disk
                return Long.MAX_VALUE / 2;
            }
        }
        long max = 0;
        for (NPath file : sortedHistoryFiles(historyDir)) {
            try {
                max = Math.max(max, Long.parseLong(file.name().substring(0, file.name().length() - 5)));
            } catch (NumberFormatException ignored) {
                // not a message file
            }
        }
        return max + 1;
    }

    @Override
    public NaruHistorySave saveHistory(String uuid, NaruSessionScope scope, long taskId,
                                        List<NaruMessage> messages) {
        NPath dir = taskDir(rootOf(scope).resolve(uuid), taskId);
        NaruFileIo.mkdirs(dir);
        Set<String> before = referencedHistoryIds(dir);
        // the skeleton is left holding no history of its own when a caller saves history
        // directly: this method is told the messages, and the rest of the task is not this
        // method's business to invent
        NElement skeleton = taskSkeleton(dir);
        HistoryWrite write = writeHistory(dir, messages);
        commitHistory(dir, skeleton, write);
        int created = 0;
        for (String id : write.ids()) {
            if (!before.contains(id)) {
                created++;
            }
        }
        int deleted = 0;
        for (String id : write.stale()) {
            if (before.contains(id)) {
                deleted++;
            }
        }
        return new NaruHistorySave(taskId, created, Math.max(0, messages.size() - created), deleted,
                write.ids());
    }

    @Override
    public List<NaruMessage> loadHistory(String uuid, NaruSessionScope scope, long taskId) {
        NPath dir = taskDir(rootOf(scope).resolve(uuid), taskId);
        NPath meta = dir.resolve("task.tson");
        if (!NaruFileIo.isFile(meta)) {
            return new ArrayList<>();
        }
        NElement element = NaruContentAddressedStore.parse(readText(meta));
        return readHistory(dir, element.asObject().get());
    }

    /**
     * A task's skeleton with its history list emptied.
     *
     * <p>Read from disk rather than invented, so saving history for a task that already
     * exists does not quietly drop the rest of it.
     */
    private NElement taskSkeleton(NPath taskDir) {
        NPath meta = taskDir.resolve("task.tson");
        if (!NaruFileIo.isFile(meta)) {
            return NObjectElementBuilder.of().build();
        }
        NObjectElementBuilder b = NObjectElementBuilder.of();
        for (NElement child : NaruContentAddressedStore.parse(readText(meta))
                .asObject().get().children()) {
            if (child.isNamedPair() && !"history".equals(child.asPair().get().key().asStringValue().orNull())) {
                b.add(child.asPair().get());
            }
        }
        return b.build();
    }

    // ---------------------------------------------------------------- routines

    @Override
    public List<String> routineNames(String uuid, NaruSessionScope scope) {
        List<String> names = new ArrayList<>();
        for (NPath f : NaruFileIo.children(rootOf(scope).resolve(uuid).resolve("routines"))) {
            if (NaruFileIo.isFile(f) && f.name().endsWith(".tson")) {
                names.add(f.name().substring(0, f.name().length() - 5));
            }
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    @Override
    public NOptional<NElement> loadRoutine(String uuid, NaruSessionScope scope, String name) {
        NPath f = rootOf(scope).resolve(uuid).resolve("routines").resolve(name + ".tson");
        return NaruFileIo.isFile(f) ? NOptional.of(read(f)) : NOptional.ofEmpty();
    }

    @Override
    public void saveRoutine(String uuid, NaruSessionScope scope, String name, NElement element) {
        NPath dir = rootOf(scope).resolve(uuid).resolve("routines");
        NaruFileIo.mkdirs(dir);
        try {
            NaruFileIo.writeAtomic(dir.resolve(name + ".tson"), pretty(element));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void deleteRoutine(String uuid, NaruSessionScope scope, String name) {
        try {
            NaruFileIo.deleteIfExists(rootOf(scope).resolve(uuid).resolve("routines").resolve(name + ".tson"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- extensions

    @Override
    public NOptional<NElement> loadExtensionState(String uuid, NaruSessionScope scope, String extension) {
        NPath f = rootOf(scope).resolve(uuid).resolve("ext").resolve(extension + ".tson");
        return NaruFileIo.isFile(f) ? NOptional.of(read(f)) : NOptional.ofEmpty();
    }

    @Override
    public void saveExtensionState(String uuid, NaruSessionScope scope, String extension, NElement element) {
        NPath dir = rootOf(scope).resolve(uuid).resolve("ext");
        NaruFileIo.mkdirs(dir);
        try {
            NaruFileIo.writeAtomic(dir.resolve(extension + ".tson"), pretty(element));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void deleteExtensionState(String uuid, NaruSessionScope scope, String extension) {
        try {
            NaruFileIo.deleteIfExists(rootOf(scope).resolve(uuid).resolve("ext").resolve(extension + ".tson"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void move(String uuid, NaruSessionScope from, NaruSessionScope to) {
        NPath src = rootOf(from).resolve(uuid);
        NPath dst = rootOf(to).resolve(uuid);
        if (!NaruFileIo.isDirectory(src)) {
            throw new IllegalArgumentException("no session " + uuid + " in scope " + from);
        }
        if (NaruFileIo.exists(dst)) {
            // refuse rather than merge: two sessions with one uuid cannot both be right,
            // and silently overwriting loses whichever was there
            throw new IllegalStateException("session " + uuid + " already exists in scope " + to);
        }
        try {
            // A false return means the move went across filesystems and was staged as a
            // copy: the destination is complete, but arrived by copy rather than by a
            // single rename. Nothing is half-written and nothing is left behind, so this
            // is a completed move.
            NaruFileIo.moveTree(src, dst);
        } catch (IOException e) {
            // moveTree deletes the source only after the destination rename has succeeded,
            // so any failure here leaves the session exactly where it was
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean delete(String uuid, NaruSessionScope scope) {
        NPath dir = rootOf(scope).resolve(uuid);
        if (!NaruFileIo.isDirectory(dir)) {
            return false;
        }
        try {
            NaruFileIo.deleteTreeIfExists(dir);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public int purge(NaruSessionScope scope) {
        int n = 0;
        for (NPath dir : NaruFileIo.children(rootOf(scope))) {
            if (describe(dir, scope) != null) {
                try {
                    NaruFileIo.deleteTreeIfExists(dir);
                    n++;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- history of state

    @Override
    public NaruSessionVersionStore versions(String uuid, NaruSessionScope scope) {
        return new NaruFileSessionVersionStore(sessionDirFor(uuid, scope), this);
    }

    @Override
    public NaruAuditStore audit(String uuid, NaruSessionScope scope) {
        return new NaruFileAuditStore(sessionDirFor(uuid, scope));
    }

    /**
     * The session directory, in whichever scope actually holds it.
     *
     * <p>Versions and audit are reachable from the session, so they are found by the
     * session rather than by a scope the caller may have guessed. Guessing wrong and
     * silently creating a second, empty version store next to the real one is the kind of
     * bug that only shows up as "my commits disappeared".
     */
    private NPath sessionDirFor(String uuid, NaruSessionScope scope) {
        NPath exact = rootOf(scope).resolve(uuid);
        if (NaruFileIo.isDirectory(exact)) {
            return exact;
        }
        NPath actual = sessionDir(uuid);
        if (actual == null) {
            throw new IllegalArgumentException("no session " + uuid);
        }
        return actual;
    }

    // ---------------------------------------------------------------- accessors for migration

    /** The private root, for migration code that has to recognise the old layout. */
    public NPath privateRoot() {
        return privateRoot;
    }

    public NPath publicRoot() {
        return publicRoot;
    }

    /** Where versions lived before they lived under the session: {@code .naru/local/snapshot/<uuid>}. */
    public NPath legacyRoot() {
        return legacyRoot;
    }

    @Override
    public void close() {
        // nothing pooled: every operation opens what it needs and closes it
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Serializes an element the way a human would want to read it.
     *
     * <p>Synchronized because the underlying TSON formatter shares a mutable cache of
     * format options across writers, and a session is written from whatever thread the
     * scheduler happens to be on. Two sessions persisting at once raced in that cache and
     * corrupted each other's element, which surfaced as a save failing with an exception
     * from deep inside the formatter -- with no session involved at fault.
     *
     * <p>Serializing is cheap next to what it describes, so the lock costs nothing worth
     * measuring.
     */
    static synchronized String pretty(NElement element) {
        return net.thevpc.nuts.elem.NElementWriter.ofTson().ntf(false)
                .formatter(net.thevpc.nuts.elem.NElementFormatterStyle.PRETTY)
                .formatPlain(element);
    }

    /**
     * Reads a file, turning an I/O failure into an unchecked one.
     *
     * <p>The store's own methods do not declare {@link IOException}, because every caller
     * of them is code that has no useful way to recover and would only forward the same
     * exception one layer up.
     */
    private static String readText(NPath file) {
        try {
            return NaruFileIo.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static NElement read(NPath file) {
        try {
            return NaruContentAddressedStore.parse(NaruFileIo.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}