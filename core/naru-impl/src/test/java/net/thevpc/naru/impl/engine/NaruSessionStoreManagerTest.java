package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.agent.NaruSessionStoreManager;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

/**
 * The on-disk catalog, and specifically the boundary it draws around itself.
 * <p>
 * A running session writes itself a working snapshot as it goes. That scratch state lives
 * under {@code .naru/local/} and must never be mistaken for a saved session: if it were,
 * every project would report phantom entries in {@code /sessions} and a restore would drag
 * transient state along with it.
 */
@Timeout(30)
public class NaruSessionStoreManagerTest {

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

    @BeforeEach
    public void newProject() {
        projectDir = NPath.ofTempFolder("naru-store-" + System.nanoTime());
    }

    private NaruSessionImpl newSession() {
        NaruAgentImpl agent = new NaruAgentImpl();
        agent.projectDirectory(projectDir);
        return new NaruSessionImpl(agent, projectDir,
                new net.thevpc.naru.impl.interaction.NaruStreamInteraction(o -> {
                }),
                false, null, null, null, null);
    }

    @Test
    public void anUnsavedProjectHasNothingToList() {
        NaruSessionStoreManager store = newSession().sessionStoreManager();
        Assertions.assertTrue(store.list().isEmpty(),
                "a fresh project must not invent saved sessions, got " + store.list().size());
    }

    @Test
    public void aSavedSessionIsListed() {
        NaruSessionImpl session = newSession();
        session.newTask(NaruTaskSpec.of().statements("noop"));
        session.save();

        List<NaruResourceInfo> found = session.sessionStoreManager().list();
        Assertions.assertEquals(1, found.size(),
                "the saved session should be visible exactly once, got " + found.size());
        Assertions.assertEquals(session.uuid(), found.get(0).getUuid());
    }

    /**
     * The regression: a running session leaves a snapshot behind, and that snapshot used to
     * sit inside the very directory this catalog scans.
     */
    @Test
    public void aRunningSessionsSnapshotIsNotMistakenForASavedSession() {
        NaruSessionImpl session = newSession();
        session.newTask(NaruTaskSpec.of().statements("noop"));
        session.saveSnapshot();
        session.start();

        Assertions.assertTrue(projectDir.resolve(".naru/local/snapshot/" + session.uuid()).exists(),
                "the working snapshot should have been written");
        Assertions.assertTrue(session.sessionStoreManager().list().isEmpty(),
                "scratch state must never appear as a saved session, got "
                        + session.sessionStoreManager().list().size());
    }

    @Test
    public void twoSessionsInOneProjectDoNotCollide() {
        NaruAgentImpl agent = new NaruAgentImpl();
        agent.projectDirectory(projectDir);
        NaruSessionImpl a = newSession();
        NaruSessionImpl b = newSession();

        a.newTask(NaruTaskSpec.of().statements("noop"));
        b.newTask(NaruTaskSpec.of().statements("noop"));
        a.saveSnapshot();
        b.saveSnapshot();
        a.save();
        b.save();

        List<NaruResourceInfo> found = a.sessionStoreManager().list();
        Assertions.assertEquals(2, found.size(),
                "both sessions share a project and must be stored separately, got " + found.size());
    }

    @Test
    public void aSavedSessionCanBeFoundByUuid() {
        NaruSessionImpl session = newSession();
        session.newTask(NaruTaskSpec.of().statements("noop"));
        session.save();
        Assertions.assertEquals(session.uuid(), session.sessionStoreManager().findByUuidOrName(session.uuid()));
    }

    @Test
    public void anUnknownKeyFindsNothing() {
        NaruSessionStoreManager store = newSession().sessionStoreManager();
        Assertions.assertNull(store.findByUuidOrName("no-such-session"));
    }

    @Test
    public void deletingRemovesTheSessionFromDisk() {
        NaruSessionImpl session = newSession();
        session.newTask(NaruTaskSpec.of().statements("noop"));
        session.save();
        String uuid = session.uuid();
        Assertions.assertEquals(1, session.sessionStoreManager().list().size());

        session.sessionStoreManager().delete(uuid);

        Assertions.assertTrue(session.sessionStoreManager().list().isEmpty(),
                "a deleted session must not still be listed");
    }
}
