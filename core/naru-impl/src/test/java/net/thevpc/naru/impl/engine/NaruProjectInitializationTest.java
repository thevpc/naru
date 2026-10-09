package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.engine.scheduler.NaruTaskImpl;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.naru.impl.registry.builtindirectives.NaruProjectDirective;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP7/WP8: {@code /cd} is pure navigation, {@code /project} is the one navigation with side
 * effects (it runs the workspace init exactly once), the deprecated init-on-cd path is opt-in
 * behind a warning, and the session announces {@code session-start}.
 * <p>
 * Tasks are built directly rather than through {@code session.newTask}, so the test needs no
 * model, no scheduler and no extension discovery: it drives exactly the hook seam under test.
 * An init hook is written as {@code /return <unique-marker>} because that statement parses
 * without a directive registry, and its marker can be counted without executing anything.
 */
public class NaruProjectInitializationTest {

    private NPath projectDir;
    private RecordingInteraction interaction;
    private NaruSessionImpl session;

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
        projectDir = NPath.ofTempFolder("naru-init-hooks-" + System.nanoTime());
        interaction = new RecordingInteraction();
        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(projectDir);
        session = new NaruSessionImpl(agent, projectDir, interaction, false, null, null, null, null);
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

    // ── fixtures ────────────────────────────────────────────────────────────

    private NaruTaskImpl newTask(NPath workingDir) {
        NaruTaskImpl task = new NaruTaskImpl(1, -1, session);
        task._setWorkingDir(workingDir);
        task._setProjectDir(projectDir);
        return task;
    }

    private static void initHook(NPath base, String marker) {
        NPath file = base.resolve(".naru/hooks/init.naru");
        file.mkParentDirs();
        file.writeString("/return " + marker + "\n");
    }

    /**
     * Drains the task and counts the parsed return statements carrying the marker. Draining
     * also proves the hooks are consumed once rather than left in place.
     */
    private static int countMarker(NaruTask task, String marker) {
        int n = 0;
        while (task.peekStatement().isPresent()) {
            NaruStatement s = task.nextStatement().get();
            String expr = s.toElement().asObject()
                    .flatMap(o -> o.getStringValue("expression"))
                    .orNull();
            if (marker.equals(expr)) {
                n++;
            }
        }
        return n;
    }

    private static NaruStmtResult runProject(NaruTask task, NPath dir) {
        NaruDirectiveCallContext ctx = new NaruDirectiveCallContextImpl("project", dir.toString(), task);
        return new NaruProjectDirective().execute(ctx);
    }

    // ── WP7: cd runs no hooks ────────────────────────────────────────────────

    @Test
    public void cdDoesNotRunInitHooks() {
        NPath sub = projectDir.resolve("sub");
        initHook(sub, "cd-marker");
        NaruTaskImpl task = newTask(projectDir);

        task.setWorkingDir(sub);

        assertEquals(sub, task.workingDir());
        assertEquals(0, countMarker(task, "cd-marker"),
                "/cd must be pure navigation: no init hooks may run");
    }

    @Test
    public void returningToProjectRootWithCdDoesNotRerunWorkspaceInit() {
        initHook(projectDir, "root-marker");
        NaruTaskImpl task = newTask(projectDir);

        // task creation is the one task-scoped trigger
        task.runInitHooks(NaruEvent.TASK_SPAWNED);
        assertEquals(1, countMarker(task, "root-marker"),
                "the workspace init runs once when the task is created at the project root");

        // leave and come back with /cd: no hook may run either time
        task.setWorkingDir(projectDir.resolve("sub"));
        task.setWorkingDir(projectDir);
        assertEquals(0, countMarker(task, "root-marker"),
                "returning to the project root with /cd must not re-run the workspace init");
    }

    // ── WP7: project change runs init once ───────────────────────────────────

    @Test
    public void changingProjectDirAloneDoesNotRunInitHooks() {
        NPath other = NPath.ofTempFolder("naru-init-other-" + System.nanoTime());
        initHook(other, "project-marker");
        NaruTaskImpl task = newTask(projectDir);

        session.setProjectDir(other);

        assertEquals(other.normalize(), session.projectDir());
        assertEquals(0, countMarker(task, "project-marker"),
                "the raw setter re-resolves roots; running the init is /project's job");
    }

    @Test
    public void projectDirectiveRunsWorkspaceInitExactlyOnce() {
        NPath other = NPath.ofTempFolder("naru-init-other-" + System.nanoTime());
        initHook(other, "project-marker");
        NaruTaskImpl task = newTask(projectDir);

        NaruStmtResult r = runProject(task, other);

        assertEquals(0, r.exitCode(), "the project directive reports success");
        assertEquals(other.normalize(), session.projectDir());
        assertEquals(1, countMarker(task, "project-marker"),
                "/project must run the workspace init exactly once");
    }

    // ── WP7: deprecated init-on-cd is opt-in, with a warning ─────────────────

    @Test
    public void initOnCdFlagRestoresOldBehaviourWithAWarning() {
        NPath sub = projectDir.resolve("sub");
        initHook(sub, "cd-marker");
        session.setSessionEnv("naru.initOnCd", true);
        NaruTaskImpl task = newTask(projectDir);

        task.setWorkingDir(sub);

        assertEquals(1, countMarker(task, "cd-marker"),
                "with the flag on, /cd runs the hooks again");
        assertTrue(interaction.written.stream().anyMatch(x -> x.contains("init-on-cd is deprecated")),
                "restoring the old behaviour must warn once: " + interaction.written);
    }

    // ── WP8: session-start ───────────────────────────────────────────────────

    @Test
    public void sessionStartFiresOnceWithTheProjectRoots() {
        EventRecorder recorder = new EventRecorder();
        session.addSessionListener(recorder);

        session.start();

        List<NaruEvent> starts = recorder.events.stream()
                .filter(e -> NaruEvent.SESSION_START.equals(e.name()))
                .toList();
        assertEquals(1, starts.size(), "session-start is fired exactly once per session");
        assertEquals(projectDir.normalize().toString(), starts.get(0).payload("projectDir"));
    }

    // ── help ─────────────────────────────────────────────────────────────────

    private static class RecordingInteraction implements NaruInteraction {
        final List<String> written = new ArrayList<>();

        @Override
        public String name() {
            return "test";
        }

        @Override
        public void open(NaruSession session) {
        }

        @Override
        public void requestInput(NaruInputRequest request) {
        }

        @Override
        public void write(NaruLogMode mode, NMsg message) {
            if (message != null) {
                written.add(message.toString());
            }
        }

        @Override
        public void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
            if (fragment != null) {
                written.add(fragment.toString());
            }
        }

        @Override
        public void close() {
        }
    }

    private static class EventRecorder implements NaruSessionListener {
        final List<NaruEvent> events = new ArrayList<>();

        @Override
        public void onEventAppended(NaruEvent newEvent) {
            events.add(newEvent);
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
    }
}
