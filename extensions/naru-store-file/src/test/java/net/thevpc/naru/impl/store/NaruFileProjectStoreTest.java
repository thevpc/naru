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
import net.thevpc.naru.api.store.NaruUserFilter;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NIllegalArgumentException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The project file store's promises, tested through its own SPI: version tokens, the
 * unique (user, name) pair with its normalization, patch semantics that keep "unset"
 * distinct from "clear", stable keyset paging over both sorts, writes that a crash cannot
 * half-finish, corrupt rows that skip out of listings but name themselves on direct
 * lookup, and one winner per version under concurrent updates.
 */
@Timeout(60)
public class NaruFileProjectStoreTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private NPath root;
    private NaruProjectStore store;

    @BeforeEach
    public void newRoot() {
        root = NPath.ofTempFolder("naru-project-store-" + System.nanoTime());
        store = new NaruFileProjectStore(new NaruProjectStoreConfig(root, root));
    }

    // ---------------------------------------------------------------- fixtures

    private NaruProject create(String name, String folder, String user) {
        return store.create(NaruProjectDraft.of(name, NPath.of(folder), user));
    }

    private NaruProject createSpaced(String name, String folder, String user) throws InterruptedException {
        Thread.sleep(2); // keeps updatedAt orderable across rows
        return create(name, folder, user);
    }

    // ---------------------------------------------------------------- round trip and versions

    @Test
    public void createFindUpdateDeleteRoundTrip() {
        NaruProject p = create("Alpha", "/tmp/alpha", "alice");
        Assertions.assertEquals(1, p.version());
        Assertions.assertEquals(p.createdAt(), p.updatedAt());
        Assertions.assertTrue(root.resolve(p.id() + ".tson").isFile(),
                "one file per project, named by id");

        NaruProject found = store.findById(p.id()).get();
        Assertions.assertEquals("Alpha", found.name());
        Assertions.assertEquals(NPath.of("/tmp/alpha"), found.folder());
        Assertions.assertEquals("alice", found.user());

        NaruProject renamed = store.update(p.id(), 1, NaruProjectPatch.of().name("Beta"));
        Assertions.assertEquals("Beta", renamed.name());
        Assertions.assertEquals(2, renamed.version());
        Assertions.assertFalse(renamed.updatedAt().equals(p.updatedAt()));
        Assertions.assertEquals(renamed.folder(), found.folder(), "unset fields stay exactly as stored");

        store.delete(p.id(), renamed.version());
        Assertions.assertTrue(store.findById(p.id()).isEmpty());
    }

    @Test
    public void staleVersionIsRefusedOnUpdateAndDelete() {
        NaruProject p = create("Alpha", "/tmp/a", null);
        store.update(p.id(), 1, NaruProjectPatch.of().name("Beta")); // now version 2
        NaruProjectConflictException conflict = Assertions.assertThrows(NaruProjectConflictException.class,
                () -> store.update(p.id(), 1, NaruProjectPatch.of().name("Gamma")));
        Assertions.assertEquals(2, conflict.actualVersion());
        // nothing changed
        Assertions.assertEquals("Beta", store.findById(p.id()).get().name());

        Assertions.assertThrows(NaruProjectConflictException.class,
                () -> store.delete(p.id(), 1));
        store.delete(p.id(), 2);
        Assertions.assertTrue(store.findById(p.id()).isEmpty());
    }

    @Test
    public void missingProjectIsNotFoundNotCrash() {
        Assertions.assertThrows(NaruProjectNotFoundException.class,
                () -> store.update("no-such-id", 1, NaruProjectPatch.of().name("x")));
        Assertions.assertThrows(NaruProjectNotFoundException.class,
                () -> store.delete("no-such-id", 1));
        Assertions.assertTrue(store.findById("no-such-id").isEmpty());
    }

    // ---------------------------------------------------------------- uniqueness

    @Test
    public void nameUniquenessIsCaseInsensitiveNfcAndPerUserNamespace() {
        create("café", "/tmp/a", "alice");
        // case-insensitive
        Assertions.assertThrows(NaruProjectNameConflictException.class,
                () -> create("CAFÉ", "/tmp/b", "alice"));
        // NFC: decomposed e+accent is the same name as precomposed
        Assertions.assertThrows(NaruProjectNameConflictException.class,
                () -> create("cafe\u0301", "/tmp/c", "alice"));
        // another user may reuse it
        NaruProject bob = create("café", "/tmp/d", "bob");
        Assertions.assertNotNull(bob.id());
        // the null-user namespace is its own: free of alice and of bob
        NaruProject none = create("café", "/tmp/e", null);
        Assertions.assertNotNull(none.id());
        Assertions.assertThrows(NaruProjectNameConflictException.class,
                () -> create("CAFÉ", "/tmp/f", null));
        // folders are not unique
        Assertions.assertNotNull(create("Other", "/tmp/a", "carol").id());
    }

    @Test
    public void uniquenessIsRecheckedWhenTheUserChanges() {
        create("Alpha", "/tmp/a", "alice");
        NaruProject b = create("Beta", "/tmp/b", "bob");
        // renaming b to Alpha for bob is fine...
        store.update(b.id(), b.version(), NaruProjectPatch.of().name("Alpha"));
        // ...but moving it into alice's namespace now clashes
        NaruProject renamed = store.findById(b.id()).get();
        Assertions.assertThrows(NaruProjectNameConflictException.class,
                () -> store.update(b.id(), renamed.version(), NaruProjectPatch.of().user("alice")));
        // and the row was not touched by the failed move
        Assertions.assertEquals("bob", store.findById(b.id()).get().user());
    }

    // ---------------------------------------------------------------- folder normalization

    @Test
    public void folderIsNormalizedToAbsoluteOnCreateAndOnEdit() {
        NaruProject p = create("A", "sub/../other", null);
        Assertions.assertTrue(p.folder().isAbsolute());
        Assertions.assertEquals(NPath.of("sub/../other").toAbsolute().normalize(), p.folder());
        NaruProject q = store.update(p.id(), p.version(),
                NaruProjectPatch.of().folder(NPath.of("./x/./y/..")));
        Assertions.assertEquals(NPath.of("./x/./y/..").toAbsolute().normalize(), q.folder());
        // a non-existing folder is still storable: existence is a warning, not an error
        NaruProject ghost = create("G", "/definitely/not/mounted/here", null);
        Assertions.assertEquals(NPath.of("/definitely/not/mounted/here"), ghost.folder());
    }

    // ---------------------------------------------------------------- patch semantics

    @Test
    public void patchDistinguishesUnsetFromExplicitClear() {
        NaruProject p = create("A", "/tmp/a", "alice");
        // unset user: stays
        NaruProject renamed = store.update(p.id(), p.version(), NaruProjectPatch.of().name("B"));
        Assertions.assertEquals("alice", renamed.user());
        // explicit clear
        NaruProject cleared = store.update(p.id(), renamed.version(), NaruProjectPatch.of().clearUser());
        Assertions.assertNull(cleared.user());
        // and the key is really absent from the file, not written as null
        Assertions.assertFalse(root.resolve(p.id() + ".tson").readString().contains("user"),
                "null user is omitted from the project file");
        // blank-to-null on set
        NaruProject blanked = store.update(p.id(), cleared.version(), NaruProjectPatch.of().user("   "));
        Assertions.assertNull(blanked.user());
    }

    @Test
    public void emptyPatchBumpsTheVersionWithoutChangingFields() {
        NaruProject p = create("A", "/tmp/a", "alice");
        NaruProject bumped = store.update(p.id(), p.version(), NaruProjectPatch.unchanged());
        Assertions.assertEquals(2, bumped.version());
        Assertions.assertEquals(p.name(), bumped.name());
        Assertions.assertEquals(p.folder(), bumped.folder());
        Assertions.assertEquals(p.user(), bumped.user());
    }

    // ---------------------------------------------------------------- pagination

    @Test
    public void pagesFollowBothSortsWithoutRepeatsOrSkips() throws InterruptedException {
        List<NaruProject> made = new ArrayList<>();
        for (String n : List.of("delta", "alpha", "echo", "bravo", "charlie")) {
            made.add(createSpaced(n, "/tmp/" + n, null));
        }
        // UPDATED_DESC: creation order reversed (each row's updatedAt is its creation time)
        List<String> expectedUpdated = new ArrayList<>();
        for (int i = made.size() - 1; i >= 0; i--) {
            expectedUpdated.add(made.get(i).id());
        }
        List<String> expectedName = made.stream()
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .map(NaruProject::id).toList();

        Assertions.assertEquals(expectedUpdated, collect(NaruProjectSort.UPDATED_DESC));
        Assertions.assertEquals(expectedName, collect(NaruProjectSort.NAME_ASC));

        // paging two at a time must visit exactly the full traversal, in order
        for (NaruProjectSort sort : NaruProjectSort.values()) {
            List<String> seen = new ArrayList<>();
            String cursor = null;
            while (true) {
                NaruPage<NaruProject> page = store.findByQuery(
                        new NaruProjectQuery(cursor, sort, NaruUserFilter.any(), null, 2));
                for (NaruProject p : page.items()) {
                    seen.add(p.id());
                }
                if (page.nextCursor() == null) {
                    break;
                }
                cursor = page.nextCursor();
            }
            Assertions.assertEquals(sort == NaruProjectSort.NAME_ASC ? expectedName : expectedUpdated, seen,
                    "paged traversal differs for " + sort);
        }
    }

    private List<String> collect(NaruProjectSort sort) {
        List<String> out = new ArrayList<>();
        String c = null;
        while (true) {
            NaruPage<NaruProject> page = store.findByQuery(
                    new NaruProjectQuery(c, sort, NaruUserFilter.any(), null, 2));
            for (NaruProject p : page.items()) {
                out.add(p.id());
            }
            if (page.nextCursor() == null) {
                return out;
            }
            c = page.nextCursor();
        }
    }

    @Test
    public void cursorIsStableUnderInsertsAndDeletesBetweenPages() throws InterruptedException {
        for (String n : List.of("one", "two", "three", "four")) {
            createSpaced(n, "/tmp/" + n, null);
        }
        NaruPage<NaruProject> first = store.findByQuery(
                new NaruProjectQuery(null, NaruProjectSort.UPDATED_DESC, NaruUserFilter.any(), null, 2));
        Assertions.assertEquals(2, first.items().size());
        // a newer project lands mid-traversal: it is newer than the cursor position, so
        // this pass may legitimately miss it -- but nothing that existed throughout the
        // traversal may repeat or vanish
        createSpaced("late", "/tmp/late", null);
        // an already-seen row is deleted: the next page must not repeat or resurrect it
        store.delete(first.items().get(0).id(), first.items().get(0).version());
        NaruPage<NaruProject> second = store.findByQuery(
                new NaruProjectQuery(first.nextCursor(), NaruProjectSort.UPDATED_DESC,
                        NaruUserFilter.any(), null, 2));
        Set<String> seen = new HashSet<>();
        for (NaruProject p : first.items()) {
            Assertions.assertTrue(seen.add(p.id()));
        }
        for (NaruProject p : second.items()) {
            Assertions.assertTrue(seen.add(p.id()), "row " + p.id() + " repeated across pages");
        }
    }

    @Test
    public void nameContainsAndUserFiltersApplyBeforePaging() throws InterruptedException {
        createSpaced("alpha", "/tmp/a", "alice");
        createSpaced("alphabet", "/tmp/b", "bob");
        createSpaced("beta", "/tmp/c", null);
        NaruPage<NaruProject> contains = store.findByQuery(
                new NaruProjectQuery(null, NaruProjectSort.UPDATED_DESC, NaruUserFilter.any(), "ALPH", 50));
        Assertions.assertEquals(2, contains.items().size());
        NaruPage<NaruProject> noUser = store.findByQuery(
                new NaruProjectQuery(null, NaruProjectSort.UPDATED_DESC, NaruUserFilter.none(), null, 50));
        Assertions.assertEquals(1, noUser.items().size());
        Assertions.assertEquals("beta", noUser.items().get(0).name());
        NaruPage<NaruProject> alice = store.findByQuery(
                new NaruProjectQuery(null, NaruProjectSort.UPDATED_DESC, NaruUserFilter.ofName("alice"), null, 50));
        Assertions.assertEquals(1, alice.items().size());
        Assertions.assertEquals("alpha", alice.items().get(0).name());
    }

    @Test
    public void aCursorFromAnotherSortIsRejectedWithAClearError() {
        create("a", "/tmp/a", null);
        create("b", "/tmp/b", null);
        NaruPage<NaruProject> page = store.findByQuery(
                new NaruProjectQuery(null, NaruProjectSort.NAME_ASC, NaruUserFilter.any(), null, 1));
        Assertions.assertNotNull(page.nextCursor());
        NIllegalArgumentException err = Assertions.assertThrows(NIllegalArgumentException.class,
                () -> store.findByQuery(new NaruProjectQuery(page.nextCursor(),
                        NaruProjectSort.UPDATED_DESC, NaruUserFilter.any(), null, 1)));
        Assertions.assertTrue(String.valueOf(err.getMessage()).contains("NAME_ASC"),
                "the error must name the sort the cursor came from: " + err.getMessage());
    }

    @Test
    public void limitIsClampedAndNonPositiveLimitIsAnError() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> NaruProjectQuery.first(0));
        NaruProjectQuery wide = new NaruProjectQuery(null, null, null, null, 10_000);
        Assertions.assertEquals(NaruProjectQuery.MAX_LIMIT, wide.limit());
    }

    // ---------------------------------------------------------------- atomicity and corruption

    @Test
    public void aWriteThatCannotFinishLeavesTheOriginalIntact() throws Exception {
        NaruProject p = create("A", "/tmp/a", null);
        NPath row = root.resolve(p.id() + ".tson");
        String before = Files.readString(NaruFileIo.path(row));
        // make the store directory unwritable: the temp file cannot even be created, so
        // the rename never runs. The original row must be exactly what it was.
        Set<PosixFilePermission> readOnly = new HashSet<>(
                Files.getPosixFilePermissions(NaruFileIo.path(root)));
        readOnly.remove(PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(NaruFileIo.path(root), readOnly);
        try {
            Assertions.assertThrows(RuntimeException.class,
                    () -> store.update(p.id(), p.version(), NaruProjectPatch.of().name("B")));
        } finally {
            Files.setPosixFilePermissions(NaruFileIo.path(root), Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
        String after = Files.readString(NaruFileIo.path(row));
        Assertions.assertEquals(before, after, "a failed write must not touch the original");
        Assertions.assertEquals("A", store.findById(p.id()).get().name());
    }

    @Test
    public void aCorruptFileIsSkippedInListingAndNamedOnFindById() {
        NaruProject good = create("good", "/tmp/good", null);
        root.resolve("broken-project.tson").writeString("{ this is not tson");
        root.resolve(".leftover.tmp-crash").writeString("junk");
        NaruPage<NaruProject> all = store.findByQuery(NaruProjectQuery.first());
        Assertions.assertEquals(1, all.items().size(), "the corrupt file must be skipped, not fatal");
        Assertions.assertEquals(good.id(), all.items().get(0).id());

        NIllegalArgumentException err = Assertions.assertThrows(NIllegalArgumentException.class,
                () -> store.findById("broken-project"));
        Assertions.assertTrue(String.valueOf(err.getMessage()).contains("broken-project.tson"),
                "findById on a corrupt row must name the file: " + err.getMessage());
    }

    @Test
    public void aSchemaValidButInvalidRowIsTreatedAsCorrupt() {
        create("good", "/tmp/good", null);
        root.resolve("bad-name.tson").writeString(
                "{id : \"bad-name\", name : \"bad\u0007name\", folder : \"/tmp/x\", "
                        + "createdAt : \"2024-01-01T00:00:00Z\", updatedAt : \"2024-01-01T00:00:00Z\", version : 1}");
        Assertions.assertEquals(1, store.findByQuery(NaruProjectQuery.first()).items().size());
        Assertions.assertThrows(NIllegalArgumentException.class, () -> store.findById("bad-name"));
    }

    // ---------------------------------------------------------------- concurrency

    @Test
    public void concurrentUpdatesToOneProjectHaveExactlyOneWinnerPerVersion() throws Exception {
        NaruProject p = create("A", "/tmp/a", null);
        int racers = 8;
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        AtomicReference<Throwable> surprise = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            int n = i;
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    store.update(p.id(), p.version(), NaruProjectPatch.of().name("winner-" + n));
                    won.incrementAndGet();
                } catch (NaruProjectConflictException e) {
                    conflicted.incrementAndGet();
                } catch (Throwable e) {
                    surprise.compareAndSet(null, e);
                }
            });
            threads.add(t);
            t.start();
        }
        ready.await();
        go.countDown();
        for (Thread t : threads) {
            t.join(30_000);
        }
        Assertions.assertNull(surprise.get(), String.valueOf(surprise.get()));
        Assertions.assertEquals(1, won.get(), "exactly one writer may win version 1");
        Assertions.assertEquals(racers - 1, conflicted.get());
        Assertions.assertEquals(2, store.findById(p.id()).get().version());
    }

    @Test
    public void concurrentCreatesOfTheSamePairHaveExactlyOneWinner() throws Exception {
        int racers = 6;
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        AtomicReference<Throwable> surprise = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    create("Race", "/tmp/race", "runner");
                    won.incrementAndGet();
                } catch (NaruProjectNameConflictException e) {
                    conflicted.incrementAndGet();
                } catch (Throwable e) {
                    surprise.compareAndSet(null, e);
                }
            });
            threads.add(t);
            t.start();
        }
        go.countDown();
        for (Thread t : threads) {
            t.join(30_000);
        }
        Assertions.assertNull(surprise.get(), String.valueOf(surprise.get()));
        Assertions.assertEquals(1, won.get());
        Assertions.assertEquals(racers - 1, conflicted.get());
    }
}
