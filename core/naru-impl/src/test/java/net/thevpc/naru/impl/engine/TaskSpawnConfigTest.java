package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.mode.NaruStandardMode;
import net.thevpc.naru.api.registry.DefaultNaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTagProvider;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.scheduler.NaruTaskSchedulerView;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.registry.NaruRegistryImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Guards the two prerequisites the planning layer needs from the task engine:
 *
 * <ol>
 *     <li>a task's prompt mode and tool tags must be settable <b>at spawn time</b>.
 *         A plan selector runs in {@code PLANNING} and has to spawn {@code IMPLEMENT}
 *         executors, and item-tasks need tags granted explicitly because a task only
 *         sees a tagged tool when it holds one of that tool's tags. Both were previously
 *         only reachable by mutating the task <i>after</i> {@code newTask(..)} returned,
 *         which leaves a window where a scheduler worker can already pick the task up
 *         under the inherited mode and with no tags.</li>
 *     <li>cancellation must be observable from inside a running statement.
 *         {@code kill()} only sets the {@code KILLED} status, and the scheduler reads that
 *         between statements, so a long running tool or model call had no way to learn it
 *         was being cancelled. {@code isKillRequested()} provides that, and
 *         {@code onTaskStatusChanged} gives the plan a termination hook carrying the task
 *         object (the task is already unregistered from the session by the time listeners
 *         run, so the object reference is the only usable handle).</li>
 * </ol>
 */
public class TaskSpawnConfigTest {

