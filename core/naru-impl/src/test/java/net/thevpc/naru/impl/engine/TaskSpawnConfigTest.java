package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.registry.DefaultNaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTagProvider;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.scheduler.NaruTaskSchedulerView;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.spawn.NaruSpawnInherit;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.ia.mode.NaruStandardPromptModeImpl;
import net.thevpc.naru.impl.registry.NaruRegistryImpl;
import net.thevpc.naru.impl.engine.scheduler.NaruTaskImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
        Nuts.require();    }

    @BeforeEach
    public void setUp() {
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(NPath.ofTempFolder("naru-task-spawn"));
        // configureDefaults=false: no SPI provider discovery, no network, no real models
        session = agent.newSession()
                .directory(agent.projectDirectory())
                .interaction(null)
                .configureDefaults(false)
                .sessionListener(new NaruSessionListener() {
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
                })
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
        // tool tags are contributed by per-extension providers; register the builtin set
        // directly instead of going through registerDefaults() (which would pull in
        // model providers and toolsets)
        registerTags(NaruToolTags.ROUTINE, NaruToolTags.AI, NaruToolTags.NETWORK,
                NaruToolTags.WRITE, NaruToolTags.EXECUTE, "plan");
        // PLANNING and IMPLEMENT moved to the plan extension, and this session is built
        // without SPI discovery, so declare the two modes the planner spawns between.
        session.registry().declareMode(new NaruStandardPromptModeImpl("planning", new String[0], "", tags -> true));
        session.registry().declareMode(new NaruStandardPromptModeImpl("implement", new String[0], "", tags -> true));
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

    private NaruPromptMode mode(String standard) {
        return session.registry().mode(standard).get();
    }

    private static List<String> tagsOf(NaruTask task) {
        return task.findToolTags().stream().map(NaruToolTag::name).sorted().collect(Collectors.toList());
    }

    // ── spawn-time prompt mode ───────────────────────────────────────────────

    @Test
    public void rootTaskDefaultsToTheDefaultMode() {
        // PLANNING was the root default before the planning layer moved to an
        // extension; a session that did not ask for it now starts in DEFAULT.
        NaruTask root = session.newTask(NaruTaskSpec.of());
        Assertions.assertSame(mode("DEFAULT"), root.promptMode());
    }

    @Test
    public void specModeOverridesInheritedParentMode() {
        // the case the planning layer depends on: a PLANNING selector spawning
        // IMPLEMENT executors. Before NaruTaskSpec.promptMode(..) this was impossible
        // without a post-creation flip that races the scheduler.
        NaruTask selector = session.newTask(NaruTaskSpec.of()
                .promptMode(mode("PLANNING")))
                ;
        NaruTask executor = session.newTask(NaruTaskSpec.of()
                .parentId(selector.id())
                .promptMode(mode("IMPLEMENT")));

        Assertions.assertSame(mode("PLANNING"), selector.promptMode());
        Assertions.assertSame(mode("IMPLEMENT"), executor.promptMode());
    }

    @Test
    public void childInheritsParentModeWhenSpecDeclaresNone() {
        String REVIEW="PLANNING";
        NaruTask parent = session.newTask(NaruTaskSpec.of()
                .promptMode(mode(REVIEW)));
        NaruTask child = session.newTask(NaruTaskSpec.of().parentId(parent.id()));
        Assertions.assertSame(mode(REVIEW), child.promptMode());
    }

    @Test
    public void specModeAppliesToRootTasksToo() {
        NaruTask root = session.newTask(NaruTaskSpec.of()
                .promptMode(mode("IMPLEMENT")));
        Assertions.assertSame(mode("IMPLEMENT"), root.promptMode());
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

    // ── budget labels ────────────────────────────────────────────────────────

    @Test
    public void specLabelsAreAppliedToTheTask() {
        NaruTask task = session.newTask(NaruTaskSpec.of()
                .addLabel("module", "billing")
                .addLabel("tier", "pro"));
        Assertions.assertEquals(Map.of("module", "billing", "tier", "pro"), task.labels());
    }

    /**
     * Unlike tags and env, labels are inherited by default: a child almost always shares
     * its spawner's descriptive dimensions, and a caller who says nothing must not lose them.
     */
    @Test
    public void labelsInheritFromParentByDefault() {
        NaruTask parent = session.newTask(NaruTaskSpec.of().addLabel("module", "billing"));
        NaruTask child = session.newTask(NaruTaskSpec.of().parentId(parent.id()));
        Assertions.assertEquals(Map.of("module", "billing"), child.labels());
    }

    @Test
    public void childLabelsOverrideInheritedOnes() {
        NaruTask parent = session.newTask(NaruTaskSpec.of().addLabel("module", "billing"));
        NaruTask child = session.newTask(NaruTaskSpec.of()
                .parentId(parent.id())
                .addLabel("module", "search"));
        Assertions.assertEquals(Map.of("module", "search"), child.labels());
    }

    @Test
    public void revokingALabelRemovesTheInheritedOne() {
        NaruTask parent = session.newTask(NaruTaskSpec.of()
                .addLabel("module", "billing")
                .addLabel("tier", "pro"));
        NaruTask child = session.newTask(NaruTaskSpec.of()
                .parentId(parent.id())
                .revokeLabels("tier"));
        Assertions.assertEquals(Map.of("module", "billing"), child.labels());
    }

    /**
     * A caller who enumerates an inherit set without {@code labels} has opted out of the
     * default. This is the one way to stop inheriting, since the inherit set only adds.
     */
    @Test
    public void enumeratingAnInheritSetWithoutLabelsOptsOut() {
        NaruTask parent = session.newTask(NaruTaskSpec.of().addLabel("module", "billing"));
        NaruTask child = session.newTask(NaruTaskSpec.of()
                .parentId(parent.id())
                .inherit(NaruSpawnInherit.TAGS));
        Assertions.assertEquals(Map.of(), child.labels());
    }

    @Test
    public void nameAndLabelsRoundTripThroughSerialization() {
        NaruTaskImpl task = (NaruTaskImpl) session.newTask(NaruTaskSpec.of()
                .name("my-routine")
                .addLabel("module", "billing")
                .addLabel("tier", "pro"));

        NaruTaskImpl reloaded = new NaruTaskImpl(task.toElement(), (NaruSessionImpl) session);

        Assertions.assertEquals("my-routine", reloaded.name(),
                "the task name was not serialized before, so a reloaded task lost it");
        Assertions.assertEquals(Map.of("module", "billing", "tier", "pro"), reloaded.labels(),
                "labels are persisted with the task and must survive a reload");
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
