package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.mode.NaruPromptMode.ModeIntent;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.plan.NaruPlanException;
import net.thevpc.naru.api.plan.NaruPlanItemSnapshot;
import net.thevpc.naru.api.plan.NaruPlanItemState;
import net.thevpc.naru.api.plan.NaruPlanReader;
import net.thevpc.naru.api.plan.NaruPlanService;
import net.thevpc.naru.api.plan.NaruPlanSnapshot;
import net.thevpc.naru.api.plan.NaruPlanState;
import net.thevpc.naru.api.registry.NaruSessionExtension;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Makes the planning feature available to the session, without the core knowing that
 * plans exist.
 * <p>
 * The extension owns the plan graph, contributes it to task prompts, and persists it
 * under the session's own {@code ext/} folder. Removing {@code naru-tools-plan} from the
 * classpath removes planning entirely; nothing in {@code naru-api} or {@code naru-impl}
 * refers to it.
 */
public class NaruPlanExtension implements NaruSessionExtension, NaruPlanService {

    public static final String NAME = "plan";

    /**
     * Tells the model how to interact with the plan, given the tools it has. Kept here
     * rather than in a core prompt mode so the instructions disappear with the feature.
     */
    private static final String PROGRESS_RULES =
            "Report progress with plan_update(item, status, notes) as you start, block on, or finish work. "
                    + "You cannot mark an item done yourself: completion goes through the item's validator, "
                    + "so finish the work and let the validator judge it. The plan's shape is fixed for this "
                    + "session; if it turns out to be wrong, say so in your report and let the user re-plan.";

    private final NaruPlanManagerImpl plans = new NaruPlanManagerImpl();

    @Override
    public String name() {
        return "plan";
    }

    @Override
    public Set<NaruSource> sources() {
        return EnumSet.of(NaruSource.SYSTEM);
    }

    /**
     * The plan graph, shared with the tools and directives of this extension.
     */
    public NaruPlanManager plans() {
        return plans;
    }

    @Override
    public List<NaruPlanSnapshot> allPlans() {
        String activeId = plans.activePlanId();
        List<NaruPlanSnapshot> out = new ArrayList<>();
        for (NaruPlan p : plans.plans().values()) {
            out.add(snapshot(p, p.id().equals(activeId)));
        }
        return out;
    }

    @Override
    public NaruPlanSnapshot activePlan() {
        NaruPlan p = plans.activePlan().orNull();
        return p == null ? null : snapshot(p, true);
    }

    @Override
    public NaruPlanSnapshot createPlan(String goal) {
        String text = requireText(goal, "a plan needs a goal");
        try {
            NaruPlan plan = plans.createPlan(text, new ArrayList<>());
            return snapshot(plan, plan.id().equals(plans.activePlanId()));
        } catch (IllegalArgumentException e) {
            throw new NaruPlanException(messageOf(e, "the plan could not be created"), e);
        }
    }

    @Override
    public NaruPlanSnapshot setGoal(String planId, String goal) {
        String text = requireText(goal, "a plan needs a goal, not a blank one");
        NaruPlan plan = plan(planId);
        NaruPlan changed = orFail(plans.setGoal(plan.id(), text),
                "plan " + plan.id() + " kept its old goal");
        return snapshot(changed, changed.id().equals(plans.activePlanId()));
    }

    @Override
    public NaruPlanSnapshot addItem(String planId, String description) {
        String text = requireText(description, "an item needs a description");
        NaruPlan plan = plan(planId);
        NaruPlan changed = orFail(plans.addItems(plan.id(), List.of(NaruPlanItemSpec.of(text))),
                "plan " + plan.id() + " rejected that item");
        return snapshot(changed, changed.id().equals(plans.activePlanId()));
    }

    @Override
    public NaruPlanSnapshot editItem(String planId, String itemId, String description) {
        String text = requireText(description, "an item needs a description");
        NaruPlan plan = plan(planId);
        orFail(plans.editItem(plan.id(), itemId, NaruPlanItemPatch.of().description(text)),
                "item " + itemId + " could not be edited");
        return snapshot(plan, plan.id().equals(plans.activePlanId()));
    }

