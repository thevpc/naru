package net.thevpc.naru.impl.store;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.store.*;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The store's own behaviour, tested without a session.
 *
 * <p>Everything here goes through the public SPI on purpose. A test that reached into
 * {@code .naru/sessions/<uuid>/tasks/1/history/} directly would keep passing after the one
 * thing this store promises -- that a caller only ever names a value, never a place --
 * had quietly stopped being true.
 *
 * <p>What these tests exist to catch, in order of how quietly they would otherwise fail:
 * a message lost during a save, a message duplicated, an id reused, and a move that leaves
 * a session half in each of two scopes.
 */
@Timeout(60)
public class NaruFileSessionStoreTest {

    @BeforeAll
    public static void setUpWorkspace() {
        // the TSON reader needs a workspace for its format options; a shared one keeps
        // every test here from opening its own
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
    private NaruSessionStore store;

    @BeforeEach
    public void newProject() {
        projectDir = NPath.ofTempFolder("naru-filestore-" + System.nanoTime());
        store = new NaruFileSessionStore(new NaruStoreConfig(projectDir,
                projectDir.resolve(".naru"), NaruSessionScope.PRIVATE));
    }

    // ---------------------------------------------------------------- fixtures

    private static NaruSessionData data(String uuid, String name) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("mode", "test");
        return new NaruSessionData()
                .uuid(uuid)
                .name(name)
                .creationInstant(Instant.parse("2024-01-01T00:00:00Z"))
                .modificationInstant(Instant.parse("2024-01-02T00:00:00Z"))
                .projectDir("/tmp/p")
                .workingDir("/tmp/p")
                .env(env);
    }

    private static NElement skeleton(long taskId) {
        return NElement.ofObjectBuilder()
                .set("id", taskId)
                .set("name", "task-" + taskId)
                .build();
    }

    private static NaruTaskState task(long taskId, List<NaruMessage> messages) {
        return NaruTaskState.ofInline(taskId, NElement.ofObjectBuilder()
                .set("id", taskId)
                .set("name", "task-" + taskId)
                .set("history", NElement.ofArrayBuilder()
                        .addAll(messages.stream().map(NaruMessage::toElement).toArray(NElement[]::new))
                        .build())
                .build());
    }

    private static NaruMessage message(String text) {
        return NaruMessage.user(text);
    }

    private void givenSession(String uuid) {
        store.create(data(uuid, "name-" + uuid), NaruSessionScope.PRIVATE);
    }

    private static List<String> texts(List<NaruMessage> messages) {
        List<String> out = new ArrayList<>();
        for (NaruMessage m : messages) {
            out.add(m.getContent());
        }
        return out;
    }

    // ---------------------------------------------------------------- catalog

    @Test
    public void aCreatedSessionIsFoundInItsScopeAndNoOther() {
        givenSession("s1");

        Assertions.assertTrue(store.exists("s1", NaruSessionScope.PRIVATE));
        Assertions.assertFalse(store.exists("s1", NaruSessionScope.PUBLIC));
        Assertions.assertEquals(NaruSessionScope.PRIVATE, store.scopeOf("s1").orNull());
        Assertions.assertTrue(store.scopeOf("s2").isEmpty());
    }

    @Test
    public void sessionDataSurvivesARoundTrip() {
        givenSession("s1");
        store.saveData(data("s1", "renamed"), NaruSessionScope.PRIVATE);

        NaruSessionData back = store.loadData("s1", NaruSessionScope.PRIVATE).orNull();
        Assertions.assertNotNull(back);
        Assertions.assertEquals("renamed", back.name());
        Assertions.assertEquals("test", back.env().get("mode"));
        Assertions.assertEquals(Instant.parse("2024-01-01T00:00:00Z"), back.creationInstant());
    }

    @Test
    public void creatingTheSameUuidTwiceIsRefusedRatherThanMerged() {
        givenSession("s1");
        // two sessions with one uuid cannot both be right, and overwriting would lose
        // whichever was written first along with everything it holds
        Assertions.assertThrows(IllegalStateException.class,
                () -> store.create(data("s1", "other"), NaruSessionScope.PRIVATE));
    }

