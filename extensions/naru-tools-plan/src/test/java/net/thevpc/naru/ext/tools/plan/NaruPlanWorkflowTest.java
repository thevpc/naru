package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.routine.NaruStmtResultType;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration-style tests for plan directives and workflow: /mode plan, plan_create,
 * switching to impl mode, activating plans, etc.
 */
public class NaruPlanWorkflowTest {

    private NaruSession session;
    private NaruTask foregroundTask;

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
        agent.projectDirectory(NPath.ofTempFolder("naru-plan-workflow"));
        session = new NaruSessionImpl(agent, agent.projectDirectory(), null, true,
                NOOP_LISTENER, null, null, null);
        // Create a foreground task for directive execution
        foregroundTask = session.newTask(NaruTaskSpec.of());
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

    private NaruStmtResult callDirective(String directiveName, String argument) {
        NaruDirective directive = session.registry().findDirective(directiveName)
                .orElseThrow(() -> new AssertionError("no /" + directiveName + " directive"));
        NaruDirectiveCallContext ctx = new NaruDirectiveCallContextImpl(directiveName, argument, foregroundTask);
        return directive.execute(ctx);
    }

    private static boolean isOk(NaruStmtResult r) {
        return r.type() == NaruStmtResultType.SUCCESS;
    }

    private static boolean isErr(NaruStmtResult r) {
        return r.type() == NaruStmtResultType.ERROR;
    }

    private static String err(NaruStmtResult r) {
        return String.valueOf(r.errorValue());
    }

    private static NaruPlanManager plans(NaruSession session) {
        return NaruPlanExtension.plans(session);
    }

    @Test
    public void testPlanShowWithNoPlans() {
        NaruStmtResult result = callDirective("plan", "show");
        assertTrue(isErr(result));
        assertTrue(err(result).contains("no active plan"), err(result));
    }

    @Test
    public void testPlanListWithNoPlans() {
        NaruStmtResult result = callDirective("plan", "list");
        assertTrue(isOk(result), err(result));
    }

    @Test
    public void testPlanActivateWithNoNonFinishedPlans() {
        NaruStmtResult result = callDirective("plan", "activate");
        assertTrue(isErr(result));
        assertTrue(err(result).contains("no non-finished plan"), err(result));
    }

    @Test
    public void testPlanActivateWithNoArgAndSinglePlan() {
        // Create a plan
        NaruPlan plan = plans(session).createPlan("test goal", List.of(
                NaruPlanItemSpec.of("item1", "do something")
        ));
        assertNull(plans(session).activePlanId());

        // Activate without specifying id
        NaruStmtResult result = callDirective("plan", "activate");
        assertTrue(isOk(result), err(result));
        assertEquals(plan.id(), plans(session).activePlanId());
    }

    @Test
    public void testPlanActivateWithNoArgAndMultiplePlans() {
        plans(session).createPlan("goal1", List.of(NaruPlanItemSpec.of("a", "a1")));
        plans(session).createPlan("goal2", List.of(NaruPlanItemSpec.of("b", "b1")));
        assertNull(plans(session).activePlanId());

        // Should fail because multiple non-finished plans
        NaruStmtResult result = callDirective("plan", "activate");
        assertTrue(isErr(result));
        assertTrue(err(result).contains("multiple non-finished plans"), err(result));
    }

    @Test
    public void testPlanActivateWithNoArgSkipsCompletedPlans() {
        NaruPlan plan1 = plans(session).createPlan("goal1", List.of(NaruPlanItemSpec.of("a", "a1")));
        NaruPlan plan2 = plans(session).createPlan("goal2", List.of(NaruPlanItemSpec.of("b", "b1")));
        // Complete plan1
        plans(session).completeItem(plan1.id(), plan1.items().get(0).id(), true, "done");
        assertNull(plans(session).activePlanId());

        // Should activate plan2 only
        NaruStmtResult result = callDirective("plan", "activate");
        assertTrue(isOk(result), err(result));
        assertEquals(plan2.id(), plans(session).activePlanId());
    }

    @Test
    public void testPlanActivateWithExplicitId() {
        NaruPlan plan = plans(session).createPlan("test goal", List.of(
                NaruPlanItemSpec.of("item1", "do something")
        ));
        NaruStmtResult result = callDirective("plan", "activate " + plan.id());
        assertTrue(isOk(result), err(result));
        assertEquals(plan.id(), plans(session).activePlanId());
    }

