package net.thevpc.naru.impl.store;

import net.thevpc.naru.api.project.NaruProject;
import net.thevpc.naru.api.project.NaruProjectConflictException;
import net.thevpc.naru.api.project.NaruProjectDraft;
import net.thevpc.naru.api.project.NaruProjectNameConflictException;
import net.thevpc.naru.api.project.NaruProjectNotFoundException;
import net.thevpc.naru.api.project.NaruProjectPatch;
import net.thevpc.naru.api.project.NaruProjectQuery;
import net.thevpc.naru.api.project.NaruProjectSort;
import net.thevpc.naru.api.project.NaruProjectStore;
import net.thevpc.naru.api.store.NaruPage;
import net.thevpc.naru.api.store.NaruProjectStoreConfig;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NOptional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The project store on the filesystem: one {@code <id>.tson} per project under the store
 * root (default {@code .naru/local/projects} — private, because project rows carry
 * absolute machine-specific paths).
 *
 * <p>Writes are atomic in the session store's own sense: {@link NaruFileIo#writeAtomic}
 * writes a sibling temp file, fsyncs it, and renames it over the target, so a crash
 * between any two operations leaves either the whole old row or the whole new row, never
 * half of either.
 *
 * <h2>Concurrency (v1)</h2>
 * Read-modify-write on one project is serialized by an in-process lock keyed by the
 * project's file, and every version-checked write also re-checks what it must under the
 * global scan lock (creation, and any change that can affect the unique {@code (user,
 * name)} pair). Multi-process access to one store directory is <b>not</b> supported in
 * v1; the version token is the fence against logical staleness, the locks against
 * in-process interleaving.
 *
 * <h2>Corruption</h2>
 * A listing skips unreadable rows with a warning to stderr — one bad file must never
 * break the tree. {@code findById} on that same file throws a clear error naming the
 * file, because there the user asked for that exact row.
 *
 * <h2>Paging</h2>
 * Listing is a directory scan; sorting and keyset paging happen in memory over
 * {@code (sortKey, id)}. The cursor is an opaque base64 of the sort tag, the last row's
 * sort key, and its id; a cursor from a different sort is rejected with a clear error
 * rather than silently restarted.
 */
public class NaruFileProjectStore implements NaruProjectStore {

    /** Order: global scan lock first, per-file lock second, only when both are needed. */
    private static final Object GLOBAL_LOCK = new Object();
    private static final ConcurrentHashMap<String, Object> FILE_LOCKS = new ConcurrentHashMap<>();

    private final NaruProjectStoreConfig config;
    private final NPath root;

    public NaruFileProjectStore(NaruProjectStoreConfig config) {
        this.config = config;
        this.root = config.root();
    }

    @Override
    public NaruProjectStoreConfig config() {
        return config;
    }

    NPath fileOf(String id) {
        return root.resolve(id + ".tson");
    }

    private static Object lockFor(String fileKey) {
        return FILE_LOCKS.computeIfAbsent(fileKey, x -> new Object());
    }

    // ---------------------------------------------------------------- writes

    @Override
    public NaruProject create(NaruProjectDraft draft) {
        if (draft == null) {
            throw new NIllegalArgumentException(NMsg.ofC("project draft is required"));
        }
        String name = NaruProject.checkName(draft.name());
        NPath folder = NaruProject.checkFolder(draft.folder());
        String user = NaruProject.checkUser(draft.user());
        synchronized (GLOBAL_LOCK) {
            // the uniqueness scan and this row's write are one critical section: two
            // concurrent creations of the same (user, name) must not both pass the scan
            String nameKey = key(name);
            for (NaruProject other : scanAll()) {
                if (Objects.equals(other.user(), user) && key(other.name()).equals(nameKey)) {
                    throw new NaruProjectNameConflictException(user, name, other.id());
                }
            }
            String id = UUID.randomUUID().toString();
            Instant now = Instant.now();
            NaruProject project = NaruProject.of(id, name, folder, user, now, now, 1);
            synchronized (lockFor(fileOf(id).toString())) {
                writeRow(project);
            }
            return project;
        }
    }