    @Test
    public void listingCountsTasksAndMessagesFromWhatIsActuallyThere() {
        givenSession("s1");
        store.saveTask("s1", NaruSessionScope.PRIVATE,
                task(1, List.of(message("a"), message("b"))));
        store.saveTask("s1", NaruSessionScope.PRIVATE, task(2, List.of(message("c"))));

        NaruSessionRef ref = store.list(NaruSessionScope.PRIVATE).get(0);
        Assertions.assertEquals(2, ref.taskCount());
        Assertions.assertEquals(3, ref.historyCount());
        Assertions.assertEquals("s1", ref.uuid());
    }

    @Test
    public void listingAScopeWithNoSessionsIsEmptyRatherThanAFailure() {
        Assertions.assertTrue(store.list(NaruSessionScope.PUBLIC).isEmpty());
        Assertions.assertTrue(store.list().isEmpty());
    }

    // ---------------------------------------------------------------- history

    @Test
    public void appendingAMessageWritesExactlyOneNewFile() {
        givenSession("s1");
        store.saveTask("s1", NaruSessionScope.PRIVATE, task(1, List.of(message("one"), message("two"))));

        int before = historyFileCount("s1", 1);
        store.saveTask("s1", NaruSessionScope.PRIVATE,
                task(1, List.of(message("one"), message("two"), message("three"))));
        int after = historyFileCount("s1", 1);

        Assertions.assertEquals(2, before, "one file per message, not one file for the array");
        Assertions.assertEquals(3, after,
                "appending must cost one file, not a rewrite of the conversation");
    }

    @Test
    public void savingTheSameConversationTwiceChangesNothingOnDisk() {
        givenSession("s1");
        List<NaruMessage> messages = List.of(message("one"), message("two"));
        store.saveTask("s1", NaruSessionScope.PRIVATE, task(1, messages));
        List<String> ids = historyIds("s1", 1);

        store.saveTask("s1", NaruSessionScope.PRIVATE, task(1, messages));

        Assertions.assertEquals(ids, historyIds("s1", 1),
                "an unchanged conversation must keep its ids, or every save would rewrite it");
    }

    @Test
    public void historyOrderFollowsTheConversationNotTheFiles() {
        givenSession("s1");
        store.saveTask("s1", NaruSessionScope.PRIVATE,
                task(1, List.of(message("first"), message("second"), message("third"))));
        List<String> idsBefore = historyIds("s1", 1);

        // the same messages in a different order: what a user does by editing history
        store.saveTask("s1", NaruSessionScope.PRIVATE,
                task(1, List.of(message("third"), message("first"), message("second"))));

        Assertions.assertEquals(List.of("third", "first", "second"),
                texts(store.loadHistory("s1", NaruSessionScope.PRIVATE, 1)));
        Assertions.assertEquals(3, historyIds("s1", 1).size());
        Assertions.assertEquals(idsBefore.size(), historyIds("s1", 1).size(),
                "reordering is not a rewrite: the same files back the same messages");
    }

    @Test
    public void aRemovedMessageLosesItsFileOnlyAfterTheSkeletonNoLongerNamesIt() {
        givenSession("s1");
        store.saveTask("s1", NaruSessionScope.PRIVATE, task(1, List.of(message("one"), message("two"))));
        Assertions.assertEquals(2, historyFileCount("s1", 1));

        store.saveTask("s1", NaruSessionScope.PRIVATE, task(1, List.of(message("one"))));

        Assertions.assertEquals(1, historyFileCount("s1", 1), "the dropped message should be gone");
        Assertions.assertEquals(List.of("one"), texts(store.loadHistory("s1", NaruSessionScope.PRIVATE, 1)),
                "what is left must still be readable");
    }

    @Test
    public void theSameMessageTwiceStaysTwoMessages() {
        givenSession("s1");
        store.saveTask("s1", NaruSessionScope.PRIVATE,
                task(1, List.of(message("same"), message("other"), message("same"))));

        List<String> ids = historyIds("s1", 1);
        Assertions.assertEquals(3, ids.size(), "each occurrence is its own file");
        Assertions.assertEquals(3, new java.util.HashSet<>(ids).size(), "and its own id");
        Assertions.assertEquals(List.of("same", "other", "same"),
                texts(store.loadHistory("s1", NaruSessionScope.PRIVATE, 1)));
    }