    @Override
    public NaruPlanSnapshot removeItem(String planId, String itemId) {
        NaruPlan plan = plan(planId);
        NaruPlan changed = orFail(plans.removeItem(plan.id(), itemId),
                "item " + itemId + " is still in the plan");
        return snapshot(changed, changed.id().equals(plans.activePlanId()));
    }

    @Override
    public NaruPlanSnapshot activate(String planId) {
        NaruPlan plan = plan(planId);
        plans.setActivePlanId(plan.id());
        return snapshot(plan, true);
    }

    @Override
    public void deactivate() {
        plans.setActivePlanId(null);
    }

    @Override
    public void deletePlan(String planId) {
        NaruPlan plan = plan(planId);
        if (!plans.removePlan(plan.id())) {
            throw new NaruPlanException("plan " + planId + " is no longer there");
        }
    }

    private NaruPlan plan(String planId) {
        return orFail(plans.findPlan(planId), "no plan " + planId);
    }

    /**
     * A write the graph refused. The manager already phrases the reason for a reader, so it is
     * carried through instead of being replaced by a generic "edit failed".
     */
    private static <T> T orFail(NOptional<T> result, String fallback) {
        if (result.isError() || !result.isPresent()) {
            java.util.function.Supplier<NMsg> message = result.message();
            NMsg msg = message == null ? null : message.get();
            String reason = msg == null ? null : msg.toString();
            throw new NaruPlanException(reason == null || reason.isBlank() ? fallback : reason);
        }
        return result.orNull();
    }

