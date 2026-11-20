package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.routine.NaruStmtResultType;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The human write path: {@code /plan goal|add|edit|remove}.
 *
 * <p>These are tested through the directive rather than the manager because the directive is the
 * only thing that turns free text into a graph edit — the parse is where a description containing
 * {@code --dry-run} would be lost, and the guard messages a desktop dialog shows to the user are
 * asserted here too.
 */
public class NaruPlanEditDirectiveTest {

    private NaruSession session;
    private NaruTask task;

    @BeforeAll
    public static void requireNuts() {
        Nuts.require();
    }

    @BeforeEach
    public void setUp() {
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(NPath.ofTempFolder("naru-plan-edit"));
        session = agent.newSession()
                .sessionDir(agent.projectDirectory())
                .interaction(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
        task = session.newTask(NaruTaskSpec.of());
    }

    private NaruPlanManager plans() {
        return NaruPlanExtension.plans(session);
    }

    private NaruStmtResult plan(String argument) {
        NaruDirective directive = session.registry().findDirective("plan")
                .orElseThrow(() -> new AssertionError("no /plan directive"));
        NaruDirectiveCallContext ctx = new NaruDirectiveCallContextImpl("plan", argument, task);
        return directive.execute(ctx);
    }

    private static boolean isErr(NaruStmtResult r) {
        return r.type() == NaruStmtResultType.ERROR;
    }

    private static String err(NaruStmtResult r) {
        return String.valueOf(r.errorValue());
    }

    /** A one-item plan, activated — every edit verb needs an active plan to act on. */
    private NaruPlan activePlan(String key, String description) {
        NaruPlan plan = plans().createPlan("goal", List.of(NaruPlanItemSpec.of(key, description)));
        plans().setActivePlanId(plan.id());
        return plan;
    }

    @Test
    public void goalRestatesTheActivePlan() {
        NaruPlan plan = activePlan("a", "first");
        NaruStmtResult r = plan("goal ship the cockpit with a rail");
        assertTrue(!isErr(r), err(r));
        assertEquals("ship the cockpit with a rail", plans().findPlan(plan.id()).orNull().goal());
    }

    @Test
    public void goalRefusesWhenNoPlanIsSelected() {
        plans().createPlan("goal", List.of(NaruPlanItemSpec.of("a", "first")));
        NaruStmtResult r = plan("goal rename it");
        assertTrue(isErr(r));
        assertTrue(err(r).contains("no active plan"), err(r));
    }

    @Test
    public void addAppendsAnItemGatedOnAnExistingKey() {
        NaruPlan plan = activePlan("a", "design it");
        NaruStmtResult r = plan("add build it --after=a");
        assertTrue(!isErr(r), err(r));
        List<NaruPlanItem> items = plans().findPlan(plan.id()).orNull().items();
        assertEquals(2, items.size());
        assertEquals(List.of(items.get(0).id()), items.get(1).dependsOn());
        // the gate is what the readiness pass is for: the new item waits, the first does not
        assertEquals(NaruPlanItemStatus.READY, items.get(0).status());
        assertEquals(NaruPlanItemStatus.PENDING, items.get(1).status());
    }

    @Test
    public void addRejectsAnUnknownDependency() {
        NaruPlan plan = activePlan("a", "design it");
        NaruStmtResult r = plan("add build it --after=zzz");
        assertTrue(isErr(r));
        assertTrue(err(r).contains("no item matching"), err(r));
        assertEquals(1, plan.items().size());
    }

    @Test
    public void aQuotedDescriptionKeepsItsDashesAndItsSpaces() {
        // the desktop composes these lines from a text field, so "--dry-run" inside the user's
        // sentence must arrive as prose and not as an option the parser swallowed
        NaruPlan plan = activePlan("a", "design it");
        NaruStmtResult r = plan("add \"support --dry-run in the shell tool\"");
        assertTrue(!isErr(r), err(r));
        List<NaruPlanItem> items = plans().findPlan(plan.id()).orNull().items();
        assertEquals("support --dry-run in the shell tool", items.get(items.size() - 1).description());
    }

    @Test
    public void editRewritesTheDescriptionAndTheNoteSeparately() {
        NaruPlan plan = activePlan("a", "design it");
        String itemId = plan.items().get(0).id();
        NaruStmtResult r = plan("edit " + itemId + " design it twice, carefully");
        assertTrue(!isErr(r), err(r));
        assertEquals("design it twice, carefully", plans().findItem(plan.id(), itemId).orNull().description());

        NaruStmtResult note = plan("note " + itemId + " \"asked the reviewer, twice\"");
        assertTrue(!note.isError(), err(note));
        NaruPlanItem item = plans().findItem(plan.id(), itemId).orNull();
        assertEquals("asked the reviewer, twice", item.notes());
        assertEquals("design it twice, carefully", item.description());

        assertTrue(!plan("edit " + itemId + " --no-notes").isError());
        assertNullNote(plans().findItem(plan.id(), itemId).orNull());
    }

    private static void assertNullNote(NaruPlanItem item) {
        assertTrue(item.notes() == null, "expected the note cleared, got " + item.notes());
    }

    @Test
    public void aQuotedOptionValueIsOneArgument() {
        // NCmdLine used to reject --opt="a b" outright; now that the BASH tokenizer quotes inside a
        // word, the documented /plan grammar works as written
        NaruPlan plan = activePlan("a", "design it");
        String itemId = plan.items().get(0).id();
        NaruStmtResult r = plan("edit " + itemId + " --notes=\"asked the reviewer, twice\"");
        assertTrue(!r.isError(), err(r));
        assertEquals("asked the reviewer, twice", plans().findItem(plan.id(), itemId).orNull().notes());
    }

    @Test
    public void editWithNothingMentionedIsAnErrorNotASilentNoOp() {        NaruPlan plan = activePlan("a", "design it");
        NaruStmtResult r = plan("edit " + plan.items().get(0).id());
        assertTrue(isErr(r));
        assertTrue(err(r).contains("nothing to change"), err(r));
    }

    @Test
    public void editCannotRewriteTextUnderARunningTask() {
        NaruPlan plan = activePlan("a", "design it");
        String itemId = plan.items().get(0).id();
        plans().reportItem(plan.id(), itemId, NaruPlanItemStatus.RUNNING, "working");
        NaruStmtResult r = plan("edit " + itemId + " actually, build it");
        assertTrue(isErr(r));
        assertTrue(err(r).contains("running"), err(r));
        assertEquals("design it", plans().findItem(plan.id(), itemId).orNull().description());
        // the note is commentary, not the instruction, so it still lands while the task runs
        assertTrue(!plan("note " + itemId + " halfway").isError());
        assertEquals("halfway", plans().findItem(plan.id(), itemId).orNull().notes());
    }

    @Test
    public void removeDropsTheItemAndUnblocksItsDependent() {
        NaruPlan plan = plans().createPlan("goal", List.of(
                NaruPlanItemSpec.of("a", "gate"),
                NaruPlanItemSpec.of("b", "waits").dependsOn("a")));
        plans().setActivePlanId(plan.id());
        NaruStmtResult r = plan("remove a");
        assertTrue(!isErr(r), err(r));
        NaruPlan after = plans().findPlan(plan.id()).orNull();
        assertEquals(1, after.items().size());
        assertEquals("waits", after.items().get(0).description());
        assertEquals(NaruPlanItemStatus.READY, after.items().get(0).status());
        assertTrue(after.items().get(0).dependsOn().isEmpty());
    }

    @Test
    public void removeRefusesWhileATaskHoldsTheItem() {
        NaruPlan plan = activePlan("a", "design it");
        String itemId = plan.items().get(0).id();
        plans().reportItem(plan.id(), itemId, NaruPlanItemStatus.RUNNING, "working");
        NaruStmtResult r = plan("remove " + itemId);
        assertTrue(isErr(r));
        assertTrue(err(r).contains("running"), err(r));
        assertEquals(1, plan.items().size());
    }

    @Test
    public void createMakesAPlanHoldingOnlyItsGoalAndLeavesItDormant() {
        NaruStmtResult r = plan("create migrate the build");
        assertTrue(!isErr(r), err(r));
        List<NaruPlan> all = List.copyOf(plans().plans().values());
        assertEquals(1, all.size());
        assertEquals("migrate the build", all.get(0).goal());
        assertTrue(all.get(0).items().isEmpty(), "the plan starts with no items");
        assertTrue(plans().activePlanId() == null, "a created plan stays dormant");
    }

    @Test
    public void createWithNoGoalIsAnError() {
        NaruStmtResult r = plan("create");
        assertTrue(isErr(r));
        assertTrue(err(r).contains("usage"), err(r));
        assertTrue(plans().plans().isEmpty());
    }

    // ── naming a plan other than the active one ───────────────────────────────

    @Test
    public void goalTargetsANamedPlanWithoutActivatingIt() {
        NaruPlan active = activePlan("a", "the active one");
        NaruPlan other = plans().createPlan("other goal", List.of(NaruPlanItemSpec.of("b", "other item")));
        NaruStmtResult r = plan("goal --plan=" + other.id() + " renamed other");
        assertTrue(!isErr(r), err(r));
        assertEquals("renamed other", plans().findPlan(other.id()).orNull().goal());
        assertEquals("goal", plans().findPlan(active.id()).orNull().goal(), "the active plan is untouched");
        assertEquals(active.id(), plans().activePlanId(), "and it stays the active one");
    }

    @Test
    public void addTargetsANamedPlan() {
        NaruPlan active = activePlan("a", "the active one");
        NaruPlan other = plans().createPlan("other goal", List.of());
        NaruStmtResult r = plan("add --plan=" + other.id() + " an item for the other plan");
        assertTrue(!isErr(r), err(r));
        assertEquals(1, plans().findPlan(other.id()).orNull().items().size());
        assertEquals(1, plans().findPlan(active.id()).orNull().items().size(), "the active plan kept its one item");
        assertEquals(active.id(), plans().activePlanId());
    }

    @Test
    public void editAndRemoveTargetANamedPlan() {
        NaruPlan active = activePlan("a", "the active one");
        NaruPlan other = plans().createPlan("other goal", List.of(NaruPlanItemSpec.of("b", "other item")));
        String itemId = other.items().get(0).id();
        assertTrue(!plan("edit --plan=" + other.id() + " " + itemId + " rewritten elsewhere").isError());
        assertEquals("rewritten elsewhere", plans().findItem(other.id(), itemId).orNull().description());
        assertTrue(!plan("remove --plan=" + other.id() + " " + itemId).isError());
        assertTrue(plans().findPlan(other.id()).orNull().items().isEmpty());
        // the active plan was never the target, so its own item is untouched
        List<NaruPlanItem> activeItems = plans().findPlan(active.id()).orNull().items();
        assertEquals(1, activeItems.size());
        assertEquals("the active one", activeItems.get(0).description());
        assertEquals(NaruPlanItemStatus.READY, activeItems.get(0).status());
    }

    @Test
    public void anUnknownNamedPlanIsAnError() {
        activePlan("a", "the active one");
        NaruStmtResult r = plan("goal --plan=zzzz renamed");
        assertTrue(isErr(r));
        assertTrue(err(r).contains("no plan matching"), err(r));
    }

    @Test
    public void anItemPrefixIsEnoughToAddressIt() {
        NaruPlan plan = activePlan("a", "design it");
        String itemId = plan.items().get(0).id();
        String prefix = itemId.substring(0, 8);
        assertNotNull(plan.findItemByPrefix(prefix));
        assertTrue(!plan("edit " + prefix + " renamed by prefix").isError());
        assertEquals("renamed by prefix", plans().findItem(plan.id(), itemId).orNull().description());
    }
}