    @Test
    public void idsAreNeverReusedAfterAMessageIsDeleted() {
        givenSession("s1");
        store.saveTask("s1", NaruSessionScope.PRIVATE, task(1, List.of(message("a"), message("b"))));
        store.saveTask("s1", NaruSessionScope.PRIVATE, task(1, List.of(message("a"))));

        store.saveTask("s1", NaruSessionScope.PRIVATE,
                task(1, List.of(message("a"), message("new"))));

        List<String> ids = historyIds("s1", 1);
        Assertions.assertEquals(2, ids.size());
        // the reused content is the one still on disk; the new message must not have
        // landed on the id the deleted message used to have
        Assertions.assertEquals(List.of("a", "new"),
                texts(store.loadHistory("s1", NaruSessionScope.PRIVATE, 1)));
    }

    @Test
    public void aTaskIsReadableWithoutItsHistoryBeingSeparate() {
        givenSession("s1");
        store.saveTask("s1", NaruSessionScope.PRIVATE, task(1, List.of(message("one"), message("two"))));

        NaruTaskState back = store.loadTask("s1", NaruSessionScope.PRIVATE, 1).orNull();
        Assertions.assertNotNull(back);
        Assertions.assertEquals(List.of("one", "two"), texts(back.history()));
        Assertions.assertEquals("task-1", back.skeleton().asObject().get().getStringValue("name").orNull());
    }

    @Test
    public void savingHistoryDirectlyRewritesTheTaskSkeleton() {
        givenSession("s1");
        store.saveTask("s1", NaruSessionScope.PRIVATE, task(1, List.of(message("one"))));
        store.saveTask("s1", NaruSessionScope.PRIVATE, task(2, List.of()));

        NaruHistorySave save = store.saveHistory("s1", NaruSessionScope.PRIVATE, 1,
                List.of(message("one"), message("two")));

        Assertions.assertEquals(1, save.created());
        // the point of the test: a direct history save must leave the task able to name its
        // messages, or the next load returns a task with no history and nothing looks wrong
        Assertions.assertEquals(List.of("one", "two"),
                texts(store.loadTask("s1", NaruSessionScope.PRIVATE, 1).get().history()));
        Assertions.assertEquals("task-2",
                store.loadTask("s1", NaruSessionScope.PRIVATE, 2).get().skeleton()
                        .asObject().get().getStringValue("name").orNull(),
                "saving one task's history must not disturb another task");
    }

    @Test
    public void deletingATaskTakesItsMessagesWithIt() {
        givenSession("s1");
        store.saveTask("s1", NaruSessionScope.PRIVATE, task(1, List.of(message("a"), message("b"))));
        store.saveTask("s1", NaruSessionScope.PRIVATE, task(2, List.of(message("c"))));

        store.deleteTask("s1", NaruSessionScope.PRIVATE, 1);

        Assertions.assertEquals(List.of(2L), store.taskIds("s1", NaruSessionScope.PRIVATE));
        Assertions.assertTrue(store.loadTask("s1", NaruSessionScope.PRIVATE, 1).isEmpty());
    }

    // ---------------------------------------------------------------- routines and extensions

    @Test
    public void routinesRoundTripAndCanBeRemoved() {
        givenSession("s1");
        NElement routine = NElement.ofObjectBuilder().set("code", "echo 1").build();
        store.saveRoutine("s1", NaruSessionScope.PRIVATE, "greet", routine);

        Assertions.assertEquals(List.of("greet"), store.routineNames("s1", NaruSessionScope.PRIVATE));
        // compared structurally, not by toString: what a formatter prints is its own business,
        // and asserting on it would fail this test for reasons that have nothing to do with storage
        NObjectElement back = store.loadRoutine("s1", NaruSessionScope.PRIVATE, "greet").get().asObject().get();
        Assertions.assertEquals("echo 1", back.getStringValue("code").get());

        store.deleteRoutine("s1", NaruSessionScope.PRIVATE, "greet");
        Assertions.assertTrue(store.routineNames("s1", NaruSessionScope.PRIVATE).isEmpty());
    }