    @Test
    public void testPlanActivateWithPrefix() {
        NaruPlan plan = plans(session).createPlan("test goal", List.of(
                NaruPlanItemSpec.of("item1", "do something")
        ));
        String prefix = plan.id().substring(0, 8);
        NaruStmtResult result = callDirective("plan", "activate " + prefix);
        assertTrue(isOk(result), err(result));
        assertEquals(plan.id(), plans(session).activePlanId());
    }

    @Test
    public void testPlanActivateWithUnknownIdIsRejected() {
        NaruStmtResult result = callDirective("plan", "activate nosuchplan");
        assertTrue(isErr(result));
        assertTrue(err(result).contains("no plan"), err(result));
    }

    @Test
    public void testPlanActivateInPlanModeIsRejected() {
        // Switch to plan mode
        foregroundTask.promptMode(session.registry().mode("plan").orNull());
        NaruPlan plan = plans(session).createPlan("test goal", List.of(
                NaruPlanItemSpec.of("item1", "do something")
        ));
        NaruStmtResult result = callDirective("plan", "activate");
        assertTrue(isErr(result));
        assertTrue(err(result).contains("cannot activate plan while in planning mode"), err(result));
        // the plan must not have been activated behind the user's back
        assertNull(plans(session).activePlanId());
    }

    @Test
    public void testPlanDeactivate() {
        NaruPlan plan = plans(session).createPlan("test goal", List.of(
                NaruPlanItemSpec.of("item1", "do something")
        ));
        plans(session).setActivePlanId(plan.id());
        assertEquals(plan.id(), plans(session).activePlanId());

        NaruStmtResult result = callDirective("plan", "deactivate");
        assertTrue(isOk(result), err(result));
        assertNull(plans(session).activePlanId());
    }

    @Test
    public void testPlanDeactivateWhenNoActivePlan() {
        assertNull(plans(session).activePlanId());
        NaruStmtResult result = callDirective("plan", "deactivate");
        assertTrue(isOk(result)); // Just warns/logs
    }

    @Test
    public void testPlanShowActivePlan() {
        NaruPlan plan = plans(session).createPlan("test goal", List.of(
                NaruPlanItemSpec.of("item1", "do something"),
                NaruPlanItemSpec.of("item2", "do another").dependsOn("item1")
        ));
        plans(session).setActivePlanId(plan.id());
        NaruStmtResult result = callDirective("plan", "show");
        assertTrue(isOk(result), err(result));
    }

    @Test
    public void testPlanShowWithSpecificId() {
        NaruPlan plan = plans(session).createPlan("test goal", List.of(
                NaruPlanItemSpec.of("item1", "do something")
        ));
        NaruStmtResult result = callDirective("plan", "show " + plan.id());
        assertTrue(isOk(result), err(result));
    }

    @Test
    public void testPlanShowWithUnknownId() {
        NaruStmtResult result = callDirective("plan", "show nosuchplan");
        assertTrue(isErr(result));
        assertTrue(err(result).contains("no such plan"), err(result));
    }

    @Test
    public void testPlanShowFallsBackToTheOnlyPlan() {
        // no active plan, but exactly one plan exists: the user only has to type /plan show
        plans(session).createPlan("the only goal", List.of(NaruPlanItemSpec.of("a", "work")));
        NaruStmtResult result = callDirective("plan", "show");
        assertTrue(isOk(result), err(result));
    }

    @Test
    public void testPlanShowRefusesToGuessBetweenSeveralUnfinishedPlans() {
        plans(session).createPlan("goal1", List.of(NaruPlanItemSpec.of("a", "a1")));
        plans(session).createPlan("goal2", List.of(NaruPlanItemSpec.of("b", "b1")));
        NaruStmtResult result = callDirective("plan", "show");
        assertTrue(isErr(result));
        assertTrue(err(result).contains("multiple active plans exist"), err(result));
    }

    @Test
    public void testPlanClearActive() {
        NaruPlan plan = plans(session).createPlan("test goal", List.of(
                NaruPlanItemSpec.of("item1", "do something")
        ));
        plans(session).setActivePlanId(plan.id());
        NaruStmtResult result = callDirective("plan", "clear");
        assertTrue(isOk(result), err(result));
        assertNull(plans(session).findPlan(plan.id()).orNull());
        assertNull(plans(session).activePlanId());
    }

    @Test
    public void testPlanClearSpecific() {
        NaruPlan plan = plans(session).createPlan("test goal", List.of(
                NaruPlanItemSpec.of("item1", "do something")
        ));
        NaruStmtResult result = callDirective("plan", "clear " + plan.id());
        assertTrue(isOk(result), err(result));
        assertNull(plans(session).findPlan(plan.id()).orNull());
    }

