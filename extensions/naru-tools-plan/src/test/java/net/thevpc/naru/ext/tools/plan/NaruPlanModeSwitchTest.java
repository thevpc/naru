package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.registry.NaruSessionExtension;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the plan-mode round trip a human actually performs:
 *
 * <pre>
 *   /mode plan
 *   ...model plans...
 *   /mode impl
 * </pre>
 *
 * <p>The interesting part is the second line. Activation used to require an explicit
 * {@code /plan activate} after the mode switch, which made the plugin feel like it was
 * withholding something. Since a human mode switch <em>is</em> the consent activation
 * exists to require, switching into an executing mode now activates the sole unfinished
 * plan by itself, and refuses to guess when several exist.
 */
public class NaruPlanModeSwitchTest {

    private NaruSession session;
    private NaruTask task;

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
        agent.projectDirectory(NPath.ofTempFolder("naru-plan-mode-switch"));
        session = new NaruSessionImpl(agent, agent.projectDirectory(), null, true,
                NOOP_LISTENER, null, null, null);
        task = session.newTask(NaruTaskSpec.of());
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

    private NaruPromptMode mode(String name) {
        NaruPromptMode m = session.registry().mode(name).orNull();
        assertNotNull(m, "no mode '" + name + "' is registered");
        return m;
    }

    private NaruPlanManager plans() {
        return NaruPlanExtension.plans(session);
    }

    private NaruPlan planOf(String goal, String... items) {
        List<NaruPlanItemSpec> specs = java.util.Arrays.stream(items)
                .map(NaruPlanItemSpec::of)
                .collect(java.util.stream.Collectors.toList());
        return plans().createPlan(goal, specs);
    }

    // ── the modes the flow relies on ─────────────────────────────────────────

    @Test
    public void planAndImplementModesAreBothRegistered() {
        // aliases matter: the flow is usually typed as "/mode impl" and "/mode plan"
        assertSame(mode("plan"), mode("planning"));
        assertSame(mode("plan"), mode("architect"));
        assertSame(mode("implement"), mode("impl"));
        assertSame(mode("implement"), mode("do"));
    }

    @Test
    public void planModeIsReadOnlyAndImplementModeIsNot() {
        assertTrue(mode("plan").acceptToolTags(java.util.Set.of("fs")));
        assertTrue(!mode("plan").acceptToolTags(java.util.Set.of("exec")));
        assertTrue(!mode("plan").acceptToolTags(java.util.Set.of("write")));
        assertTrue(mode("implement").acceptToolTags(java.util.Set.of("exec")));
        assertTrue(mode("implement").acceptToolTags(java.util.Set.of("write")));
    }

    // ── the round trip ──────────────────────────────────────────────────────

    @Test
    public void planningThenImplementingActivatesThePlanOnItsOwn() {
        NaruPlan plan = planOf("migrate to java 25",
                "inventory the build", "rewrite the poms", "run the tests");
        task.promptMode(mode("plan"));

        // planning: the plan exists but nothing is active, so it cannot run itself
        assertNull(plans().activePlanId());

        // the human says "go"
        task.promptMode(mode("implement"));

        assertEquals(plan.id(), plans().activePlanId());
    }

    @Test
    public void theActivatedPlanIsContributedToTheNextPrompt() {
        NaruPlan plan = planOf("migrate to java 25", "inventory the build");
        task.promptMode(mode("plan"));
        task.promptMode(mode("implement"));

        NaruSessionExtension ext = session.registry()
                .extension(NaruPlanExtension.NAME, NaruSessionExtension.class).orNull();
        assertNotNull(ext);
        List<net.thevpc.naru.api.model.NaruMessage> contributed = ext.contribute(task);
        assertEquals(1, contributed.size());
        String text = contributed.get(0).getContent();
        // the executor must see the same graph the planner produced
        assertTrue(text.contains("inventory the build"), text);
        assertTrue(text.contains("### ACTIVE PLAN:"), text);
    }

    @Test
    public void switchingToPlanModeNeverActivatesAnything() {
        planOf("migrate to java 25", "inventory the build");
        task.promptMode(mode("plan"));
        assertNull(plans().activePlanId());
    }

    @Test
    public void switchingBetweenTwoExecutingModesChangesNothing() {
        NaruPlan plan = planOf("migrate to java 25", "inventory the build");
        task.promptMode(mode("plan"));
        task.promptMode(mode("implement"));
        assertEquals(plan.id(), plans().activePlanId());

        // leaving implement for the default mode must not re-activate, and coming back
        // must not disturb the plan the user already had active
        task.promptMode(session.registry().mode(NaruPromptMode.DEFAULT).orNull());
        task.promptMode(mode("implement"));
        assertEquals(plan.id(), plans().activePlanId(),
                "an already active plan must not be disturbed by a mode switch");
    }