    @Override
    public NaruProject update(String id, long expectedVersion, NaruProjectPatch patch) {
        if (patch == null) {
            throw new NIllegalArgumentException(NMsg.ofC("project patch is required"));
        }
        boolean touchesUniquePair = patch.name().isSet() || patch.user().isSet();
        NPath file = fileOf(id);
        Object lock = lockFor(file.toString());
        if (touchesUniquePair) {
            synchronized (GLOBAL_LOCK) {
                synchronized (lock) {
                    return updateLocked(id, expectedVersion, patch, file);
                }
            }
        }
        synchronized (lock) {
            return updateLocked(id, expectedVersion, patch, file);
        }
    }

    private NaruProject updateLocked(String id, long expectedVersion, NaruProjectPatch patch, NPath file) {
        NaruProject current = loadStrict(id, file);
        if (current.version() != expectedVersion) {
            throw new NaruProjectConflictException(id, expectedVersion, current.version());
        }
        String name = patch.name().isSet() ? NaruProject.checkName(patch.name().value()) : current.name();
        NPath folder = patch.folder().isSet() ? NaruProject.checkFolder(patch.folder().value()) : current.folder();
        String user = patch.user().isSet() ? NaruProject.checkUser(patch.user().value()) : current.user();
        if (!name.equals(current.name()) || !Objects.equals(user, current.user())) {
            NaruProject candidate = NaruProject.of(id, name, folder, user,
                    current.createdAt(), Instant.now(), expectedVersion + 1);
            // uniqueness is rechecked on name OR user change, excluding the row being updated
            for (NaruProject other : scanAll()) {
                if (other.id().equals(id)) {
                    continue;
                }
                if (Objects.equals(other.user(), candidate.user())
                        && key(other.name()).equals(key(candidate.name()))) {
                    throw new NaruProjectNameConflictException(candidate.user(), candidate.name(), other.id());
                }
            }
        }
        NaruProject updated = current.written(name, folder, user, Instant.now(), expectedVersion + 1);
        writeRow(updated);
        return updated;
    }

