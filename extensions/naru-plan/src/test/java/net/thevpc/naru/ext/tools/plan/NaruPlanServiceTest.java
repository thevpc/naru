package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.plan.NaruPlanException;
import net.thevpc.naru.api.plan.NaruPlanReader;
import net.thevpc.naru.api.plan.NaruPlanService;
import net.thevpc.naru.api.plan.NaruPlanSnapshot;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The writable seam a host uses to shape plans.
 *
 * <p>The point of these tests is that writes and reads are one implementation: a plan created
 * through {@link NaruPlanService} is immediately visible through {@link NaruPlanReader#allPlans()},
 * not written into a second instance or dropped on the floor between a panel and the graph. The
 * extension's directive tests cover the command parser; this covers the path a desktop uses when it
 * does not go through that parser at all.
 */
class NaruPlanServiceTest {

    private NaruSession session;

    @BeforeAll
    static void setUpWorkspace() {
        Nuts.require();
    }

    @BeforeEach
    void setUp() {
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(NPath.ofTempFolder("naru-plan-service"));
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

    @Test
    void theServiceAndTheReaderAreTheSameExtension() {
        NaruPlanService service = NaruPlanService.of(session);
        assertNotNull(service);
        assertSame(service, NaruPlanReader.of(session));
        assertEquals(NaruPlanReader.EXTENSION, service.name());
    }

    @Test
    void everyWriteIsVisibleThroughTheReader() {
        NaruPlanService service = NaruPlanService.of(session);
        NaruPlanReader reader = NaruPlanReader.of(session);

        NaruPlanSnapshot plan = service.createPlan("ship the cockpit");
        assertEquals("ship the cockpit", plan.goal());
        assertTrue(plan.items().isEmpty());
        assertEquals(1, reader.allPlans().size(), "the created plan is immediately readable");

        plan = service.addItem(plan.id(), "write the rail");
        assertEquals(1, plan.items().size());
        String itemId = plan.items().get(0).id();
        assertEquals("write the rail", reader.allPlans().get(0).items().get(0).description());

        plan = service.editItem(plan.id(), itemId, "write the rail, then test it");
        assertEquals("write the rail, then test it", plan.items().get(0).description());
        assertEquals("write the rail, then test it",
                reader.allPlans().get(0).items().get(0).description());

        plan = service.setGoal(plan.id(), "ship the cockpit and the rail");
        assertEquals("ship the cockpit and the rail", plan.goal());
        assertEquals("ship the cockpit and the rail", reader.allPlans().get(0).goal());

        plan = service.activate(plan.id());
        assertTrue(plan.active());
        assertNotNull(reader.activePlan());
        assertEquals(plan.id(), reader.activePlan().id());

        service.deactivate();
        assertNull(reader.activePlan());

        plan = service.removeItem(plan.id(), itemId);
        assertTrue(plan.items().isEmpty());
        assertTrue(reader.allPlans().get(0).items().isEmpty());

        service.deletePlan(plan.id());
        assertTrue(reader.allPlans().isEmpty());
    }

    @Test
    void refusedWritesCarryTheManagersReason() {
        NaruPlanService service = NaruPlanService.of(session);
        NaruPlanSnapshot plan = service.createPlan("a plan");

        assertThrows(NaruPlanException.class, () -> service.createPlan("   "));
        assertThrows(NaruPlanException.class, () -> service.setGoal(plan.id(), ""));
        assertThrows(NaruPlanException.class, () -> service.setGoal("missing-plan", "a goal"));
        assertThrows(NaruPlanException.class, () -> service.addItem("missing-plan", "an item"));
        assertThrows(NaruPlanException.class,
                () -> service.editItem(plan.id(), "missing-item", "new text"));
        assertThrows(NaruPlanException.class, () -> service.removeItem(plan.id(), "missing-item"));
        assertThrows(NaruPlanException.class, () -> service.deletePlan("missing-plan"));

        // a refused call must not leave a partial plan behind
        assertEquals(1, service.allPlans().size());
        assertFalse(service.allPlans().get(0).goal().isBlank());
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
