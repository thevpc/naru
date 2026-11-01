package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.project.NaruSetting;
import net.thevpc.nuts.util.NOptional;

import java.util.List;
import java.util.Map;

/**
 * Manages durable execution plans for a session.
 *
 * <p>Plans persist with the session (as {@code plans.tson}) and survive crashes and
 * reloads. A plan is addressed by its own id, never by the id of the task that
 * happens to be working on it, so that the architect task, the executor tasks and the
 * validator tasks can all reference the same plan.
 *
 * <p>This interface deliberately contains no scheduling policy. Deciding which ready
 * item runs next, how many run at once, and when a plan is activated are all
 * responsibilities of the layer above; the manager only owns the graph, its derived
 * readiness, and the rules that keep a caller from recording unvalidated work as
 * finished.
 */
public interface NaruPlanManager {

    /**
     * Creates a plan from declarative item specs and registers it under its generated id.
     *
     * @throws IllegalArgumentException if a dependency key is unknown or the graph
     *                                  would contain a cycle
     */
    NaruPlan createPlan(String goal, List<NaruPlanItemSpec> items);

    NOptional<NaruPlan> findPlan(String planId);

    /**
     * All plans, in creation order.
     */
    Map<String, NaruPlan> plans();

    NOptional<NaruPlanItem> findItem(String planId, String itemId);

    /**
     * Appends items to an existing plan, resolving dependency keys against existing
     * items and against keys declared in the same batch.
     *
     * @throws IllegalArgumentException if a key is unknown, duplicated, or the graph
     *                                  would contain a cycle
     */
    NOptional<NaruPlan> addItems(String planId, List<NaruPlanItemSpec> items);

    /**
     * Restate what a plan is for.
     *
     * <p>The goal is the one field a human is expected to correct after the plan is
     * written — the shape the model proposed was right, its sentence about the purpose was
     * not. A blank goal is refused rather than stored: {@code render()} and the desktop
     * board both fall back to a placeholder, so an empty one reads as damage.
     */
    NOptional<NaruPlan> setGoal(String planId, String goal);

    /**
     * Edit one item's text, note or validator.
     *
     * <p>Fields the patch leaves {@link NaruSetting#unset() unset} are not written. An
     * item whose task is {@link NaruPlanItemStatus#RUNNING} or
     * {@link NaruPlanItemStatus#VALIDATING} cannot have its description changed: the worker
     * was handed that sentence as its instruction, and rewriting it underneath the run
     * leaves the task doing work the plan no longer describes. Notes and validator may
     * still be edited while it runs.
     */
    NOptional<NaruPlanItem> editItem(String planId, String itemId, NaruPlanItemPatch patch);

    /**
     * Drop an item and every dependency edge that pointed at it, then recompute readiness
     * so its dependents unblock if it was their last gate.
     *
     * <p>Refused while a task holds the item, for the reason {@link #reopenItem} gives for
     * non-terminal items: un-starting live work is the caller's job, and deleting the item
     * the task is reporting against would desynchronise the two.
     */
    NOptional<NaruPlan> removeItem(String planId, String itemId);

    /**
     * Reports progress on an item.
     *
     * <p>{@link NaruPlanItemStatus#DONE} is rejected here on purpose: completion must
     * go through {@link #completeItem(String, String, boolean, String)} so that a
     * validator verdict always accompanies a finished item. Everything else the
     * caller may set freely.
     */
    NOptional<NaruPlanItem> reportItem(String planId, String itemId, NaruPlanItemStatus status, String notes);

    /**
     * Records the outcome of an item's validator.
     *
     * <p>A pass moves the item to {@link NaruPlanItemStatus#DONE}; a failure moves it to
     * {@link NaruPlanItemStatus#FAILED} with {@code notes} kept as the reason. An item
     * with no validator may be completed here too, but must pass {@code true}.
     */
    NOptional<NaruPlanItem> completeItem(String planId, String itemId, boolean validationPassed, String notes);

    /**
     * Reopens a finished item, demoting it to {@link NaruPlanItemStatus#PENDING} and
     * cascading the demotion to items that transitively depended on it.
     *
     * <p>This is the destructive operation the human confirmation in the directive is
     * for: cascading can undo validated work.
     *
     * @param cascade whether to demote dependents as well
     * @return the demoted items, in demotion order
     */
    NOptional<List<NaruPlanItem>> reopenItem(String planId, String itemId, boolean cascade);

    /**
     * Recomputes {@link NaruPlanItemStatus#READY} for every non-terminal item whose
     * dependencies are all {@link NaruPlanItemStatus#DONE}, and demotes items that are
     * {@code READY} but no longer satisfied.
     *
     * <p>Safe to call as often as you like; it never touches items that are running,
     * validating or terminal.
     */
    NOptional<NaruPlan> recomputeAndFill(String planId);

    boolean removePlan(String planId);

    /**
     * The plan currently marked for execution, if any. Activation is session state and
     * is independent of the prompt mode of any task; only a human directive sets it.
     */
    NOptional<NaruPlan> activePlan();

    String activePlanId();

    /**
     * Records which plan is activated. Passing {@code null} or an unknown id deactivates.
     */
    void setActivePlanId(String planId);
}