    @Override
    public void delete(String id, long expectedVersion) {
        NPath file = fileOf(id);
        synchronized (lockFor(file.toString())) {
            NaruProject current = loadStrict(id, file);
            if (current.version() != expectedVersion) {
                throw new NaruProjectConflictException(id, expectedVersion, current.version());
            }
            try {
                java.nio.file.Files.delete(NaruFileIo.path(file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void writeRow(NaruProject project) {
        NaruFileIo.mkdirs(root);
        try {
            NaruFileIo.writeAtomic(fileOf(project.id()), NaruFileSessionStore.pretty(project.toElement()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- reads

    @Override
    public NOptional<NaruProject> findById(String id) {
        if (id == null || id.isBlank()) {
            return NOptional.ofEmpty();
        }
        NPath file = fileOf(id);
        if (!NaruFileIo.isFile(file)) {
            return NOptional.ofEmpty();
        }
        return NOptional.of(loadStrict(id, file));
    }

    @Override
    public boolean exists(String id) {
        return id != null && !id.isBlank() && NaruFileIo.isFile(fileOf(id));
    }

    /**
     * Reads one row and refuses to pretend a damaged one is readable: the caller named
     * this id, so a clear error pointing at the file is the only honest answer.
     */
    private NaruProject loadStrict(String id, NPath file) {
        if (!NaruFileIo.isFile(file)) {
            throw new NaruProjectNotFoundException(id);
        }
        try {
            return NaruProject.of(NaruContentAddressedStore.parse(NaruFileIo.readString(file)));
        } catch (Exception e) {
            throw new NIllegalArgumentException(
                    NMsg.ofC("corrupt project file %s: %s", file, e.getMessage()));
        }
    }

    @Override
    public NaruPage<NaruProject> findByQuery(NaruProjectQuery query) {
        final NaruProjectQuery q = query == null ? NaruProjectQuery.first() : query;
        List<NaruProject> rows = new ArrayList<>();
        for (NaruProject p : scanAll()) {
            if (!q.user().matches(p.user())) {
                continue;
            }
            if (q.nameContains() != null && !key(p.name()).contains(q.nameContains().toLowerCase(Locale.ROOT))) {
                continue;
            }
            rows.add(p);
        }
        Comparator<NaruProject> order = comparator(q.sort());
        rows.sort(order);
        String[] cursor = decodeCursor(q.cursor(), q.sort());
        if (cursor != null) {
            String sortKey = cursor[1];
            String idKey = cursor[2];
            rows.removeIf(p -> !isAfter(p, q.sort(), sortKey, idKey));
        }
        List<NaruProject> items = rows.size() <= q.limit()
                ? new ArrayList<>(rows) : new ArrayList<>(rows.subList(0, q.limit()));
        String next = rows.size() > q.limit() && !items.isEmpty()
                ? encodeCursor(q.sort(), sortKeyOf(items.get(items.size() - 1), q.sort()),
                items.get(items.size() - 1).id())
                : null;
        return new NaruPage<>(items, next);
    }

    // ---------------------------------------------------------------- scan, order, cursors

    /** Every readable row; corrupt files are skipped with a warning so the listing survives them. */
    private List<NaruProject> scanAll() {
        List<NaruProject> out = new ArrayList<>();
        for (NPath file : NaruFileIo.children(root)) {
            String name = file.name();
            if (name == null || !name.endsWith(".tson") || name.startsWith(".")) {
                // dot-prefixed names are NaruFileIo's temp files; never rows
                continue;
            }
            try {
                NElement e = NaruContentAddressedStore.parse(NaruFileIo.readString(file));
                out.add(NaruProject.of(e));
            } catch (Exception e) {
                net.thevpc.nuts.io.NErr.println(NMsg.ofC(
                        "naru project store: skipping unreadable project file %s: %s",
                        file, String.valueOf(e.getMessage())));
            }
        }
        return out;
    }

    private static String key(String normalizedName) {
        return normalizedName.toLowerCase(Locale.ROOT);
    }

    private Comparator<NaruProject> comparator(NaruProjectSort sort) {
        switch (sort) {
            case NAME_ASC:
                return Comparator.comparing((NaruProject p) -> key(p.name()))
                        .thenComparing(NaruProject::id);
            case UPDATED_DESC:
            default:
                // newest first; the id breaks ties so the order is total, which is what
                // makes a keyset cursor exact
                return Comparator.comparing((NaruProject p) -> sortKeyOf(p, NaruProjectSort.UPDATED_DESC))
                        .reversed()
                        .thenComparing(NaruProject::id);
        }
    }

    private static String sortKeyOf(NaruProject p, NaruProjectSort sort) {
        switch (sort) {
            case NAME_ASC:
                return key(p.name());
            case UPDATED_DESC:
            default:
                Instant t = p.updatedAt();
                return String.format("%020d.%09d", t.getEpochSecond(), t.getNano());
        }
    }

    private static boolean isAfter(NaruProject p, NaruProjectSort sort, String cursorKey, String cursorId) {
        String key = sortKeyOf(p, sort);
        int c = key.compareTo(cursorKey);
        switch (sort) {
            case UPDATED_DESC:
                return c < 0 || (c == 0 && p.id().compareTo(cursorId) > 0);
            case NAME_ASC:
            default:
                return c > 0 || (c == 0 && p.id().compareTo(cursorId) > 0);
        }
    }

    private static String encodeCursor(NaruProjectSort sort, String sortKey, String id) {
        String raw = sort.name() + "\n" + sortKey + "\n" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** null for no cursor; otherwise [sortTag, sortKey, id]. A cursor from another sort is a clear error. */
    private static String[] decodeCursor(String cursor, NaruProjectSort expected) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String[] parts;
        try {
            parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", 3);
        } catch (IllegalArgumentException e) {
            throw new NIllegalArgumentException(NMsg.ofC("invalid project page cursor: %s", cursor));
        }
        if (parts.length != 3) {
            throw new NIllegalArgumentException(NMsg.ofC("invalid project page cursor: %s", cursor));
        }
        if (!parts[0].equals(expected.name())) {
            throw new NIllegalArgumentException(NMsg.ofC(
                    "this cursor was produced by a %s listing; it cannot page a %s listing — restart with a null cursor",
                    parts[0], expected.name()));
        }
        return parts;
    }

    @Override
    public void close() {
        // nothing to release: the root is opened per operation
    }
}