    @Test
    public void testPlanListShowsPlans() {
        NaruPlan plan = plans(session).createPlan("my test goal", List.of(
                NaruPlanItemSpec.of("item1", "do something")
        ));
        NaruStmtResult result = callDirective("plan", "list");
        assertTrue(isOk(result), err(result));
    }

    @Test
    public void testPlanReopenWithoutArgument() {
        NaruStmtResult result = callDirective("plan", "reopen");
        assertTrue(isErr(result));
        assertTrue(err(result).contains("usage"), err(result));
    }

    @Test
    public void testPlanReopenWithCascadeIsRefused() {
        NaruPlan plan = plans(session).createPlan("test goal", List.of(
                NaruPlanItemSpec.of("a", "first"),
                NaruPlanItemSpec.of("b", "second").dependsOn("a")
        ));
        plans(session).setActivePlanId(plan.id());
        NaruPlanItem a = plan.items().get(0);
        plans(session).completeItem(plan.id(), a.id(), true, null);

        // cascade is destructive, so it must be confirmed rather than silently applied
        NaruStmtResult result = callDirective("plan", "reopen " + a.id() + " --cascade");
        assertTrue(isErr(result));
        assertTrue(err(result).contains("--cascade"), err(result));
        assertEquals(NaruPlanItemStatus.DONE, a.status());
    }

    @Test
    public void testPlanReopenFinishedItem() {
        NaruPlan plan = plans(session).createPlan("test goal", List.of(
                NaruPlanItemSpec.of("a", "first")
        ));
        plans(session).setActivePlanId(plan.id());
        NaruPlanItem a = plan.items().get(0);
        plans(session).completeItem(plan.id(), a.id(), true, null);

        NaruStmtResult result = callDirective("plan", "reopen " + a.id().substring(0, 8));
        assertTrue(isOk(result), err(result));
        assertEquals(NaruPlanItemStatus.READY, a.status());
    }

    @Test
    public void testPlanReopenUnfinishedItemIsRejected() {
        NaruPlan plan = plans(session).createPlan("test goal", List.of(
                NaruPlanItemSpec.of("a", "first")
        ));
        plans(session).setActivePlanId(plan.id());
        NaruPlanItem a = plan.items().get(0);
        assertEquals(NaruPlanItemStatus.READY, a.status());

        NaruStmtResult result = callDirective("plan", "reopen " + a.id().substring(0, 8));
        assertTrue(isErr(result));
        assertTrue(err(result).contains("only finished items can be reopened"), err(result));
        assertEquals(NaruPlanItemStatus.READY, a.status());
    }

    @Test
    public void testPlanReopenWithoutActivePlan() {
        plans(session).createPlan("test goal", List.of(NaruPlanItemSpec.of("a", "first")));
        NaruStmtResult result = callDirective("plan", "reopen aaaa");
        assertTrue(isErr(result));
        assertTrue(err(result).contains("no active plan"), err(result));
    }

    @Test
    public void testPlanModeRejectsExecuteAndWriteTags() {
        // in plan mode the model must not be able to mutate the workspace
        var planMode = session.registry().mode("plan").orNull();
        assertTrue(planMode.acceptToolTags(java.util.Set.of("fs")));
        assertFalse(planMode.acceptToolTags(java.util.Set.of("exec")));
        assertFalse(planMode.acceptToolTags(java.util.Set.of("write")));
        // the plan tools themselves are the one thing plan mode must still offer
        assertTrue(planMode.acceptToolTags(java.util.Set.of("plan")));
    }

    @Test
    public void testOnlyTheExecutingModeDeclaresTheExecutingIntent() {
        // the mode-switch hook keys off this, so a mode that declares nothing must not
        // be mistaken for one the user is asking to build something
        assertEquals(net.thevpc.naru.api.mode.NaruPromptMode.ModeIntent.PLANNING,
                session.registry().mode("plan").orNull().modeIntent());
        assertEquals(net.thevpc.naru.api.mode.NaruPromptMode.ModeIntent.EXECUTING,
                session.registry().mode("implement").orNull().modeIntent());
        assertEquals(net.thevpc.naru.api.mode.NaruPromptMode.ModeIntent.GENERAL,
                session.registry().mode(net.thevpc.naru.api.mode.NaruPromptMode.DEFAULT).orNull().modeIntent());
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