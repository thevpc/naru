package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.plan.NaruPlanException;
import net.thevpc.naru.api.plan.NaruPlanItemSnapshot;
import net.thevpc.naru.api.plan.NaruPlanListener;
import net.thevpc.naru.api.plan.NaruPlanReader;
import net.thevpc.naru.api.plan.NaruPlanService;
import net.thevpc.naru.api.plan.NaruPlanSnapshot;
import net.thevpc.naru.api.plan.NaruPlanItemState;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    // ── descriptions are documents, not sentences ─────────────────────────────

    @Test
    void aPlanDescriptionKeepsItsMarkdownAndReachesTheModel() {
        NaruPlanService service = NaruPlanService.of(session);
        NaruPlanSnapshot plan = service.createPlan("ship the cockpit",
                "# Why\n\nThe rail is unreadable.\n\n- fix the tally\n- keep the dots");
        assertEquals("# Why\n\nThe rail is unreadable.\n\n- fix the tally\n- keep the dots",
                service.allPlans().get(0).description(),
                "line breaks inside a description are its content, not whitespace to clean");

        // the point of storing it is that the run sees it, not that a panel can show it
        service.activate(plan.id());
        NaruPlan live = NaruPlanExtension.plans(session).findPlan(plan.id()).orNull();
        assertTrue(String.valueOf(live.render().filteredText()).contains("The rail is unreadable"),
                "the description belongs in the prompt the task is given");

        service.setDescription(plan.id(), "  \n  ");
        assertTrue(service.allPlans().get(0).description().isBlank(), "blank text clears it");
    }

    @Test
    void aDescriptionSurvivesTheSessionFile() {
        NaruPlanService service = NaruPlanService.of(session);
        NaruPlanExtension ext = session.registry()
                .extension(NaruPlanExtension.NAME, NaruPlanExtension.class).orNull();
        assertNotNull(ext);
        NaruPlanSnapshot plan = service.createPlan("goal", "## Notes\n\ncode:\n    keep me");
        String id = plan.id();
        NElement state = ext.save(session);

        service.deletePlan(id);
        assertTrue(service.allPlans().isEmpty(), "the board is empty before the restore");
        ext.load(session, state);

        NaruPlanSnapshot restored = service.allPlans().get(0);
        assertEquals(id, restored.id());
        assertEquals("## Notes\n\ncode:\n    keep me", restored.description(),
                "a description written by hand must come back exactly as it was typed");
    }

    @Test
    void anItemDescriptionKeepsItsLines() {
        NaruPlanService service = NaruPlanService.of(session);
        NaruPlanSnapshot plan = service.createPlan("a plan");
        plan = service.addItem(plan.id(), "run the suite\n\nexpected: 0 failures");
        assertEquals("run the suite\n\nexpected: 0 failures",
                plan.items().get(0).description(),
                "an item is the instruction a task works from, so its shape is part of it");
    }

    // ── renaming and reordering ───────────────────────────────────────────────

    @Test
    void renamingAnItemMovesTheHandleAndLeavesTheEdgesAlone() {
        NaruPlanService service = NaruPlanService.of(session);
        NaruPlanManager plans = NaruPlanExtension.plans(session);
        NaruPlan plan = plans.createPlan("a plan", List.of(
                NaruPlanItemSpec.of("a", "first"),
                NaruPlanItemSpec.of("b", "second").dependsOn("a")));
        String bId = plan.items().get(1).id();

        NaruPlanSnapshot renamed = service.renameItem(plan.id(), bId, "z");
        assertEquals("z", itemById(renamed, bId).key());
        assertEquals(List.of("a"), itemById(renamed, bId).dependsOnKeys(),
                "dependencies are held by item id, so a rename must not disturb them");

        // a key is a handle other items are authored against, so it has to stay unique and typeable
        assertThrows(NaruPlanException.class, () -> service.renameItem(plan.id(), bId, "a"));
        assertThrows(NaruPlanException.class, () -> service.renameItem(plan.id(), bId, "two words"));
        assertTrue(itemById(service.renameItem(plan.id(), bId, "  "), bId).key().length() > 0,
                "an empty key falls back to the id prefix rather than showing nothing");
    }

    @Test
    void movingAnItemChangesTheReadingOrderNotTheGraph() {
        NaruPlanService service = NaruPlanService.of(session);
        NaruPlanSnapshot plan = service.createPlan("a plan");
        for (String text : List.of("one", "two", "three")) {
            plan = service.addItem(plan.id(), text);
        }
        String first = plan.items().get(0).id();
        assertEquals(List.of("one", "two", "three"), descriptions(plan));

        plan = service.moveItem(plan.id(), first, 2);
        assertEquals(List.of("two", "three", "one"), descriptions(plan));
        assertEquals(first, plan.items().get(2).id(), "the same item, at the end");

        final NaruPlanSnapshot moved = plan;
        assertThrows(NaruPlanException.class, () -> service.moveItem(moved.id(), first, 3));
        assertThrows(NaruPlanException.class, () -> service.moveItem(moved.id(), first, -1));
    }

    // ── the board hears about changes it did not ask for ──────────────────────

    @Test
    void oneWriteAnnouncesItselfExactlyOnce() {
        NaruPlanService service = NaruPlanService.of(session);
        AtomicInteger events = new AtomicInteger();
        NaruPlanListener listener = events::incrementAndGet;
        service.addPlanListener(listener);

        NaruPlanSnapshot plan = service.createPlan("a plan");
        assertEquals(1, events.get(), "creating a plan is one change, not two for its readiness pass");
        int afterCreate = events.get();
        service.addItem(plan.id(), "an item");
        assertEquals(afterCreate + 1, events.get());
        service.activate(plan.id());
        assertEquals(afterCreate + 2, events.get());

        service.removePlanListener(listener);
        service.addItem(plan.id(), "another item");
        assertEquals(afterCreate + 2, events.get(), "a removed listener stops being called");
    }

    @Test
    void aTaskReportingProgressReachesTheListener() {
        // the case the panel could not see before: nobody in the UI asked for this change
        NaruPlanService service = NaruPlanService.of(session);
        NaruPlanSnapshot plan = service.createPlan("a plan");
        plan = service.addItem(plan.id(), "do the work");
        AtomicInteger events = new AtomicInteger();
        service.addPlanListener(events::incrementAndGet);

        String itemId = plan.items().get(0).id();
        NaruPlanManager plans = NaruPlanExtension.plans(session);
        plans.reportItem(plan.id(), itemId, NaruPlanItemStatus.RUNNING, "started");

        assertEquals(1, events.get(), "a running task's progress report must repaint the board");
        assertEquals(NaruPlanItemState.RUNNING, itemById(service.allPlans().get(0), itemId).state());
    }

    @Test
    void aListenerThatThrowsDoesNotUndoTheWrite() {
        NaruPlanService service = NaruPlanService.of(session);
        service.addPlanListener(() -> {
            throw new IllegalStateException("the panel is broken");
        });
        NaruPlanSnapshot plan = service.createPlan("still created");
        assertEquals(1, service.allPlans().size(),
                "a display bug is not a reason to lose the plan the user just made");
        assertNotNull(plan);
    }

    private static NaruPlanItemSnapshot itemById(NaruPlanSnapshot plan, String itemId) {
        for (NaruPlanItemSnapshot i : plan.items()) {
            if (i.id().equals(itemId)) {
                return i;
            }
        }
        throw new AssertionError("item " + itemId + " is not on the plan");
    }

    private static List<String> descriptions(NaruPlanSnapshot plan) {
        List<String> out = new ArrayList<>();
        for (NaruPlanItemSnapshot i : plan.items()) {
            out.add(i.description());
        }
        return out;
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