    private NaruSession session;

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
        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-task-spawn"));
        // configureDefaults=false: no SPI provider discovery, no network, no real models
        session = new NaruSessionImpl(agent, agent.projectDirectory(), null, false,
                new NaruSessionListener() {
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
                }, null, null, null);
        // tool tags are contributed by per-extension providers; register the builtin set
        // directly instead of going through registerDefaults() (which would pull in
        // model providers and toolsets)
        registerTags(NaruToolTags.ROUTINE, NaruToolTags.AI, NaruToolTags.NETWORK,
                NaruToolTags.WRITE, NaruToolTags.EXECUTE, "plan");
    }

    private void registerTags(String... names) {
        List<NaruToolTag> tags = new ArrayList<>();
        for (String name : names) {
            tags.add(new DefaultNaruToolTag(name, name + " operations"));
        }
        ((NaruRegistryImpl) session.registry()).registerToolTagProvider(new NaruToolTagProvider() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public List<NaruToolTag> tags() {
                return Collections.unmodifiableList(tags);
            }
        });
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

    private NaruPromptMode mode(NaruStandardMode standard) {
        return session.registry().mode(standard).get();
    }

    private static List<String> tagsOf(NaruTask task) {
        return task.findToolTags().stream().map(NaruToolTag::name).sorted().collect(Collectors.toList());
    }

    // ── spawn-time prompt mode ───────────────────────────────────────────────

    @Test
    public void rootTaskDefaultsToPlanning() {
        NaruTask root = session.newTask(NaruTaskSpec.of());
        Assertions.assertSame(mode(NaruStandardMode.PLANNING), root.promptMode());
    }

    @Test
    public void specModeOverridesInheritedParentMode() {
        // the case the planning layer depends on: a PLANNING selector spawning
        // IMPLEMENT executors. Before NaruTaskSpec.promptMode(..) this was impossible
        // without a post-creation flip that races the scheduler.
        NaruTask selector = session.newTask(NaruTaskSpec.of()
                .promptMode(mode(NaruStandardMode.PLANNING)));
        NaruTask executor = session.newTask(NaruTaskSpec.of()
                .parentId(selector.id())
                .promptMode(mode(NaruStandardMode.IMPLEMENT)));

        Assertions.assertSame(mode(NaruStandardMode.PLANNING), selector.promptMode());
        Assertions.assertSame(mode(NaruStandardMode.IMPLEMENT), executor.promptMode());
    }

    @Test
    public void childInheritsParentModeWhenSpecDeclaresNone() {
        NaruTask parent = session.newTask(NaruTaskSpec.of()
                .promptMode(mode(NaruStandardMode.REVIEW)));
        NaruTask child = session.newTask(NaruTaskSpec.of().parentId(parent.id()));
        Assertions.assertSame(mode(NaruStandardMode.REVIEW), child.promptMode());
    }

    @Test
    public void specModeAppliesToRootTasksToo() {
        NaruTask root = session.newTask(NaruTaskSpec.of()
                .promptMode(mode(NaruStandardMode.IMPLEMENT)));
        Assertions.assertSame(mode(NaruStandardMode.IMPLEMENT), root.promptMode());
    }

    // ── spawn-time tool tags ─────────────────────────────────────────────────

    @Test
    public void specToolTagsAreGranted() {
        NaruTask task = session.newTask(NaruTaskSpec.of().toolTags("exec", "plan"));
        Assertions.assertEquals(List.of("exec", "plan"), tagsOf(task));
    }

    @Test
    public void toolTagsAreNotInheritedFromParent() {
        NaruTask parent = session.newTask(NaruTaskSpec.of().toolTags("exec"));
        NaruTask child = session.newTask(NaruTaskSpec.of().parentId(parent.id()));
        Assertions.assertEquals(List.of("exec"), tagsOf(parent));
        // a child sees no tagged tool at all unless the selector grants the tags
        Assertions.assertEquals(List.of(), tagsOf(child));
    }

    @Test
    public void specToolTagsReplaceRatherThanAccumulate() {
        NaruTaskSpec spec = NaruTaskSpec.of().toolTags("exec", "plan");
        NaruTask first = session.newTask(spec);
        Assertions.assertEquals(List.of("exec", "plan"), tagsOf(first));

        spec.toolTags("network");
        NaruTask second = session.newTask(spec);
        Assertions.assertEquals(List.of("network"), tagsOf(second));
    }

    @Test
    public void specToolTagsIgnoreBlanksAndNulls() {
        NaruTaskSpec spec = NaruTaskSpec.of().toolTags((String) null);
        Assertions.assertEquals(Set.of(), spec.toolTags());
        spec.toolTags(" exec ", "", "   ");
        Assertions.assertEquals(List.of("exec"), List.copyOf(spec.toolTags()));
    }

    // ── cooperative cancellation ─────────────────────────────────────────────

    @Test
    public void killSetsKillRequestedAndStatus() {
        NaruTask task = session.newTask(NaruTaskSpec.of());
        Assertions.assertFalse(task.isKillRequested());

        task.kill();

        Assertions.assertTrue(task.isKillRequested());
        Assertions.assertEquals(NaruTaskStatus.KILLED, task.status());
    }

    @Test
    public void killRequestedSurvivesPerTickStatusChurn() {
        // the scheduler flips RUNNING -> previous around every tick; polling
        // status() would therefore miss the cancellation on some ticks
        NaruTask task = session.newTask(NaruTaskSpec.of());
        task.kill();
        ((NaruTaskSchedulerView) task).status(NaruTaskStatus.RUNNING);
        ((NaruTaskSchedulerView) task).status(NaruTaskStatus.READY);
        Assertions.assertTrue(task.isKillRequested());
    }

    @Test
    public void resetClearsKillRequested() {
        NaruTask task = session.newTask(NaruTaskSpec.of());
        task.kill();
        task.reset();
        Assertions.assertFalse(task.isKillRequested());
    }

    @Test
    public void listenerObservesTerminalTransitionWithUsableTaskReference() {
        // onTerminated() unregisters the task before listeners run, so the object
        // reference is the only handle a plan selector can use to learn the outcome
        List<NaruTask> seen = new ArrayList<>();
        List<NaruTaskStatus> newStatuses = new ArrayList<>();
        session.addSessionListener(new NaruSessionListener() {
            @Override
            public void onEventAppended(NaruEvent newEvent) {
            }

            @Override
            public void sessionStarted(NaruSession s) {
            }

            @Override
            public void sessionStopped(NaruSession s) {
            }

            @Override
            public void onSessionReloaded(NaruSession s) {
            }

            @Override
            public void onTaskStatusChanged(NaruTask t, NaruTaskStatus oldStatus, NaruTaskStatus newStatus) {
                if (newStatus == NaruTaskStatus.KILLED) {
                    seen.add(t);
                    newStatuses.add(newStatus);
                }
            }
        });

        // two tasks, so killing one does not stop the session (which would otherwise
        // happen before listeners are notified)
        NaruTask other = session.newTask(NaruTaskSpec.of());
        NaruTask victim = session.newTask(NaruTaskSpec.of());
        victim.kill();

        Assertions.assertEquals(1, seen.size());
        Assertions.assertSame(victim, seen.get(0));
        Assertions.assertEquals(NaruTaskStatus.KILLED, newStatuses.get(0));
        // confirms the handle must come from the argument, not a lookup
        Assertions.assertNull(session.findTask(victim.id()).orNull());
        Assertions.assertNotNull(session.findTask(other.id()).orNull());
    }

    @Test
    public void listenerStillSeesFinalTransitionOfLastTask() {
        // onTerminated() stops the session when the last task dies; the notification
        // must not be swallowed by that
        List<NaruTaskStatus> seen = new ArrayList<>();
        session.addSessionListener(new NaruSessionListener() {
            @Override
            public void onEventAppended(NaruEvent newEvent) {
            }

            @Override
            public void sessionStarted(NaruSession s) {
            }

            @Override
            public void sessionStopped(NaruSession s) {
            }

            @Override
            public void onSessionReloaded(NaruSession s) {
            }

            @Override
            public void onTaskStatusChanged(NaruTask t, NaruTaskStatus oldStatus, NaruTaskStatus newStatus) {
                if (newStatus == NaruTaskStatus.KILLED) {
                    seen.add(newStatus);
                }
            }
        });

        NaruTask only = session.newTask(NaruTaskSpec.of());
        only.kill();

        Assertions.assertEquals(List.of(NaruTaskStatus.KILLED), seen);
    }

    @Test
    public void throwingListenerDoesNotEscapeStatusChange() {
        // this runs inside NaruTaskImpl.status(); a throwing listener would otherwise
        // fail the very task being ticked
        session.addSessionListener(new NaruSessionListener() {
            @Override
            public void onEventAppended(NaruEvent newEvent) {
            }

            @Override
            public void sessionStarted(NaruSession s) {
            }

            @Override
            public void sessionStopped(NaruSession s) {
            }

            @Override
            public void onSessionReloaded(NaruSession s) {
            }

            @Override
            public void onTaskStatusChanged(NaruTask t, NaruTaskStatus oldStatus, NaruTaskStatus newStatus) {
                throw new IllegalStateException("boom");
            }
        });

        NaruTask a = session.newTask(NaruTaskSpec.of());
        NaruTask b = session.newTask(NaruTaskSpec.of());
        Assertions.assertDoesNotThrow(b::kill);
        Assertions.assertEquals(NaruTaskStatus.KILLED, b.status());
        Assertions.assertNotNull(session.findTask(a.id()).orNull());
    }
}
