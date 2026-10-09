package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP7: {@code /project} re-resolves the skill roots without touching the task's selection.
 * The manager is rebound to the new project root and reloaded; a task keeps the skills it
 * loaded, so one that disappeared is visible as missing rather than silently unloaded.
 */
public class NaruSkillsProjectChangeTest {

    private static final NaruSessionListener NOOP_LISTENER = new NaruSessionListener() {
        @Override
        public void onEventAppended(NaruEvent newEvent) {
        }

        @Override
        public void sessionStarted(NaruSession session) {
        }

        @Override
        public void sessionStopped(NaruSession session) {
        }

        @Override
        public void onSessionReloaded(NaruSession naruSession) {
        }
    };

    private NPath projectDir;
    private NaruSessionImpl session;
    private NaruSkillsExtension ext;

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

    @BeforeEach
    public void setUp() {
        projectDir = NPath.ofTempFolder("naru-skills-project-" + System.nanoTime());
        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(projectDir);
        session = new NaruSessionImpl(agent, projectDir,
                new NaruStreamInteraction(o -> {
                }), true, NOOP_LISTENER, null, null, null);
        ext = NaruSkillsExtension.skills(session);
    }

    @AfterEach
    public void tearDown() {
        if (session != null) {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private static void skill(NPath base, String name) {
        NPath file = base.resolve(".naru/skills/" + name + ".md");
        file.mkParentDirs();
        file.writeString(name + " body\n");
    }

    private Set<String> availableNames() {
        return ext.skills().available().stream().map(NaruSkill::getName).collect(Collectors.toSet());
    }

    @Test
    public void projectChangeRebindsRootsAndKeepsTheSelection() {
        String alpha = "alpha-" + Long.toString(System.nanoTime(), 36);
        String beta = "beta-" + Long.toString(System.nanoTime(), 36);
        skill(projectDir, alpha);
        NPath other = NPath.ofTempFolder("naru-skills-project-other-" + System.nanoTime());
        skill(other, beta);
        // the session snapshotted the roots when it opened; pick up both files now
        ext.reload();

        NaruTask task = session.newTask(NaruTaskSpec.of());
        assertTrue(availableNames().contains(alpha), "alpha is available at the original root");
        assertFalse(availableNames().contains(beta), "beta is not available before the change");
        assertTrue(ext.load(task, alpha), "alpha is loaded for the task");

        session.setProjectDir(other);

        Set<String> after = availableNames();
        assertTrue(after.contains(beta), "beta becomes available at the new root: " + after);
        assertFalse(after.contains(alpha), "alpha is no longer available at the new root: " + after);
        assertTrue(ext.loadedNames(task).contains(alpha),
                "the task keeps the skill it loaded; it is reported missing, not unloaded");
        assertFalse(ext.loadedNames(task).contains(beta),
                "availability does not silently select anything for the task");
    }
}
