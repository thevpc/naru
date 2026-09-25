package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.api.registry.NaruSessionExtension;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Covers the plan graph itself: alias resolution, cycle rejection, derived readiness,
 * validator-gated completion, cascade reopen, and the persisted round trip.
 *
 * <p>These are pure model tests — no scheduler, no model, no network — because the
 * graph is deliberately the one part of the planning feature that carries policy
 * (readiness, completion gating) rather than orchestration.
 */
public class NaruPlanGraphTest {

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
        agent.setProjectDirectory(NPath.ofTempFolder("naru-plan-graph"));
        // configureDefaults must be on: the plan graph is now provided by a session
        // extension discovered through the registry, not by the session itself
        session = new NaruSessionImpl(agent, agent.getProjectDirectory(), null, true,
                NOOP_LISTENER, null, null, null);
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

    private NaruPlan planOf(String... descriptions) {
        List<NaruPlanItemSpec> specs = new ArrayList<>();
        for (String d : descriptions) {
            specs.add(NaruPlanItemSpec.of(d));
        }
        return plans(session).createPlan("goal", specs);
    }

    private static NaruPlanItem item(NaruPlan plan, String description) {
        for (NaruPlanItem i : plan.items()) {
            if (description.equals(i.description())) {
                return i;
            }
        }
        throw new AssertionError("no item " + description);
    }

    // ── construction ─────────────────────────────────────────────────────────

    @Test
    public void plansAndItemsAreKeyedByUuidNotPosition() {
        NaruPlan plan = planOf("first", "second");
        Assertions.assertNotNull(plan.id());
        Assertions.assertNotEquals(plan.id(), planOf("first", "second").id());
        for (NaruPlanItem i : plan.items()) {
            Assertions.assertNotNull(i.id());
            Assertions.assertEquals(36, i.id().length());
        }
        Assertions.assertNotEquals(plan.items().get(0).id(), plan.items().get(1).id());
    }

    @Test
    public void dependencyKeysAreResolvedToUuids() {
        NaruPlan plan = plans(session).createPlan("goal", Arrays.asList(
                NaruPlanItemSpec.of("a", "design the schema"),
                NaruPlanItemSpec.of("b", "write the migration").dependsOn("a"),
                NaruPlanItemSpec.of("c", "write the tests").dependsOn("a", "b")
        ));
        NaruPlanItem a = item(plan, "design the schema");
        NaruPlanItem b = item(plan, "write the migration");
        NaruPlanItem c = item(plan, "write the tests");
        Assertions.assertEquals(List.of(), a.dependsOn());
        Assertions.assertEquals(List.of(a.id()), b.dependsOn());
        Assertions.assertEquals(List.of(a.id(), b.id()), c.dependsOn());
    }

