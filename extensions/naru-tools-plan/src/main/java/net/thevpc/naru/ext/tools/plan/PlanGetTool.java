package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;

import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.util.NOptional;

import java.util.List;
import java.util.Map;

public class PlanGetTool extends DefaultNaruTool {

    public PlanGetTool() {
        super("plan_get", new String[]{NaruPlanToolTagProvider.PLAN_TAG});
    }

    @Override
    public String getDescription(NaruTask task) {
        return "Read a plan with its item statuses and dependencies. "
                + "Defaults to the active plan; pass plan_id (or 'all') to inspect another one. "
                + "Use this to re-check the plan before deciding what to do next.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("plan_id",
                        "Plan id or prefix to read, or 'all' to list every plan. Defaults to the active plan.", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        NaruTask task = context.task();
        String ref = context.stringArg("plan_id").onBlankEmpty().orNull();
        if ("all".equalsIgnoreCase(ref)) {
            return listAll(task);
        }
        NOptional<NaruPlan> planOpt = ref == null
                ? NaruPlanExtension.plans(task.session()).activePlan()
                : NaruPlanExtension.plans(task.session()).findPlan(ref);
        if (planOpt.isError()) {
            return "ERROR: " + planOpt.toString();
        }
        NaruPlan plan = planOpt.orNull();
        if (plan == null) {
            return "No active plan. " + listAll(task);
        }
        return "Plan " + plan.id() + " [" + plan.status().name().toLowerCase() + "]:\n" + plan.render();
    }

    private String listAll(NaruTask task) {
        Map<String, NaruPlan> plans = NaruPlanExtension.plans(task.session()).plans();
        if (plans.isEmpty()) {
            return "No plans exist yet.";
        }
        String active = NaruPlanExtension.plans(task.session()).activePlanId();
        StringBuilder sb = new StringBuilder("Plans:\n");
        for (NaruPlan p : plans.values()) {
            int done = 0;
            for (NaruPlanItem i : p.items()) {
                if (i.status() == NaruPlanItemStatus.DONE) {
                    done++;
                }
            }
            sb.append("  ").append(p.id()).append(p.id().equals(active) ? " (active)" : "").append(' ')
                    .append(p.status().name().toLowerCase()).append(' ')
                    .append(done).append('/').append(p.items().size()).append(" - ").append(p.goal()).append('\n');
        }
        return sb.toString();
    }
}
