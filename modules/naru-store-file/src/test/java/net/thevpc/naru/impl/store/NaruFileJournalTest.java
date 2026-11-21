package net.thevpc.naru.impl.store;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.store.NaruSessionData;
import net.thevpc.naru.api.store.NaruSessionScope;
import net.thevpc.naru.api.store.NaruSessionStore;
import net.thevpc.naru.api.store.NaruStoreConfig;
import net.thevpc.naru.api.store.NaruTaskState;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The write-ahead log replays what a crash kept off the disk.
 * <p>
 * A crash is played by damaging the files by hand and opening the folder again, as the next
 * process would: what the log holds must come back, what it does not must stay as it is.
 */
@Timeout(60)
public class NaruFileJournalTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static Path folder() throws Exception {
        return Files.createTempDirectory("naru-journal-");
    }

    private static String read(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    public void aWriteLostFromTheDiskComesBackFromItsRecord() throws Exception {
        Path dir = folder();
        Path file = dir.resolve("tasks/1/task.tson");
        NaruFileJournal journal = NaruFileJournal.open(dir);
        journal.write(file, "v1".getBytes(StandardCharsets.UTF_8));
        journal.write(file, "v2".getBytes(StandardCharsets.UTF_8));
        journal.sync();

        // the rename reached the disk, the data did not
        Files.write(file, new byte[0]);

        NaruFileJournal.open(dir);
        Assertions.assertEquals("v2", read(file), "the last record of a file wins");
        Assertions.assertEquals(0, Files.size(dir.resolve(NaruFileJournal.FILE_NAME)),
                "a replayed log has nothing left to give");
    }

    @Test
    public void aTornLastRecordIsIgnoredAndTheOnesBeforeItStillApply() throws Exception {
        Path dir = folder();
        Path kept = dir.resolve("session.tson");
        NaruFileJournal journal = NaruFileJournal.open(dir);
        journal.write(kept, "synced".getBytes(StandardCharsets.UTF_8));
        journal.sync();
        // a record cut short by the crash: a length that promises more than is there
        Files.write(dir.resolve(NaruFileJournal.FILE_NAME), new byte[]{0, 0, 1, 0, 1, 2, 3},
                StandardOpenOption.APPEND);
        Files.delete(kept);

        NaruFileJournal.open(dir);
        Assertions.assertEquals("synced", read(kept));
    }

    @Test
    public void aLoggedDeleteReplaysAsADelete() throws Exception {
        Path dir = folder();
        Path file = dir.resolve("routines/r.tson");
        NaruFileJournal journal = NaruFileJournal.open(dir);
        journal.write(file, "routine".getBytes(StandardCharsets.UTF_8));
        journal.delete(file);
        journal.sync();

        NaruFileJournal.open(dir);
        Assertions.assertFalse(Files.exists(file), "replaying the write must not resurrect a deleted file");
    }

    @Test
    public void aCheckpointEmptiesTheLog() throws Exception {
        Path dir = folder();
        NaruFileJournal journal = NaruFileJournal.open(dir);
        journal.write(dir.resolve("a.tson"), "a".getBytes(StandardCharsets.UTF_8));
        journal.checkpoint();
        Assertions.assertEquals(0, Files.size(dir.resolve(NaruFileJournal.FILE_NAME)));
        Assertions.assertFalse(journal.isDirty());
    }

    @Test
    public void theStoreRecoversALostMessageBeforeItIsRead() throws Exception {
        NPath project = NPath.ofTempFolder("naru-journal-store-" + System.nanoTime());
        NaruStoreConfig config = new NaruStoreConfig(project, project.resolve(".naru"), NaruSessionScope.PRIVATE);
        // a long interval: only the explicit sync below makes anything durable
        System.setProperty("naru.store.sync", "60s");
        String uuid = UUID.randomUUID().toString();
        try {
            NaruSessionStore store = new NaruFileSessionStore(config);
            store.create(new NaruSessionData().uuid(uuid).name("s"), NaruSessionScope.PRIVATE);
            NElement skeleton = NElement.ofObjectBuilder().set("id", 1L).build();
            List<String> ids = store.saveTaskAndGetHistoryIds(uuid, NaruSessionScope.PRIVATE,
                    NaruTaskState.of(1, skeleton, List.of(), List.of(NaruMessage.user("hello"))));
            store.appendHistory(uuid, NaruSessionScope.PRIVATE, 1, skeleton, ids,
                    List.of(NaruMessage.assistant("hi there")));
            store.sync(uuid, NaruSessionScope.PRIVATE);

            // the power goes: the last message file is there by name, empty
            Path history = NaruFileIo.path(project.resolve(".naru/local/sessions").resolve(uuid))
                    .resolve("tasks/1/history");
            Path last;
            try (Stream<Path> files = Files.list(history)) {
                last = files.filter(p -> p.getFileName().toString().endsWith(".tson")).sorted()
                        .reduce((a, b) -> b).orElseThrow();
            }
            Files.write(last, new byte[0]);

            // the next process opens the same store, knowing nothing of this one's logs
            NaruFileSessionStore.forgetOpenJournals();
            NaruSessionStore next = new NaruFileSessionStore(config);
            List<NaruMessage> back = next.loadHistory(uuid, NaruSessionScope.PRIVATE, 1);
            Assertions.assertEquals(List.of("hello", "hi there"),
                    back.stream().map(NaruMessage::getFilteredContent).toList());
        } finally {
            System.clearProperty("naru.store.sync");
        }
    }

    @Test
    public void theSyncPolicyParses() {
        Assertions.assertEquals(200, NaruFileSessionStore.parseSyncPolicy(null));
        Assertions.assertEquals(0, NaruFileSessionStore.parseSyncPolicy("always"));
        Assertions.assertEquals(50, NaruFileSessionStore.parseSyncPolicy("50ms"));
        Assertions.assertEquals(2000, NaruFileSessionStore.parseSyncPolicy("2s"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> NaruFileSessionStore.parseSyncPolicy("soon"));
    }
}
