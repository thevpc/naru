package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.plan.NaruPlanItemSnapshot;
import net.thevpc.naru.api.plan.NaruPlanItemState;
import net.thevpc.naru.api.plan.NaruPlanReader;
import net.thevpc.naru.api.plan.NaruPlanSnapshot;
import net.thevpc.naru.api.plan.NaruPlanState;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The read seam a host uses to show plans.
 *
 * <p>What matters here is what the host is promised: the registry lookup resolves, the snapshot
 * carries the short keys a human reads instead of UUIDs, and {@code activePlan()} is null rather
 * than an exception on a session that has never planned.
 */
class NaruPlanReaderTest {

    private NaruSession session;

    @BeforeAll
    static void setUpWorkspace() {
        Nuts.require();
    }

    @BeforeEach
    void setUp() {
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(NPath.ofTempFolder("naru-plan-reader"));
        session = agent.newSession()
                .sessionDir(agent.projectDirectory())
                .interaction(null)
                .sessionListener(NOOP_LISTENER)
                .directiveFilter(null).toolFilter(null).tagFilter(null)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private static NaruPlanItemSnapshot item(NaruPlanSnapshot s, String description) {
        for (NaruPlanItemSnapshot i : s.items()) {
            if (description.equals(i.description())) {
                return i;
            }
        }
        throw new AssertionError("no item " + description);
    }

    @Test
    void theExtensionAnswersTheRegistryQuery() {
        NaruPlanReader reader = NaruPlanReader.of(session);
        assertNotNull(reader);
        assertEquals(NaruPlanReader.EXTENSION, reader.name());
        // a session that has never planned has no active plan; that is a normal state, not a failure
        assertNull(reader.activePlan());
        assertTrue(reader.allPlans().isEmpty());
    }

    @Test
    void aSnapshotCarriesKeysNotUuids() {
        NaruPlan plan = NaruPlanExtension.plans(session).createPlan("ship the thing", List.of(
                NaruPlanItemSpec.of("a", "design"),
                NaruPlanItemSpec.of("b", "implement").dependsOn("a")));
        NaruPlanExtension.plans(session).setActivePlanId(plan.id());

        NaruPlanSnapshot s = NaruPlanReader.of(session).activePlan();
        assertNotNull(s);
        assertEquals(plan.id(), s.id());
        assertEquals("ship the thing", s.goal());
        assertEquals(NaruPlanState.OPEN, s.state());
        assertTrue(s.active());
        assertEquals(2, s.items().size());
        assertEquals(List.of(), item(s, "design").dependsOnKeys());
        assertEquals(List.of("a"), item(s, "implement").dependsOnKeys());
        assertEquals("a", item(s, "design").key());
        // readiness is derived: the root has nothing to wait for, the dependent still does
        assertEquals(NaruPlanItemState.READY, item(s, "design").state());
        assertEquals(NaruPlanItemState.PENDING, item(s, "implement").state());
    }

    @Test
    void progressCountsDoneItemsAndStateFollowsThem() {
        NaruPlan plan = NaruPlanExtension.plans(session).createPlan("two steps", List.of(
                NaruPlanItemSpec.of("a", "design"),
                NaruPlanItemSpec.of("b", "implement").dependsOn("a")));
        NaruPlanReader reader = NaruPlanReader.of(session);
        NaruPlanManager plans = NaruPlanExtension.plans(session);
        plans.setActivePlanId(plan.id());
        assertEquals("0/2", reader.activePlan().progressLabel());

        plans.completeItem(plan.id(), plan.items().get(0).id(), true, "drafted");
        NaruPlanSnapshot after = reader.activePlan();
        assertEquals("1/2", after.progressLabel());
        assertEquals(1, after.doneCount());
        assertEquals(NaruPlanItemState.DONE, item(after, "design").state());
        assertTrue(item(after, "design").notes().contains("drafted"));
        // finishing the gate unblocks the dependent, so the ready state crosses the seam too
        assertEquals(NaruPlanItemState.READY, item(after, "implement").state());

        plans.completeItem(plan.id(), plan.items().get(1).id(), true, "shipped");
        NaruPlanSnapshot done = reader.activePlan();
        assertEquals(NaruPlanState.COMPLETED, done.state());
        assertEquals("2/2", done.progressLabel());
    }

    @Test
    void aKeylessItemIsStillLabelled() {
        NaruPlan plan = NaruPlanExtension.plans(session).createPlan("no keys",
                List.of(NaruPlanItemSpec.of("just a description")));
        NaruPlanSnapshot s = NaruPlanReader.of(session).allPlans().get(0);
        NaruPlanItemSnapshot i = s.items().get(0);
        // an item authored without a key still gets the short handle /plan show prints
        assertFalse(i.key().isBlank());
        assertEquals(plan.items().get(0).id().substring(0, 8), i.key());
    }

    @Test
    void onlyOnePlanIsMarkedActive() {
        NaruPlanManager plans = NaruPlanExtension.plans(session);
        NaruPlan first = plans.createPlan("first", List.of(NaruPlanItemSpec.of("a", "one")));
        NaruPlan second = plans.createPlan("second", List.of(NaruPlanItemSpec.of("b", "two")));
        plans.setActivePlanId(second.id());

        List<NaruPlanSnapshot> all = NaruPlanReader.of(session).allPlans();
        assertEquals(2, all.size());
        assertEquals(first.id(), all.get(0).id());
        assertFalse(all.get(0).active());
        assertTrue(all.get(1).active());
        assertEquals("second", NaruPlanReader.of(session).activePlan().goal());
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
