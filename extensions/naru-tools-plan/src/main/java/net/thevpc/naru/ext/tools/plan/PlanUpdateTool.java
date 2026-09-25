package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.plan.NaruPlan;
import net.thevpc.naru.api.plan.NaruPlanItem;
import net.thevpc.naru.api.plan.NaruPlanItemStatus;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.util.NOptional;

import java.util.Arrays;

/**
 * Reports progress on a plan item.
 *
 * <p>This tool cannot mark an item {@code done} or {@code ready}. Completion runs
 * through the item's validator, so an executor cannot vouch for its own output, and
 * readiness is derived from the dependency graph rather than declared. The manager
 * rejects both attempts; the enum below simply does not offer them.
 */
public class PlanUpdateTool extends DefaultNaruTool {

    public PlanUpdateTool() {
        super("plan_update", new String[]{NaruToolTags.PLAN});
    }

    @Override
    public String getDescription(NaruTask task) {
        return "Report progress on one item of the active plan. Use 'running' when you start it, "
                + "'blocked' if you cannot proceed (always say why in notes), "
                + "'validating' when your work is finished and awaiting review. "
                + "You cannot mark an item done: completion is decided by the item's validator. "
                + "Item ids are shown as 8-character prefixes; a unique prefix is enough.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("item", "Item id, or a unique prefix of it", true).build(),
                NaruToolParameter.string("status", "New status: running, validating or blocked", true)
                        .enumValues(Arrays.asList("running", "validating", "blocked")).build(),
                NaruToolParameter.string("notes", "Progress notes, or the reason you are blocked", false).build(),
                NaruToolParameter.string("plan_id", "Optional: plan id (defaults to the active plan)", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        NaruTask task = context.task();
        String itemRef = context.stringArg("item").onBlankEmpty().orNull();
        String statusStr = context.stringArg("status").onBlankEmpty().orNull();
        String notes = context.stringArg("notes").onBlankEmpty().orNull();
        String planRef = context.stringArg("plan_id").onBlankEmpty().orNull();
        if (itemRef == null || statusStr == null) {
            return "ERROR: item and status are required";
        }
        NaruPlanItemStatus status = NaruPlanItemStatus.parse(statusStr).orNull();
        if (status == null) {
            return "ERROR: invalid status '" + statusStr + "' (use running, validating or blocked)";
        }
        NOptional<NaruPlan> planOpt = planRef == null
                ? task.session().planManager().activePlan()
                : task.session().planManager().findPlan(planRef);
        if (planOpt.isError()) {
            return "ERROR: " + planOpt.toString();
        }
        NaruPlan plan = planOpt.orNull();
        if (plan == null) {
            return "ERROR: no active plan; create one with plan_create or pass plan_id";
        }
        NaruPlanItem item = plan.findItemByPrefix(itemRef);
        if (item == null) {
            return "ERROR: no item matching '" + itemRef + "' in plan " + plan.id();
        }
        NOptional<NaruPlanItem> updated =
                task.session().planManager().reportItem(plan.id(), item.id(), status, notes);
        if (updated.isError()) {
            return "ERROR: " + updated.toString();
        }
        if (!updated.isPresent()) {
            return "ERROR: item " + item.id() + " not found in plan " + plan.id();
        }
        return plan.render();
    }
}