    private static String requireText(String text, String complaint) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            throw new NaruPlanException(complaint);
        }
        return flat;
    }

    private static String messageOf(RuntimeException e, String fallback) {
        return e.getMessage() == null || e.getMessage().isBlank() ? fallback : e.getMessage();
    }

    /**
     * Copy the live graph into immutable API values.
     *
     * <p>The enums cross by name, and that is deliberate: {@code NaruPlanItemState} declares exactly
     * what {@link NaruPlanItemStatus} declares, so a host reads the same seven states the tools
     * write. An item is copied rather than referenced for the same reason the record is — a plan is
     * mutated by the task running it, and a panel repainting on the EDT must not walk that.
     */
    private static NaruPlanSnapshot snapshot(NaruPlan p, boolean active) {
        List<NaruPlanItemSnapshot> items = new ArrayList<>();
        for (NaruPlanItem i : p.items()) {
            List<String> deps = new ArrayList<>();
            for (String depId : i.dependsOn()) {
                deps.add(labelOf(p, depId));
            }
            items.add(new NaruPlanItemSnapshot(i.id(), labelOf(p, i.id()), i.description(),
                    NaruPlanItemState.valueOf(i.status().name()), deps,
                    i.notes(), i.taskId(), i.attempts()));
        }
        return new NaruPlanSnapshot(p.id(), p.goal(), NaruPlanState.valueOf(p.status().name()),
                items, active);
    }

    /** The declaration key if the plan has one, otherwise the short id prefix {@link #contribute} prints. */
    private static String labelOf(NaruPlan p, String itemId) {
        String key = p.keyOf(itemId);
        if (key != null) {
            return key;
        }
        return itemId == null ? "" : itemId.substring(0, Math.min(8, itemId.length()));
    }

    /**
     * The session's plan graph, for the tools and directives of this feature.
     * <p>
     * The lookup cannot fail in practice: these tools ship in the same jar as the
     * extension that provides the graph, and both are registered together.
     */
    public static NaruPlanManager plans(NaruSession session) {
        return session.registry().extension(NAME, NaruPlanExtension.class)
                .map(NaruPlanExtension::plans)
                .orElseThrow(() -> new IllegalStateException(
                        "the plan extension is not installed in this session"));
    }

    public static NMsg colorizePlanStatus(NaruPlanStatus status) {
        if (status == null) {
            return null;
        }
        switch (status) {
            case OPEN:
                return NMsg.ofStyledComments(status.name().toLowerCase());
            case BLOCKED:
                return NMsg.ofStyledError(status.name().toLowerCase());
            case COMPLETED:
                return NMsg.ofStyledSuccess(status.name().toLowerCase());
            case PENDING:
                return NMsg.ofStyledPale(status.name().toLowerCase());
        }
        return NMsg.ofC("%s", status.name().toLowerCase());
    }

    public static String trimStr(String any,int max) {
        if(any.length()>max){
            return any.substring(0,max-3)+"...";
        }
        return any;
    }

    public static NMsg colorizePlanItemStatus(NaruPlanItemStatus status) {
        if (status == null) {
            return null;
        }
        switch (status) {
            case READY:
                return NMsg.ofStyledPrimary1(status.name().toLowerCase());
            case RUNNING:
                return NMsg.ofStyledPrimary2(status.name().toLowerCase());
            case FAILED:
                return NMsg.ofStyledFail(status.name().toLowerCase());
            case VALIDATING:
                return NMsg.ofStyledWarn(status.name().toLowerCase());
            case BLOCKED:
                return NMsg.ofStyledError(status.name().toLowerCase());
            case DONE:
                return NMsg.ofStyledSuccess(status.name().toLowerCase());
            case PENDING:
                return NMsg.ofStyledPale(status.name().toLowerCase());
        }
        return NMsg.ofC("%s", status.name().toLowerCase());
    }

    @Override
    public boolean isRelevant(NaruTask task) {
        return true;
    }

    /**
     * Whether the mode declares itself to exist for carrying out decided work.
     *
     * <p>Read from {@link NaruPromptMode#modeIntent()} rather than inferred from
     * {@link NaruPromptMode#acceptToolTags(Set)}: the default mode accepts every tag
     * without meaning to build anything, so tool permissions cannot carry intent.
     */
    private static boolean isExecutingMode(NaruPromptMode mode) {
        return mode != null && mode.modeIntent() == ModeIntent.EXECUTING;
    }

    /**
     * Plans that still have work to do, in creation order. The set a human can still
     * choose to run, which is why every "which plan did you mean" path filters on it.
     */
    public static List<NaruPlan> unfinishedPlans(NaruSession session) {
        List<NaruPlan> out = new ArrayList<>();
        for (NaruPlan p : plans(session).plans().values()) {
            if (p.status() != NaruPlanStatus.COMPLETED) {
                out.add(p);
            }
        }
        return out;
    }

    @Override
    public void onModeChanged(NaruTask task, NaruPromptMode old, NaruPromptMode now) {
        if (!isExecutingMode(now) || isExecutingMode(old)) {
            // only entering an executing mode counts, and re-entering one that was
            // already active changes nothing: the user did not just decide to build
            return;
        }
        // The user just said "go implement". If a plan was waiting to be activated and
        // there is no doubt about which one it is, run it rather than making them
        // type /plan activate as well: activation exists to stop a model holding the
        // plan tag from starting work on its own, and a human mode switch is exactly
        // that consent.
        if (plans.activePlanId() != null) {
            return;
        }
        List<NaruPlan> unfinished = unfinishedPlans(task.session());
        if (unfinished.size() > 1) {
            task.addHistory(NaruMessage.engine(NMsg.ofC(
                    "there are unfinished plans; activate the one you mean with /plan activate <id>")));
            return;
        }
        if (unfinished.isEmpty()) {
            return;
        }
        NaruPlan candidate = unfinished.get(0);
        plans.setActivePlanId(candidate.id());
        task.addHistory(NaruMessage.engine( NMsg.ofC(
                "activated plan %s (%s) since you switched to %s mode",
                candidate.id(), candidate.goal(), now.name())));
    }

    @Override
    public List<NaruMessage> contribute(NaruTask task) {
        NaruPlan plan = plans.activePlan().orNull();
        if (plan == null || plan.status() == NaruPlanStatus.COMPLETED) {
            return Collections.emptyList();
        }
        return List.of(NaruMessage.system(
                "### ACTIVE PLAN:\n" + plan.render().filteredText() + "\n" + PROGRESS_RULES));
    }

    @Override
    public void load(NaruSession session, NElement state) {
        plans.loadFrom(state);
    }

    @Override
    public NElement save(NaruSession session) {
        return plans.toElement();
    }

    @Override
    public void close() {
        plans.clear();
    }
}
