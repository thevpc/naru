package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The session directory round trip, after the {@code projectDir -> sessionDir} rename.
 *
 * <p>The name of a persisted key is a contract: an old reader that looks for {@code
 * projectDir} silently finds nothing and quietly re-defaults the value, so what this pins
 * down is that the header and the task skeleton carry the key the type is named after,
 * carry the right value through a save and a reload, and never emit the retired key.
 */
@Timeout(30)
public class NaruSessionDirRoundTripTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private NPath projectDir;

    @BeforeEach
    public void newProject() {
        projectDir = NPath.ofTempFolder("naru-sessiondir-" + System.nanoTime());
    }

    private NaruSession newSession() {
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(projectDir);
        return agent.newSession()
                .sessionDir(projectDir)
                .interaction(new net.thevpc.naru.impl.interaction.NaruStreamInteraction(o -> {
                }))
                .configureDefaults(false)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
    }

    private static NObjectElement read(NPath file) {
        return NElementReader.ofTson().read(file).asObject().get();
    }

    @Test
    public void theSessionHeaderPersistsTheSessionDirKey() {
        NaruSession session = newSession();
        session.newTask(NaruTaskSpec.of().statements("noop"));
        session.save();

        NPath header = projectDir.resolve(".naru/local/sessions")
                .resolve(session.uuid()).resolve("session.tson");
        Assertions.assertTrue(header.isFile(), "expected a session.tson at " + header);
        NObjectElement o = read(header);
        Assertions.assertEquals(projectDir.toString(), o.getStringValue("sessionDir").orNull(),
                "the header must carry the session directory under the sessionDir key");
        Assertions.assertTrue(o.get("projectDir").isEmpty(),
                "the retired projectDir key must not be written");
    }

    @Test
    public void theTaskSkeletonPersistsTheSessionDirKey() {
        NaruSession session = newSession();
        NaruTask task = session.newTask(NaruTaskSpec.of().statements("noop"));
        session.save();

        NPath skeleton = projectDir.resolve(".naru/local/sessions")
                .resolve(session.uuid()).resolve("tasks").resolve(Long.toString(task.id()))
                .resolve("task.tson");
        Assertions.assertTrue(skeleton.isFile(), "expected a task.tson at " + skeleton);
        NObjectElement o = read(skeleton);
        Assertions.assertEquals(projectDir.toString(), o.getStringValue("sessionDir").orNull(),
                "the task skeleton must carry the anchor directory under the sessionDir key");
        Assertions.assertTrue(o.get("projectDir").isEmpty(),
                "the retired projectDir key must not be written");
    }

    @Test
    public void sessionAndTaskBothComeBackWithTheirSessionDir() {
        NaruSession session = newSession();
        NaruTask task = session.newTask(NaruTaskSpec.of().statements("noop"));
        long taskId = task.id();
        session.save();

        NaruSessionImpl impl = (NaruSessionImpl) session;
        net.thevpc.naru.api.store.NaruSessionData back =
                impl.rawSessionStore().loadData(session.uuid(),
                        net.thevpc.naru.api.store.NaruSessionScope.PRIVATE).orNull();
        Assertions.assertNotNull(back);
        Assertions.assertEquals(projectDir.toString(), back.sessionDir(),
                "the session header did not round trip its sessionDir");

        net.thevpc.naru.api.store.NaruTaskState skeleton =
                impl.rawSessionStore().loadTaskSkeleton(session.uuid(),
                        net.thevpc.naru.api.store.NaruSessionScope.PRIVATE, taskId).orNull();
        Assertions.assertNotNull(skeleton);
        Assertions.assertEquals(projectDir.toString(),
                skeleton.skeleton().asObject().get().getStringValue("sessionDir").orNull(),
                "the task skeleton's sessionDir was not restored");
    }
}
