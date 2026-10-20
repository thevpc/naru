package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionStoreManager;
import net.thevpc.naru.api.agent.NaruWorkspace;
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
 * The workspace's saved-session catalog.
 * <p>
 * The desktop used to open a store per sidebar row. Two handles onto one folder listed the
 * same session twice, and a row could not be deleted because the handle behind the button
 * was not the handle that had read it. These tests pin the single-handle model: one catalog
 * per workspace, every saved session listed exactly once, and a delete that lands on the row
 * that was listed.
 */
@Timeout(60)
public class NaruWorkspaceCatalogTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NaruWorkspace agent(NPath projectDir) {
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(projectDir);
        return agent;
    }

    private static NaruSession session(NaruWorkspace agent, NPath projectDir, String id) {
        return agent.newSession()
                .directory(projectDir)
                .id(id)
                .interaction(new NaruStreamInteraction(o -> {
                }))
                .banner(false)
                .build();
    }

    /** A workspace hands back one catalog, and a new project gets its own. */
    @Test
    public void theCatalogIsSharedAndFollowsTheProject() {
        NPath projectDir = NPath.ofTempFolder("naru-cat-" + System.nanoTime());
        NaruWorkspace agent = agent(projectDir);
        NaruSessionStoreManager first = agent.sessionCatalog();
        Assertions.assertSame(first, agent.sessionCatalog(),
                "a workspace must not hand out a second handle to the same catalog");

        NaruWorkspace other = agent(NPath.ofTempFolder("naru-cat-other-" + System.nanoTime()));
        Assertions.assertNotSame(first, other.sessionCatalog(),
                "the catalog is disk state rooted at the project and must not follow the old one");
    }

    /** Each saved session is listed once, and a delete removes exactly that one. */
    @Test
    public void everySavedSessionIsListedOnceAndDeletable() {
        NPath projectDir = NPath.ofTempFolder("naru-cat2-" + System.nanoTime());
        NaruWorkspace agent = agent(projectDir);
        NaruSessionStoreManager catalog = agent.sessionCatalog();

        Assertions.assertTrue(catalog.list().isEmpty(),
                "a fresh project must have an empty catalog");

        NaruSession a = session(agent, projectDir, "aaaaaaaa-0000-0000-0000-000000000001");
        a.newTask(NaruTaskSpec.of().statements(new String[0]));
        a.save();
        NaruSession b = session(agent, projectDir, "bbbbbbbb-0000-0000-0000-000000000002");
        b.newTask(NaruTaskSpec.of().statements(new String[0]));
        b.save();
        try { a.stop(); } catch (RuntimeException ignored) { }
        try { b.stop(); } catch (RuntimeException ignored) { }

        List<NaruResourceInfo> listed = catalog.list();
        Assertions.assertEquals(2, listed.size(),
                "two saved sessions must be listed exactly twice in total, got " + listed.size());

        String target = listed.get(0).getUuid();
        Assertions.assertTrue(catalog.delete(target),
                "the row that was listed must be deletable through the same catalog");
        List<NaruResourceInfo> after = catalog.list();
        Assertions.assertEquals(1, after.size(),
                "deleting one row must leave exactly one, got " + after.size());
        Assertions.assertNotEquals(target, after.get(0).getUuid(),
                "the wrong session was deleted");
    }
}
