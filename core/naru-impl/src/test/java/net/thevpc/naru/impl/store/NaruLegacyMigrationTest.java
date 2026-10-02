package net.thevpc.naru.impl.store;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.store.*;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

/**
 * Upgrading a project that already has sessions.
 *
 * <p>Two things make this the test worth writing. The first is that a migration runs on
 * every start, so it has to be a no-op the second time; the second is that a session the
 * user cared about is only worth having if every one of its messages survived. Counting
 * files is not enough -- a migration can produce a directory with the right shape and the
 * wrong contents -- so these tests read the sessions back through the same API the rest of
 * the program uses and compare the conversations.
 *
 * <p>Both old layouts are covered: a session as its own folder, and a session as one
 * {@code snapshot.tson} file holding everything including its tasks. The second is the one
 * most likely to be dropped, since its messages are inline rather than in files.
 */
@Timeout(60)
public class NaruLegacyMigrationTest {

    @BeforeAll
    public static void setUpWorkspace() {
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            try {
                NWorkspace ws = Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private NPath projectDir;
    private NaruFileSessionStore store;

    @BeforeEach
    public void newProject() {
        projectDir = NPath.ofTempFolder("naru-migrate-" + System.nanoTime());
        store = new NaruFileSessionStore(new NaruStoreConfig(projectDir,
                projectDir.resolve(".naru"), NaruSessionScope.PRIVATE));
    }

    // ---------------------------------------------------------------- fixtures

    private static NElement message(String role, String content) {
        return NElement.ofObjectBuilder()
                .set("role", role)
                .set("content", content)
                .build();
    }

    /**
     * A legacy session folder.
     *
     * <p>Shaped like what the old engine actually wrote: one {@code <id>.tson} per task,
     * with that task's messages inline in the file. There is no per-message history
     * directory -- that only exists in the current layout -- so a fixture written to match
     * the new one would test nothing.
     */
    private NPath givenLegacyFolder(String uuid) {
        NPath dir = projectDir.resolve(".naru/local/snapshot/" + uuid);
        NaruFileIo.mkdirs(dir.resolve("tasks"));
        write(dir.resolve("session.tson"), NElement.ofObjectBuilder()
                .set("uuid", uuid)
                .set("name", "legacy-" + uuid)
                .set("creationDate", "2024-01-01T00:00:00Z")
                .set("modificationDate", "2024-01-02T00:00:00Z")
                .build());
        write(dir.resolve("tasks/1.tson"), NElement.ofObjectBuilder()
                .set("id", 1L)
                .set("name", "task-1")
                .set("history", NElement.ofArrayBuilder()
                        .add(message("user", "hello"))
                        .add(message("assistant", "hi"))
                        .build())
                .build());
        return dir;
    }

    /**
     * A session saved by the old engine, in the place it always saved them.
     *
     * <p>Not a migration input: it is already in the current session directories. It is
     * here because its tasks hold their messages inline, and reading one of those as if the
     * ids referred to files would come back as an empty conversation.
     */
    private NPath givenLegacySavedSession(String uuid) {
        NPath dir = projectDir.resolve(".naru/local/sessions/" + uuid);
        NaruFileIo.mkdirs(dir.resolve("tasks"));
        write(dir.resolve("session.tson"), NElement.ofObjectBuilder()
                .set("uuid", uuid)
                .set("name", "saved-" + uuid)
                .set("creationDate", "2024-01-01T00:00:00Z")
                .set("modificationDate", "2024-01-02T00:00:00Z")
                .build());
        write(dir.resolve("tasks/1.tson"), NElement.ofObjectBuilder()
                .set("id", 1L)
                .set("name", "task-1")
                .set("history", NElement.ofArrayBuilder()
                        .add(message("user", "already saved"))
                        .add(message("assistant", "and answered"))
                        .build())
                .build());
        return dir;
    }

    /** A legacy single-file session, with its task messages inline in the metadata file. */
    private NPath givenLegacyLooseSnapshot(String uuid) {
        NPath file = projectDir.resolve(".naru/local/sessions/snapshot.tson");
        write(file, NElement.ofObjectBuilder()
                .set("uuid", uuid)
                .set("name", "loose")
                .set("creationDate", "2024-01-01T00:00:00Z")
                .set("modificationDate", "2024-01-02T00:00:00Z")
                .set("tasks", NElement.ofArrayBuilder()
                        .add(NElement.ofObjectBuilder()
                                .set("id", 1L)
                                .set("name", "task-1")
                                .set("history", NElement.ofArrayBuilder()
                                        .add(message("user", "inline question"))
                                        .add(message("assistant", "inline answer"))
                                        .build())
                                .build())
                        .build())
                .build());
        return file;
    }

    private void write(NPath file, NElement element) {
        try {
            NaruFileIo.mkdirs(file.parent());
            NaruFileIo.writeAtomic(file, NaruFileSessionStore.pretty(element));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> texts(List<NaruMessage> messages) {
        return messages.stream().map(NaruMessage::getContent).toList();
    }

    // ---------------------------------------------------------------- tests

    @Test
    public void aLegacySessionFolderBecomesAReadableSession() {
        givenLegacyFolder("legacy-1");

        NaruLegacyMigration.Result result = new NaruLegacyMigration(store).migrate();

        Assertions.assertEquals(List.of("legacy-1"), result.migrated());
        Assertions.assertEquals(List.of("legacy-1"), store.list(NaruSessionScope.PRIVATE).stream()
                .map(NaruSessionRef::uuid).toList());
        Assertions.assertEquals("legacy-legacy-1",
                store.loadData("legacy-1", NaruSessionScope.PRIVATE).get().name());
    }

    @Test
    public void everyMessageOfALegacySessionSurvives() {
        givenLegacyFolder("legacy-1");

        new NaruLegacyMigration(store).migrate();

        // the point of the migration: the conversation, not the directory shape
        Assertions.assertEquals(List.of("hello", "hi"),
                texts(store.loadHistory("legacy-1", NaruSessionScope.PRIVATE, 1)));
        Assertions.assertEquals(2,
                store.list(NaruSessionScope.PRIVATE).get(0).historyCount());
    }

    @Test
    public void migratingTwiceChangesNothingTheSecondTime() {
        givenLegacyFolder("legacy-1");
        NaruLegacyMigration.Result first = new NaruLegacyMigration(store).migrate();
        Assertions.assertEquals(List.of("legacy-1"), first.migrated());

        NaruLegacyMigration.Result second = new NaruLegacyMigration(store).migrate();

        Assertions.assertTrue(second.migrated().isEmpty(), "a second run must not migrate again");
        Assertions.assertFalse(second.changedAnything(),
                "a project already in the new layout should look untouched");
        Assertions.assertEquals(List.of("hello", "hi"),
                texts(store.loadHistory("legacy-1", NaruSessionScope.PRIVATE, 1)),
                "and the conversation must not have been touched on the way");
    }

    @Test
    public void theLegacyInputIsKeptRatherThanDeleted() {
        NPath legacy = givenLegacyFolder("legacy-1");

        new NaruLegacyMigration(store).migrate();

        // a migration that removed its input would be unrecoverable the moment it had a bug
        NPath parked = projectDir.resolve(".naru/local/sessions/" + NaruLegacyMigration.LEGACY_DIR
                + "/legacy-1");
        Assertions.assertFalse(NaruFileIo.exists(legacy), "the input is moved aside, not copied forever");
        Assertions.assertTrue(NaruFileIo.isDirectory(parked), "and kept where a user could find it");
        Assertions.assertTrue(NaruFileIo.isFile(parked.resolve("tasks/1.tson")),
                "still a readable session, not a husk");
        // the dotted name is the whole mechanism: it is what keeps the parked copy from
        // being offered as a session, so it is checked by listing rather than by reading
        // the directory name back
        Assertions.assertEquals(List.of("legacy-1"), store.list(NaruSessionScope.PRIVATE).stream()
                .map(NaruSessionRef::uuid).toList(),
                "parked under a dotted name so nothing lists it as a session");
    }

    @Test
    public void aLegacySingleFileSessionMigratesWithItsInlineMessages() {
        givenLegacyLooseSnapshot("loose-1");

        NaruLegacyMigration.Result result = new NaruLegacyMigration(store).migrate();

        Assertions.assertEquals(List.of("loose-1"), result.migrated());
        Assertions.assertTrue(result.problems().isEmpty(), () -> String.valueOf(result.problems()));
        Assertions.assertEquals(List.of("inline question", "inline answer"),
                texts(store.loadHistory("loose-1", NaruSessionScope.PRIVATE, 1)),
                "an inline task's messages must survive, not just its shell");
    }

    @Test
    public void bothLegacyLayoutsCanBeMigratedInOnePass() {
        givenLegacyFolder("legacy-1");
        givenLegacyLooseSnapshot("loose-1");

        NaruLegacyMigration.Result result = new NaruLegacyMigration(store).migrate();

        Assertions.assertEquals(List.of("legacy-1", "loose-1"), result.migrated());
        Assertions.assertEquals(2, store.list(NaruSessionScope.PRIVATE).size());
    }

    @Test
    public void anAlreadySavedSessionWithInlineMessagesIsUpgradedAndKeepsItsConversation() {
        givenLegacySavedSession("saved-1");

        // the shape every session saved before the split still has on disk. It is in the
        // right directory, so nothing about where it sits suggests it needs attention, and
        // its tasks are one file each holding their messages inline.
        Assertions.assertTrue(store.loadHistory("saved-1", NaruSessionScope.PRIVATE, 1).isEmpty(),
                "precondition: not yet in the current shape");

        NaruLegacyMigration.Result result = new NaruLegacyMigration(store).migrate();

        Assertions.assertEquals(List.of("saved-1"), result.migrated());
        Assertions.assertTrue(result.problems().isEmpty(), () -> String.valueOf(result.problems()));
        // the point: it reads as the conversation it was, not as a session with no tasks
        Assertions.assertEquals(List.of("already saved", "and answered"),
                texts(store.loadHistory("saved-1", NaruSessionScope.PRIVATE, 1)));
        Assertions.assertEquals(List.of(1L), store.taskIds("saved-1", NaruSessionScope.PRIVATE));
    }

    @Test
    public void savingAnAlreadySavedSessionRewritesItsHistoryIntoFiles() {
        givenLegacySavedSession("saved-1");
        new NaruLegacyMigration(store).migrate();
        List<NaruMessage> history = store.loadHistory("saved-1", NaruSessionScope.PRIVATE, 1);
        Assertions.assertEquals(2, history.size(), "precondition: the conversation loaded");

        store.saveTask("saved-1", NaruSessionScope.PRIVATE,
                NaruTaskState.ofInline(1, NElement.ofObjectBuilder()
                        .set("id", 1L).set("name", "task-1")
                        .set("history", NElement.ofArrayBuilder()
                                .addAll(history.stream().map(NaruMessage::toElement).toArray(NElement[]::new))
                                .build())
                        .build()));

        // the point: after one save the task is in the current shape, so a later reader has
        // only the one convention to understand
        NElement skeleton = NaruFileSessionStore.read(
                projectDir.resolve(".naru/local/sessions/saved-1/tasks/1/task.tson"));
        Assertions.assertEquals(2, skeleton.asObject().get().get("history").get().asArray().get().size());
        Assertions.assertEquals(List.of("already saved", "and answered"),
                texts(store.loadHistory("saved-1", NaruSessionScope.PRIVATE, 1)));
    }

    @Test
    public void aProjectWithNoLegacySessionsMigratesNothingAndSaysNothing() {
        NaruLegacyMigration.Result result = new NaruLegacyMigration(store).migrate();

        Assertions.assertTrue(result.migrated().isEmpty());
        Assertions.assertTrue(result.failed().isEmpty());
        Assertions.assertFalse(result.changedAnything());
    }

    @Test
    public void aMigratedSessionIsNotOfferedAsALegacySessionAfterwards() {
        givenLegacyFolder("legacy-1");
        new NaruLegacyMigration(store).migrate();

        // the parked copies must not come back as sessions on the next run, or every start
        // would migrate them again forever
        NaruLegacyMigration.Result second = new NaruLegacyMigration(store).migrate();
        Assertions.assertTrue(second.migrated().isEmpty());
        Assertions.assertEquals(1, store.list(NaruSessionScope.PRIVATE).size(),
                "one session, not one session plus its own parked copy");
    }
}