package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.store.NaruSessionScope;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

/**
 * A session that was built with an id must come back as that session.
 * <p>
 * {@link net.thevpc.naru.api.agent.NaruSessionBuilder#id(String)} is how a host addresses the
 * same conversation across two processes, and it was honoured only on the lazy-handle path.
 * A plain {@code build()} ignored it and minted a random uuid, so a caller that then asked
 * the store for its own id found nothing: the desktop restored zero tasks, left the composer
 * disabled, and its next "new session" wrote yet another abandoned session beside the real
 * one. This pins the two halves of that bug -- the id is kept, and the task tree comes back.
 */
@Timeout(60)
public class NaruSessionIdentityRestoreTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NaruSession newSession(NPath projectDir, String id) {
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(projectDir);
        return agent.newSession()
                .directory(projectDir)
                .id(id)
                .scope(NaruSessionScope.PRIVATE)
                .interaction(new NaruStreamInteraction(o -> {
                }))
                .banner(false)
                .build();
    }

    /**
     * The id names the session even when nothing is stored under it yet, so the first save
     * lands where the caller said rather than under a random uuid.
     */
    @Test
    public void aBuiltSessionKeepsTheIdItWasGiven() {
        NPath projectDir = NPath.ofTempFolder("naru-id-" + System.nanoTime());
        NaruSession session = newSession(projectDir, "the-id-the-caller-chose");
        Assertions.assertEquals("the-id-the-caller-chose", session.uuid(),
                "build() must not replace a caller-supplied id with a random uuid");
    }

    /**
     * A stored session is hydrated by {@code build()} itself: the task tree is present before
     * {@code start()}, so a host never sees an empty session it must repair.
     */
    @Test
    public void aStoredSessionIsHydratedByBuildAndKeepsItsTask() {
        NPath projectDir = NPath.ofTempFolder("naru-restore-" + System.nanoTime());
        String uuid = "55555555-5555-5555-5555-555555555555";

        // First process: create an interactive session and persist it while it is parked.
        NaruSession first = newSession(projectDir, uuid);
        NaruTask task = first.newTask(NaruTaskSpec.of().statements(new String[0]));
        task.taskMode(net.thevpc.naru.api.scheduler.NaruTaskMode.INTERACTIVE);
        task.requestInput(net.thevpc.nuts.text.NMsg.ofC("what next?"));
        first.save();
        List<Long> taskIds = first.tasks().stream().map(NaruTask::id).toList();
        Assertions.assertFalse(taskIds.isEmpty(), "the first session must have created a task");
        first.stop();

        // Second process: the same id must resolve to the same stored session, with its task.
        NaruSession again = newSession(projectDir, uuid);
        Assertions.assertEquals(uuid, again.uuid(), "the restore must land on the requested id");
        Assertions.assertEquals(taskIds, again.tasks().stream().map(NaruTask::id).toList(),
                "build() must hydrate the stored task, not start from an empty session");
        for (NaruTask t : again.tasks()) {
            Assertions.assertTrue(t.isConversation(),
                    "the restored interactive task is a conversation and must survive a restart");
        }
        try {
            again.stop();
        } catch (RuntimeException ignored) {
            // never started
        }
    }
}
