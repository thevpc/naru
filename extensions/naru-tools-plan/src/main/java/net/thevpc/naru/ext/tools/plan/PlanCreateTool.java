package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.plan.NaruPlan;
import net.thevpc.naru.api.plan.NaruPlanItemSpec;
import net.thevpc.naru.api.plan.NaruPlanValidatorKind;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NArrayElement;
import net.thevpc.nuts.elem.NElement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Creates a new execution plan as a dependency graph.
 *
 * <p>Items reference each other by local {@code key}, not by id, because ids are
 * assigned here. Keys are optional but a keyed item is the only kind another item can
 * depend on.
 */
public class PlanCreateTool extends DefaultNaruTool {

    public PlanCreateTool() {
        super("plan_create", new String[]{NaruToolTags.PLAN});
    }

    @Override
    public String getDescription(NaruTask task) {
        return "Create a new execution plan as a dependency graph. "
                + "Give each item a short 'key' when other items must wait for it, and list those keys in 'dependsOn'. "
                + "Items with no unmet dependency start ready; the rest become ready automatically as their dependencies finish. "
                + "A plan is not executed until a human activates it. "
                + "Attach a validator (model_review or user_approval) to any item whose output must be judged before it counts as done.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        NaruToolParameter itemSchema = NaruToolParameter.object("item", "A single plan item", true,
                NaruToolParameter.string("description", "What this item must accomplish", true).build(),
                NaruToolParameter.string("key", "Short local name other items can reference in dependsOn", false).build(),
                NaruToolParameter.array("dependsOn",
                        "Keys of the items that must finish before this one can start", false,
                        NaruToolParameter.string("depends_on", "Key of a preceding item", true).build()).build(),
                NaruToolParameter.string("validator",
                        "Gate that must pass before this item counts as done", false)
                        .enumValues(Arrays.asList("none", "model_review", "user_approval")).build()
        ).build();
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("goal", "Overall goal of the plan", true).build(),
                NaruToolParameter.array("items", "The items of the plan", true, itemSchema).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String goal = context.stringArg("goal").onBlankEmpty().orNull();
        if (goal == null) {
            return "ERROR: goal is required";
        }
        List<NaruPlanItemSpec> specs = new ArrayList<>();
        NElement arr = context.arg("items").map(NElement::of).orNull();
        if (arr == null || !arr.isAnyArray()) {
            return "ERROR: items is required and must be an array";
        }
        for (NElement el : arr.asArray().get()) {
            NObjectLike o = NObjectLike.of(el);
            if (o == null) {
                continue;
            }
            String description = o.string("description");
            if (description == null) {
                continue;
            }
            NaruPlanItemSpec spec = NaruPlanItemSpec.of(description);
            String key = o.string("key");
            if (key != null) {
                spec.key(key);
            }
            List<String> deps = o.stringList("dependsOn");
            if (!deps.isEmpty()) {
                spec.dependsOn(deps);
            }
            String validator = o.string("validator");
            if (validator != null) {
                NaruPlanValidatorKind kind = NaruPlanValidatorKind.parse(validator).orNull();
                if (kind == null) {
                    return "ERROR: invalid validator '" + validator + "' (use none, model_review or user_approval)";
                }
                spec.validator(kind);
            }
            specs.add(spec);
        }
        if (specs.isEmpty()) {
            return "ERROR: at least one item with a description is required";
        }
        try {
            NaruPlan plan = context.task().session().planManager().createPlan(goal, specs);
            return "Plan created (id " + plan.id() + "):\n" + plan.render()
                    + "\nNothing runs until a human activates it.";
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Minimal reader for the loosely typed {@code items} array. The tool-call payload
     * is untyped JSON, so every field is read defensively.
     */
    static final class NObjectLike {
        private final NElement element;

        private NObjectLike(NElement element) {
            this.element = element;
        }

        static NObjectLike of(NElement el) {
            NObjectLike r = new NObjectLike(el);
            return r.element.isAnyObject() ? r : null;
        }

        String string(String name) {
            String s = element.asObject().flatMap(o -> o.getStringValue(name)).orNull();
            return (s == null || s.isBlank()) ? null : s.trim();
        }

        List<String> stringList(String name) {
            List<String> out = new ArrayList<>();
            NArrayElement a = element.asObject().flatMap(o -> o.getArray(name)).orNull();
            if (a != null) {
                for (NElement e : a.children()) {
                    String s = e.asStringValue().orNull();
                    if (s != null && !s.isBlank()) {
                        out.add(s.trim());
                    }
                }
            }
            return out;
        }
    }
}