    @Test
    public void leavingThePlanModeForTheDefaultModeDoesNotActivate() {
        // the default mode is not "implement" by intent: nobody said "go build this",
        // so it must not be read as consent to start executing a plan
        planOf("migrate to java 25", "inventory the build");
        task.promptMode(mode("plan"));
        task.promptMode(session.registry().mode(NaruPromptMode.DEFAULT).orNull());
        assertNull(plans().activePlanId());
    }

    @Test
    public void goingStraightToImplementFromTheDefaultModeStillActivates() {
        // a user who never entered plan mode but has a plan lying around and says
        // "/mode impl" is still asking for it to be run
        NaruPlan plan = planOf("migrate to java 25", "inventory the build");
        task.promptMode(mode("implement"));
        assertEquals(plan.id(), plans().activePlanId());
    }

    @Test
    public void reSelectingTheSameModeIsANoOp() {
        NaruPlan plan = planOf("migrate to java 25", "inventory the build");
        task.promptMode(mode("plan"));
        task.promptMode(mode("implement"));
        assertEquals(plan.id(), plans().activePlanId());

        plans().setActivePlanId(null);
        task.promptMode(mode("implement"));
        assertNull(plans().activePlanId(),
                "setting the mode to the value it already has must not fire the hook again");
        assertEquals(1, plans().plans().size());
        assertEquals(plan.id(), plans().plans().keySet().iterator().next());
    }

    // ── ambiguity is never resolved by guessing ─────────────────────────────

    @Test
    public void switchingToImplementDoesNotGuessBetweenSeveralUnfinishedPlans() {
        planOf("migrate to java 25", "inventory the build");
        planOf("fix the flaky test", "reproduce it");
        task.promptMode(mode("plan"));

        task.promptMode(mode("implement"));

        assertNull(plans().activePlanId(),
                "with several unfinished plans the user must choose, not have one picked for them");
    }

    @Test
    public void completedPlansAreNotCandidatesForAutoActivation() {
        NaruPlan done = planOf("already shipped", "nothing left");
        plans().completeItem(done.id(), done.items().get(0).id(), true, "done");
        NaruPlan pending = planOf("migrate to java 25", "inventory the build");

        task.promptMode(mode("plan"));
        task.promptMode(mode("implement"));

        assertEquals(pending.id(), plans().activePlanId(),
                "the sole unfinished plan is unambiguous even when a finished one exists");
    }

    @Test
    public void anAlreadyActivePlanIsLeftAloneWhenAnotherExists() {
        NaruPlan chosen = planOf("migrate to java 25", "inventory the build");
        planOf("fix the flaky test", "reproduce it");
        plans().setActivePlanId(chosen.id());

        task.promptMode(mode("implement"));

        assertEquals(chosen.id(), plans().activePlanId(),
                "an explicit activation must survive a later mode switch");
    }

    @Test
    public void switchingToImplementWithNoPlansAtAllIsSilentAndHarmless() {
        task.promptMode(mode("implement"));
        assertNull(plans().activePlanId());
        assertEquals(0, plans().plans().size());
    }

    // ── the hook is part of the extension contract, not a plan special case ──

    @Test
    public void everySessionExtensionIsOfferedTheModeChange() {
        // the core must notify extensions generically: the plan feature may not be the
        // only one that cares that the user changed mode. The probe is discovered the
        // same way a third-party extension would be, through the service loader.
        assertNotNull(session.registry()
                        .extension(RecordingSessionExtension.NAME_FOR_LOOKUP, NaruSessionExtension.class).orNull(),
                "the probe extension must have been discovered by the registry");
        RecordingSessionExtension.reset();
        task.promptMode(mode("implement"));
        assertEquals(List.of("default->implement"), RecordingSessionExtension.CALLS);
    }

    @Test
    public void theModeChangeHookFiresOncePerRealChange() {
        RecordingSessionExtension.reset();
        task.promptMode(mode("plan"));
        task.promptMode(mode("plan"));
        task.promptMode(mode("implement"));
        assertEquals(List.of("default->plan", "plan->implement"), RecordingSessionExtension.CALLS);
    }

    private static final NaruSessionListener NOOP_LISTENER = new NaruSessionListener() {
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
    };
}