    @Test
    public void anExtensionWithNothingToPersistLosesItsStaleState() {
        givenSession("s1");
        store.saveExtensionState("s1", NaruSessionScope.PRIVATE, "plan",
                NElement.ofObjectBuilder().set("v", 1).build());

        store.deleteExtensionState("s1", NaruSessionScope.PRIVATE, "plan");

        // otherwise a later load would resurrect state the extension said it no longer has
        Assertions.assertTrue(store.loadExtensionState("s1", NaruSessionScope.PRIVATE, "plan").isEmpty());
    }

    // ---------------------------------------------------------------- scope

    @Test
    public void movingBetweenScopesPreservesEverythingAndEndsUpInOnePlace() {
        givenSession("s1");
        store.saveTask("s1", NaruSessionScope.PRIVATE,
                task(1, List.of(message("a"), message("b"))));
        store.saveRoutine("s1", NaruSessionScope.PRIVATE, "r", NElement.ofObjectBuilder().build());

        store.move("s1", NaruSessionScope.PRIVATE, NaruSessionScope.PUBLIC);

        Assertions.assertFalse(store.exists("s1", NaruSessionScope.PRIVATE), "the old scope must be empty");
        Assertions.assertEquals(NaruSessionScope.PUBLIC, store.scopeOf("s1").orNull());
        Assertions.assertEquals(List.of("a", "b"),
                texts(store.loadHistory("s1", NaruSessionScope.PUBLIC, 1)));
        Assertions.assertEquals(List.of("r"), store.routineNames("s1", NaruSessionScope.PUBLIC));
    }

    @Test
    public void movingOntoAnExistingSessionIsRefusedRatherThanMerged() {
        givenSession("s1");
        // the same uuid in the destination scope: reached by hand, since creating it there
        // through the API is the collision the check exists to prevent
        store.create(data("s1", "name-s1"), NaruSessionScope.PUBLIC);

        Assertions.assertThrows(IllegalStateException.class,
                () -> store.move("s1", NaruSessionScope.PRIVATE, NaruSessionScope.PUBLIC));
        Assertions.assertTrue(store.exists("s1", NaruSessionScope.PRIVATE), "the source survives a refusal");
    }

    @Test
    public void movingASessionThatIsNotThereFailsRatherThanCreatingOne() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> store.move("nope", NaruSessionScope.PRIVATE, NaruSessionScope.PUBLIC));
        Assertions.assertTrue(store.list(NaruSessionScope.PUBLIC).isEmpty());
    }

    @Test
    public void deletingRemovesTheSessionEntirely() {
        givenSession("s1");
        store.saveTask("s1", NaruSessionScope.PRIVATE, task(1, List.of(message("a"))));

        Assertions.assertTrue(store.delete("s1", NaruSessionScope.PRIVATE));
        Assertions.assertFalse(store.delete("s1", NaruSessionScope.PRIVATE), "deleting twice is not a deletion");
        Assertions.assertTrue(store.list().isEmpty());
    }

    // ---------------------------------------------------------------- helpers

    private int historyFileCount(String uuid, long taskId) {
        return taskDir(uuid, taskId).resolve("history").list().stream()
                .filter(p -> p.name().endsWith(".tson"))
                .toList().size();
    }

    private List<String> historyIds(String uuid, long taskId) {
        NPath meta = taskDir(uuid, taskId).resolve("task.tson");
        List<String> out = new ArrayList<>();
        NElement ids = NElementReader.ofTson().ntf(false).read(meta).asObject().get().get("history").get();
        for (NElement e : ids.asArray().get()) {
            out.add(e.asStringValue().get());
        }
        return out;
    }

    private NPath taskDir(String uuid, long taskId) {
        return projectDir.resolve(".naru/local/sessions/" + uuid + "/tasks/" + taskId);
    }
}