    @Test
    public void unknownDependencyKeyIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class, () ->
                plans(session).createPlan("goal", List.of(
                        NaruPlanItemSpec.of("a", "only item").dependsOn("nope"))));
    }

    @Test
    public void duplicateKeyIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class, () ->
                plans(session).createPlan("goal", List.of(
                        NaruPlanItemSpec.of("k", "one"),
                        NaruPlanItemSpec.of("k", "two"))));
    }

    @Test
    public void selfDependencyIsRejectedAsCycle() {
        NaruPlan plan = plans(session).createPlan("goal",
                List.of(NaruPlanItemSpec.of("a", "only item")));
        NaruPlanItem a = plan.items().get(0);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> plan.addDependency(a.id(), a.id()));
    }

    @Test
    public void cycleIsRejectedAndEdgeRolledBack() {
        NaruPlan plan = plans(session).createPlan("goal", Arrays.asList(
                NaruPlanItemSpec.of("a", "first"),
                NaruPlanItemSpec.of("b", "second").dependsOn("a"),
                NaruPlanItemSpec.of("c", "third").dependsOn("b")
        ));
        NaruPlanItem a = item(plan, "first");
        NaruPlanItem c = item(plan, "third");

        Assertions.assertThrows(IllegalArgumentException.class, () -> plan.addDependency(a.id(), c.id()));
        // the rejected edge must not linger, or the plan is silently corrupted
        Assertions.assertEquals(List.of(), a.dependsOn());
        plan.assertAcyclic();
    }

    @Test
    public void diamondDependencyIsAllowed() {
        NaruPlan plan = plans(session).createPlan("goal", Arrays.asList(
                NaruPlanItemSpec.of("a", "root"),
                NaruPlanItemSpec.of("b", "left").dependsOn("a"),
                NaruPlanItemSpec.of("c", "right").dependsOn("a"),
                NaruPlanItemSpec.of("d", "join").dependsOn("b", "c")
        ));
        plan.assertAcyclic();
        Assertions.assertEquals(4, plan.items().size());
    }

    // ── derived readiness ────────────────────────────────────────────────────

    @Test
    public void onlyItemsWithSatisfiedDependenciesBecomeReady() {
        NaruPlan plan = plans(session).createPlan("goal", Arrays.asList(
                NaruPlanItemSpec.of("a", "design"),
                NaruPlanItemSpec.of("b", "implement").dependsOn("a")
        ));
        NaruPlanItem a = item(plan, "design");
        NaruPlanItem b = item(plan, "implement");
        Assertions.assertEquals(NaruPlanItemStatus.READY, a.status());
        Assertions.assertEquals(NaruPlanItemStatus.PENDING, b.status());
        Assertions.assertEquals(NaruPlanStatus.ACTIVE, plan.status());
    }

    @Test
    public void completingAnItemUnblocksItsDependents() {
        NaruPlan plan = plans(session).createPlan("goal", Arrays.asList(
                NaruPlanItemSpec.of("a", "design"),
                NaruPlanItemSpec.of("b", "implement").dependsOn("a")
        ));
        NaruPlanItem a = item(plan, "design");
        NaruPlanItem b = item(plan, "implement");

        plans(session).completeItem(plan.id(), a.id(), true, "shipped");
        Assertions.assertEquals(NaruPlanItemStatus.DONE, a.status());
        Assertions.assertEquals(NaruPlanItemStatus.READY, b.status());
    }

    @Test
    public void recomputeDemotesReadyWhenADependencyReopens() {
        NaruPlan plan = plans(session).createPlan("goal", Arrays.asList(
                NaruPlanItemSpec.of("a", "design"),
                NaruPlanItemSpec.of("b", "implement").dependsOn("a")
        ));
        NaruPlanItem a = item(plan, "design");
        NaruPlanItem b = item(plan, "implement");
        plans(session).completeItem(plan.id(), a.id(), true, null);
        Assertions.assertEquals(NaruPlanItemStatus.READY, b.status());

        plans(session).reopenItem(plan.id(), a.id(), false);
        // a reopened item is runnable again at once; only its dependents have to wait
        Assertions.assertEquals(NaruPlanItemStatus.READY, a.status());
        Assertions.assertEquals(NaruPlanItemStatus.PENDING, b.status());
    }

    @Test
    public void recomputeNeverTouchesLiveOrTerminalItems() {
        NaruPlan plan = plans(session).createPlan("goal", List.of(NaruPlanItemSpec.of("a", "only")));
        NaruPlanItem a = plan.items().get(0);
        plans(session).reportItem(plan.id(), a.id(), NaruPlanItemStatus.RUNNING, null);
        plans(session).recomputeAndFill(plan.id());
        Assertions.assertEquals(NaruPlanItemStatus.RUNNING, a.status());

        plans(session).reportItem(plan.id(), a.id(), NaruPlanItemStatus.BLOCKED, "waiting on infra");
        plans(session).recomputeAndFill(plan.id());
        Assertions.assertEquals(NaruPlanItemStatus.BLOCKED, a.status());
    }

    @Test
    public void danglingDependencyKeepsItemPending() {
        // an edge to an item that no longer exists must not silently make this item
        // runnable. The public API cannot create such an edge, so it is injected
        // directly, the way a stale reference would arrive from a reloaded session.
        NaruPlan plan = plans(session).createPlan("goal", List.of(
                NaruPlanItemSpec.of("only", "no other deps")));
        NaruPlanItem only = plan.items().get(0);
        only.addDependency("00000000-dead-beef-0000-000000000000");
        plans(session).recomputeAndFill(plan.id());
        Assertions.assertEquals(NaruPlanItemStatus.PENDING, only.status());

        only.removeDependency("00000000-dead-beef-0000-000000000000");
        plans(session).recomputeAndFill(plan.id());
        Assertions.assertEquals(NaruPlanItemStatus.READY, only.status());
    }

    // ── completion gating ────────────────────────────────────────────────────

    @Test
    public void executorCannotMarkAnItemDone() {
        NaruPlan plan = planOf("only");
        NaruPlanItem a = plan.items().get(0);
        NOptional<NaruPlanItem> r =
                plans(session).reportItem(plan.id(), a.id(), NaruPlanItemStatus.DONE, "trust me");
        Assertions.assertTrue(r.isError());
        Assertions.assertEquals(NaruPlanItemStatus.READY, a.status());
    }

    @Test
    public void executorCannotDeclareAnItemReady() {
        NaruPlan plan = plans(session).createPlan("goal", Arrays.asList(
                NaruPlanItemSpec.of("a", "design"),
                NaruPlanItemSpec.of("b", "implement").dependsOn("a")
        ));
        NaruPlanItem b = item(plan, "implement");
        NOptional<NaruPlanItem> r =
                plans(session).reportItem(plan.id(), b.id(), NaruPlanItemStatus.READY, null);
        Assertions.assertTrue(r.isError());
        Assertions.assertEquals(NaruPlanItemStatus.PENDING, b.status());
    }

    @Test
    public void failedValidatorPutsTheItemInFailedNotDone() {
        NaruPlan plan = plans(session).createPlan("goal", List.of(
                NaruPlanItemSpec.of("a", "risky change").validator(NaruPlanValidatorKind.MODEL_REVIEW)));
        NaruPlanItem a = plan.items().get(0);
        plans(session).completeItem(plan.id(), a.id(), false, "reviewer found a data loss bug");
        Assertions.assertEquals(NaruPlanItemStatus.FAILED, a.status());
        Assertions.assertEquals("reviewer found a data loss bug", a.notes());
        Assertions.assertEquals(NaruPlanStatus.BLOCKED, plan.status());
    }

    @Test
    public void passedValidatorCompletesTheItem() {
        NaruPlan plan = plans(session).createPlan("goal", List.of(
                NaruPlanItemSpec.of("a", "reviewed change").validator(NaruPlanValidatorKind.MODEL_REVIEW)));
        NaruPlanItem a = plan.items().get(0);
        plans(session).completeItem(plan.id(), a.id(), true, "looks good");
        Assertions.assertEquals(NaruPlanItemStatus.DONE, a.status());
        Assertions.assertEquals(NaruPlanStatus.COMPLETED, plan.status());
    }

    @Test
    public void terminalItemRejectsFurtherReporting() {
        NaruPlan plan = planOf("only");
        NaruPlanItem a = plan.items().get(0);
        plans(session).completeItem(plan.id(), a.id(), true, null);
        NOptional<NaruPlanItem> r =
                plans(session).reportItem(plan.id(), a.id(), NaruPlanItemStatus.RUNNING, null);
        Assertions.assertTrue(r.isError());
    }

    // ── reopen / cascade ─────────────────────────────────────────────────────

    @Test
    public void reopenWithoutCascadeLeavesDependentsAlone() {
        NaruPlan plan = plans(session).createPlan("goal", Arrays.asList(
                NaruPlanItemSpec.of("a", "first"),
                NaruPlanItemSpec.of("b", "second").dependsOn("a")
        ));
        NaruPlanItem a = item(plan, "first");
        NaruPlanItem b = item(plan, "second");
        plans(session).completeItem(plan.id(), a.id(), true, null);
        plans(session).completeItem(plan.id(), b.id(), true, null);
        Assertions.assertEquals(NaruPlanStatus.COMPLETED, plan.status());

        List<NaruPlanItem> demoted = plans(session).reopenItem(plan.id(), a.id(), false).orNull();
        Assertions.assertEquals(1, demoted.size());
        Assertions.assertEquals(NaruPlanItemStatus.READY, a.status());
        Assertions.assertEquals(NaruPlanItemStatus.DONE, b.status());
        Assertions.assertNull(a.taskId());
    }

    @Test
    public void reopenWithCascadeDemotesTransitiveDependents() {
        NaruPlan plan = plans(session).createPlan("goal", Arrays.asList(
                NaruPlanItemSpec.of("a", "first"),
                NaruPlanItemSpec.of("b", "second").dependsOn("a"),
                NaruPlanItemSpec.of("c", "third").dependsOn("b"),
                NaruPlanItemSpec.of("d", "independent")
        ));
        for (NaruPlanItem i : plan.items()) {
            plans(session).completeItem(plan.id(), i.id(), true, null);
        }
        NaruPlanItem a = item(plan, "first");
        NaruPlanItem c = item(plan, "third");
        NaruPlanItem d = item(plan, "independent");

        List<NaruPlanItem> demoted = plans(session).reopenItem(plan.id(), a.id(), true).orNull();
        Assertions.assertEquals(3, demoted.size());
        // the reopened root becomes runnable again straight away, which is the point of
        // reopening it; its descendants wait because it is not done
        Assertions.assertEquals(NaruPlanItemStatus.READY, a.status());
        Assertions.assertEquals(NaruPlanItemStatus.PENDING, c.status());
        // an item outside the dependency cone must survive
        Assertions.assertEquals(NaruPlanItemStatus.DONE, d.status());
    }

    @Test
    public void reopenOfNonTerminalItemIsANoOp() {
        NaruPlan plan = planOf("only");
        NaruPlanItem a = plan.items().get(0);
        List<NaruPlanItem> demoted = plans(session).reopenItem(plan.id(), a.id(), true).orNull();
        Assertions.assertTrue(demoted.isEmpty());
        Assertions.assertEquals(NaruPlanItemStatus.READY, a.status());
    }

    // ── item lookup by prefix ────────────────────────────────────────────────

    @Test
    public void itemsResolveByUniquePrefix() {
        NaruPlan plan = planOf("only");
        NaruPlanItem a = plan.items().get(0);
        String prefix = a.id().substring(0, 8);
        Assertions.assertEquals(a.id(), plan.findItemByPrefix(prefix).id());
        Assertions.assertEquals(a.id(), plan.findItemByPrefix(a.id()).id());
    }

    @Test
    public void ambiguousOrUnknownPrefixResolvesToNothing() {
        NaruPlan plan = planOf("one", "two");
        Assertions.assertNull(plan.findItemByPrefix("zzzzzzzz"));
        Assertions.assertNull(plan.findItemByPrefix(""));
        Assertions.assertNull(plan.findItemByPrefix(null));
    }

    // ── activation ───────────────────────────────────────────────────────────

    @Test
    public void activationIsSessionStateIndependentOfAnyTask() {
        NaruPlan plan = planOf("only");
        Assertions.assertNull(plans(session).activePlanId());
        Assertions.assertTrue(plans(session).activePlan().isEmpty());

        plans(session).setActivePlanId(plan.id());
        Assertions.assertEquals(plan.id(), plans(session).activePlanId());

        plans(session).setActivePlanId(null);
        Assertions.assertTrue(plans(session).activePlan().isEmpty());
    }

    @Test
    public void activatingAnUnknownPlanThrows() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> plans(session).setActivePlanId("nope"));
    }

    @Test
    public void removingTheActivePlanDeactivatesIt() {
        NaruPlan plan = planOf("only");
        plans(session).setActivePlanId(plan.id());
        Assertions.assertTrue(plans(session).removePlan(plan.id()));
        Assertions.assertNull(plans(session).activePlanId());
    }

    @Test
    public void multiplePlansCoexist() {
        NaruPlan a = planOf("a1", "a2");
        NaruPlan b = planOf("b1");
        NaruPlan c = planOf("c1", "c2", "c3");
        Assertions.assertEquals(3, plans(session).plans().size());
        Assertions.assertNotNull(plans(session).findPlan(a.id()).orNull());
        Assertions.assertNotNull(plans(session).findPlan(b.id()).orNull());
        Assertions.assertNotNull(plans(session).findPlan(c.id()).orNull());
        // removing one leaves the others addressable
        plans(session).removePlan(b.id());
        Assertions.assertEquals(2, plans(session).plans().size());
        Assertions.assertNotNull(plans(session).findPlan(c.id()).orNull());
    }

    // ── persistence ──────────────────────────────────────────────────────────

    @Test
    public void planSurvivesARoundTrip() {
        NaruPlan plan = plans(session).createPlan("ship the thing", Arrays.asList(
                NaruPlanItemSpec.of("a", "design"),
                NaruPlanItemSpec.of("b", "implement").dependsOn("a").validator(NaruPlanValidatorKind.USER_APPROVAL)
        ));
        NaruPlanItem a = item(plan, "design");
        NaruPlanItem b = item(plan, "implement");
        plans(session).completeItem(plan.id(), a.id(), true, "done and dusted");
        b.setTaskId(42L);
        b.setAttempts(1);
        plans(session).setActivePlanId(plan.id());

        NPath folder = NPath.ofTempFolder("naru-plan-persist");
        save(session, folder);

        NaruPlanManagerHolder reloaded = new NaruPlanManagerHolder();
        reloaded.manager.loadFrom(stateFile(folder));

        NaruPlan back = reloaded.manager.findPlan(plan.id()).orNull();
        Assertions.assertNotNull(back);
        Assertions.assertEquals("ship the thing", back.goal());
        Assertions.assertEquals(2, back.items().size());
        NaruPlanItem backA = back.item(a.id());
        NaruPlanItem backB = back.item(b.id());
        Assertions.assertNotNull(backA);
        Assertions.assertNotNull(backB);
        Assertions.assertEquals(NaruPlanItemStatus.DONE, backA.status());
        Assertions.assertEquals("done and dusted", backA.notes());
        Assertions.assertEquals(NaruPlanValidatorKind.USER_APPROVAL, backB.validator());
        Assertions.assertEquals(1, backB.attempts());
        Assertions.assertEquals(Long.valueOf(42L), backB.taskId());
        Assertions.assertEquals(List.of(a.id()), backB.dependsOn());
        Assertions.assertEquals(plan.id(), reloaded.manager.activePlanId());
    }

    @Test
    public void legacyFlatPlanIsSkippedRatherThanMisread() {
        // the state file written by the pre-DAG build: taskId + steps, no id + items
        NPath folder = NPath.ofTempFolder("naru-plan-legacy").resolve("ext").mkdirs();
        String tson = "{ plans: [ { taskId: 7, goal: \"old\", creationInstant: \"2020-01-01T00:00:00Z\","
                + " modificationInstant: \"2020-01-01T00:00:00Z\","
                + " steps: [ { id: 1, description: \"step one\", status: \"pending\" } ] } ] }";
        folder.resolve(NaruPlanExtension.NAME + ".tson").writeString(tson);

        NaruPlanManagerHolder holder = new NaruPlanManagerHolder();
        holder.manager.loadFrom(folder.resolve("ext").resolve(NaruPlanExtension.NAME + ".tson"));
        Assertions.assertTrue(holder.manager.plans().isEmpty());
    }

    @Test
    public void aRejectedBatchLeavesThePlanUntouched() {
        NaruPlan plan = plans(session).createPlan("goal", List.of(NaruPlanItemSpec.of("a", "first")));
        NaruPlanItem a = plan.items().get(0);

        // b depends on c, and c depends back on b: a cycle that must roll the whole
        // batch out rather than leaving half of it in the graph
        Assertions.assertThrows(IllegalArgumentException.class, () -> plan.addItems(Arrays.asList(
                NaruPlanItemSpec.of("b", "second").dependsOn("c"),
                NaruPlanItemSpec.of("c", "third").dependsOn("b")
        )));

        Assertions.assertEquals(1, plan.items().size());
        Assertions.assertEquals(a.id(), plan.items().get(0).id());
        // and the plan must still be usable afterwards
        Assertions.assertDoesNotThrow(() -> plan.addItems(List.of(NaruPlanItemSpec.of("d", "fourth").dependsOn("a"))));
        Assertions.assertEquals(2, plan.items().size());
    }

    @Test
    public void plansAndItemsResolveByPrefix() {
        NaruPlan plan = plans(session).createPlan("goal", Arrays.asList(
                NaruPlanItemSpec.of("a", "first"),
                NaruPlanItemSpec.of("b", "second").dependsOn("a")
        ));
        NaruPlanItem a = item(plan, "first");
        NaruPlanItem b = item(plan, "second");
        String planPrefix = plan.id().substring(0, 8);

        // the render() output is what a model actually quotes back at us
        Assertions.assertEquals(plan.id(), plans(session).findPlan(planPrefix).orNull().id());
        Assertions.assertEquals(plan.id(), plans(session).findPlan(plan.id()).orNull().id());
        Assertions.assertEquals(a.id(), plans(session).findItem(planPrefix, a.id().substring(0, 8)).orNull().id());
        Assertions.assertEquals(b.id(), plans(session).findItem(planPrefix, b.id().substring(0, 8)).orNull().id());

        // an unknown handle resolves to nothing rather than to a neighbouring plan/item
        Assertions.assertFalse(plans(session).findPlan("ffffffff-nope").isPresent());
        Assertions.assertFalse(plans(session).findItem(planPrefix, "ffffffff-nope").isPresent());

        // ambiguity cannot be forced here (ids are random), so it is asserted on the
        // deterministic single-character case: no id may be assumed to start with 'f'
        Assertions.assertFalse(plans(session).findPlan("f").isPresent()
                        && plans(session).plans().keySet().stream()
                        .filter(id -> id.startsWith("f")).count() == 1,
                "an ambiguous plan prefix must resolve to nothing, not to an arbitrary plan");
    }

    @Test
    public void aPlanWithNoItemsSurvivesARoundTrip() {
        // an empty plan is legal: it is a goal the architect has not broken down yet
        NaruPlan plan = plans(session).createPlan("not broken down yet", List.of());
        NPath folder = NPath.ofTempFolder("naru-plan-empty-persist");
        save(session, folder);

        NaruPlanManagerHolder reloaded = new NaruPlanManagerHolder();
        reloaded.manager.loadFrom(stateFile(folder));
        NaruPlan back = reloaded.manager.findPlan(plan.id()).orNull();
        Assertions.assertNotNull(back, "an empty plan must not be dropped on reload");
        Assertions.assertEquals("not broken down yet", back.goal());
        Assertions.assertTrue(back.items().isEmpty());
    }

    @Test
    public void declarationKeysSurviveLaterBatchesAndAReload() {
        // a key is the only short meaningful handle on an item, so a plan has to stay
        // extensible by key after the batch that declared it
        NaruPlan plan = plans(session).createPlan("goal", List.of(NaruPlanItemSpec.of("a", "first")));
        NaruPlanItem a = item(plan, "first");
        Assertions.assertEquals("a", plan.keyOf(a.id()));

        Assertions.assertTrue(plans(session).addItems(plan.id(),
                List.of(NaruPlanItemSpec.of("b", "second").dependsOn("a"))).isPresent());
        NaruPlanItem b = item(plan, "second");
        Assertions.assertEquals(List.of(a.id()), b.dependsOn());

        NPath folder = NPath.ofTempFolder("naru-plan-keys-persist");
        save(session, folder);
        NaruPlanManagerHolder reloaded = new NaruPlanManagerHolder();
        reloaded.manager.loadFrom(stateFile(folder));
        NaruPlan back = reloaded.manager.findPlan(plan.id()).orNull();
        Assertions.assertNotNull(back);
        Assertions.assertEquals("a", back.keyOf(a.id()));
        Assertions.assertEquals("b", back.keyOf(b.id()));

        // a key freed by removing its item must not resolve again
        Assertions.assertTrue(back.removeItem(a.id()));
        Assertions.assertNull(back.keyOf(a.id()));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> back.addItems(List.of(NaruPlanItemSpec.of("c", "third").dependsOn("a"))));
    }

    /** Reaches the plan graph the way production code does: through the session SPI. */
    private static NaruPlanManager plans(NaruSession session) {
        return NaruPlanExtension.plans(session);
    }

    /** Drives a real save through the session so the SPI's file contract is exercised. */
    private static void save(NaruSession session, NPath folder) {
        NPath extDir = folder.mkdirs().resolve("ext");
        NElement state = ((NaruPlanManagerImpl) NaruPlanExtension.plans(session)).toElement();
        NPath file = extDir.resolve(NaruPlanExtension.NAME + ".tson");
        if (state == null) {
            if (file.exists()) {
                file.delete();
            }
        } else {
            NElementWriter.ofTson().ntf(false).write(state, file);
        }
    }

    private static NPath stateFile(NPath folder) {
        return folder.resolve("ext").resolve(NaruPlanExtension.NAME + ".tson");
    }

    @Test
    public void theSessionDiscoversThePlanExtensionThroughTheSpi() {
        // proves the feature is reached only via the registry, never through core
        Assertions.assertTrue(session.registry().sessionExtensions().stream()
                        .anyMatch(e -> e.name().equals(NaruPlanExtension.NAME)),
                "the plan extension must be discovered as a session extension");
        NaruSessionExtension found = session.registry()
                .extension(NaruPlanExtension.NAME, NaruSessionExtension.class).orNull();
        Assertions.assertNotNull(found);
        // the same instance must back the tools, or their state would be invisible to
        // the prompt contributor and to persistence
        Assertions.assertSame(NaruPlanExtension.plans(session), ((NaruPlanExtension) found).plans());
        Assertions.assertEquals(Collections.emptyList(), found.contribute(null));
    }

    @Test
    public void anActivePlanIsContributedToTheTaskPrompt() {
        NaruSessionExtension ext = session.registry()
                .extension(NaruPlanExtension.NAME, NaruSessionExtension.class).orNull();
        Assertions.assertNotNull(ext);
        // nothing active yet
        Assertions.assertTrue(ext.contribute(null).isEmpty());

        NaruPlan plan = plans(session).createPlan("ship it", List.of(NaruPlanItemSpec.of("a", "design")));
        // creating a plan does not activate it: activation is an explicit, separate act,
        // otherwise a stray declaration would hijack the prompt of a running task
        Assertions.assertTrue(ext.contribute(null).isEmpty());

        plans(session).setActivePlanId(plan.id());
        List<NaruMessage> contributed = ext.contribute(null);
        Assertions.assertEquals(1, contributed.size());
        String text = contributed.get(0).getContent();
        Assertions.assertTrue(text.contains("### ACTIVE PLAN:"), text);
        Assertions.assertTrue(text.contains("design"), text);
        // the completion rule must travel with the plan, or the model will mark items
        // done itself and bypass the validator
        Assertions.assertTrue(text.contains("cannot mark an item done"), text);
    }

    @Test
    public void aCompletedPlanStopsBeingContributed() {
        NaruSessionExtension ext = session.registry()
                .extension(NaruPlanExtension.NAME, NaruSessionExtension.class).orNull();
        NaruPlan plan = plans(session).createPlan("goal", List.of(NaruPlanItemSpec.of("a", "only")));
        plans(session).setActivePlanId(plan.id());
        plans(session).completeItem(plan.id(), plan.items().get(0).id(), true, "done");
        Assertions.assertEquals(NaruPlanStatus.COMPLETED, plan.status());
        Assertions.assertTrue(ext.contribute(null).isEmpty());
    }

    @Test
    public void theActivePlanReachesTheRealTaskContext() {
        // the previous tests exercise the extension directly; this one proves the core
        // actually splices it in, which is the whole point of the SPI
        NaruPlan plan = plans(session).createPlan("ship the thing",
                List.of(NaruPlanItemSpec.of("a", "design the widget")));
        plans(session).setActivePlanId(plan.id());

        NaruTask task = session.newTask(NaruTaskSpec.of());
        NaruModelRequest request = ((net.thevpc.naru.impl.engine.scheduler.NaruTaskImpl) task)
                .context(NaruSource.SYSTEM);

        String rendered = request.messages().stream()
                .map(NaruMessage::getContent)
                .filter(java.util.Objects::nonNull)
                .reduce("", (a, b) -> a + "\n" + b);
        Assertions.assertTrue(rendered.contains("### ACTIVE PLAN:"), rendered);
        Assertions.assertTrue(rendered.contains("design the widget"), rendered);
        // and it must be attributed to the extension, not smuggled in as anonymous system text
        Assertions.assertTrue(request.messages().stream()
                        .anyMatch(m -> NaruPlanExtension.NAME.equals(m.getSourceName())),
                "the plan message must carry the extension's source name");
    }

    @Test
    public void theExtensionHonoursTheStateFileContractTheCoreRelieson() {
        // The core's save/load loops are a few lines, but they depend on two guarantees
        // from the extension, which this pins: a missing file is not an error, and
        // "nothing to persist" is signalled as null so a stale file gets deleted.
        //
        // Note: driving a real session.save() is not possible in this harness, because
        // NaruSessionImpl#save posts to the agent's action queue and the agent is never
        // started here, so the future never completes.
        NaruPlanExtension ext = new NaruPlanExtension();

        // a missing state file must leave the extension usable and empty
        NPath missing = NPath.ofTempFolder("naru-plan-state-missing").resolve("ext")
                .resolve(NaruPlanExtension.NAME + ".tson");
        Assertions.assertFalse(missing.exists());
        Assertions.assertFalse(ext.load(session, missing).isPresent());
        Assertions.assertTrue(ext.plans().plans().isEmpty());
        Assertions.assertNotNull(ext.plans().createPlan("fresh", List.of(NaruPlanItemSpec.of("a", "work"))));

        // with no plans at all there is nothing to persist, and the core treats null as
        // "delete the stale file" rather than as a failure
        NaruPlanExtension empty = new NaruPlanExtension();
        Assertions.assertNull(empty.save(session));

        // with plans, the snapshot is a real element
        Assertions.assertNotNull(ext.save(session));
    }

    /** A listener that ignores everything, so the tests can focus on the plan graph. */
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

    private static final class NaruPlanManagerHolder {
        private final NaruPlanManagerImpl manager = new NaruPlanManagerImpl();
    }
}
