package net.thevpc.naru.ext.tools.plan;



import net.thevpc.nuts.elem.NArrayElement;
import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementFormatterStyle;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link NaruPlanManager}. Plans live in memory, in creation order, and are
 * persisted as a single TSON file inside the session folder (written by
 * {@code NaruSessionImpl}).
 */
public class NaruPlanManagerImpl implements NaruPlanManager {

    private final Map<String, NaruPlan> plans = new LinkedHashMap<>();
    private volatile String activePlanId;
    /**
     * Hosts that display the graph. A plan changes without the panel asking — a task reports
     * progress, a directive adds an item, a mode switch activates a plan — and a board that only
     * reads on its own events shows a plan that is no longer true.
     */
    private final List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public NaruPlan createPlan(String goal, List<NaruPlanItemSpec> items) {
        return createPlan(goal, null, items);
    }

    @Override
    public NaruPlan createPlan(String goal, String description, List<NaruPlanItemSpec> items) {
        NaruPlan plan = new NaruPlan(goal);
        plan.setDescription(description);
        plan.addItems(items == null ? new ArrayList<>() : items);
        plans.put(plan.id(), plan);
        // the graph is built with every item PENDING; the first readiness pass is what
        // makes the roots of the plan runnable
        recompute(plan.id());
        changed();
        return plan;
    }

    @Override
    public NOptional<NaruPlan> findPlan(String planId) {
        NaruPlan p = planId == null ? null : plans.get(planId);
        if (p == null && planId != null && !planId.isBlank()) {
            // plans are printed as 8-character prefixes, so accept a unique prefix the
            // same way item lookup does. An ambiguous prefix resolves to nothing rather
            // than to an arbitrary plan.
            String q = planId.trim();
            NaruPlan match = null;
            for (NaruPlan candidate : plans.values()) {
                if (candidate.id().startsWith(q)) {
                    if (match != null) {
                        match = null;
                        break;
                    }
                    match = candidate;
                }
            }
            p = match;
        }
        return p == null
                ? NOptional.ofNamedEmpty(NMsg.ofC("plan %s", planId))
                : NOptional.of(p);
    }

    @Override
    public Map<String, NaruPlan> plans() {
        return new LinkedHashMap<>(plans);
    }

    @Override
    public NOptional<NaruPlanItem> findItem(String planId, String itemId) {
        NOptional<NaruPlan> foundPlan = findPlan(planId);
        if (!foundPlan.isPresent()) {
            return NOptional.ofNamedEmpty(NMsg.ofC("plan %s", planId));
        }
        NaruPlanItem item = itemId == null ? null : foundPlan.orNull().findItemByPrefix(itemId);
        return item == null
                ? NOptional.ofNamedEmpty(NMsg.ofC("item %s of plan %s", itemId, planId))
                : NOptional.of(item);
    }

    @Override
    public NOptional<NaruPlan> addItems(String planId, List<NaruPlanItemSpec> items) {
        NaruPlan plan = planId == null ? null : plans.get(planId);
        if (plan == null) {
            return NOptional.ofNamedEmpty(NMsg.ofC("plan %s", planId));
        }
        try {
            plan.addItems(items == null ? new ArrayList<>() : items);
        } catch (IllegalArgumentException e) {
            return NOptional.ofNamedError(NMsg.ofC("cannot add items to plan %s: %s", planId, e.getMessage()));
        }
        recompute(planId);
        changed();
        return NOptional.of(plan);
    }

    @Override
    public NOptional<NaruPlan> setGoal(String planId, String goal) {
        NOptional<NaruPlan> found = findPlan(planId);
        if (!found.isPresent()) {
            return NOptional.ofNamedEmpty(NMsg.ofC("plan %s", planId));
        }
        if (goal == null || goal.isBlank()) {
            return NOptional.ofNamedError(NMsg.ofC("plan %s needs a goal, not a blank one", planId));
        }
        NaruPlan plan = found.orNull();
        plan.setGoal(goal.trim());
        plan.touch();
        changed();
        return NOptional.of(plan);
    }

    @Override
    public NOptional<NaruPlan> setDescription(String planId, String description) {
        NOptional<NaruPlan> found = findPlan(planId);
        if (!found.isPresent()) {
            return NOptional.ofNamedEmpty(NMsg.ofC("plan %s", planId));
        }
        NaruPlan plan = found.orNull();
        // blank clears; anything else is the reader's own Markdown and is stored as written, apart
        // from the padding around it. Flattening it here would turn a document into a sentence.
        plan.setDescription(description == null || description.isBlank() ? null : description.strip());
        plan.touch();
        changed();
        return NOptional.of(plan);
    }

    @Override
    public NOptional<NaruPlan> renameItem(String planId, String itemId, String key) {
        NOptional<NaruPlan> found = findPlan(planId);
        if (!found.isPresent()) {
            return NOptional.ofNamedEmpty(NMsg.ofC("plan %s", planId));
        }
        NaruPlan plan = found.orNull();
        NaruPlanItem item = itemId == null ? null : plan.findItemByPrefix(itemId);
        if (item == null) {
            return NOptional.ofNamedEmpty(NMsg.ofC("item %s of plan %s", itemId, planId));
        }
        if (key != null && !key.isBlank() && !isSingleWordKey(key.trim())) {
            return NOptional.ofNamedError(NMsg.ofC(
                    "'%s' is not a usable item key: a key is one word, so later items can point at "
                            + "it with --after=%s", key.trim(), key.trim()));
        }
        if (!plan.renameKey(item.id(), key)) {
            return NOptional.ofNamedError(NMsg.ofC("plan %s already has an item keyed '%s'",
                    plan.id(), key == null ? "" : key.trim()));
        }
        plan.touch();
        changed();
        return NOptional.of(plan);
    }

    /**
     * A declaration key is the handle later items and {@code /plan add --after=} quote, so it has to
     * survive being typed into a command line: one word, no whitespace.
     */
    private static boolean isSingleWordKey(String key) {
        return key.matches("[A-Za-z0-9._-]+");
    }

    @Override
    public NOptional<NaruPlan> moveItem(String planId, String itemId, int newIndex) {
        NOptional<NaruPlan> found = findPlan(planId);
        if (!found.isPresent()) {
            return NOptional.ofNamedEmpty(NMsg.ofC("plan %s", planId));
        }
        NaruPlan plan = found.orNull();
        NaruPlanItem item = itemId == null ? null : plan.findItemByPrefix(itemId);
        if (item == null) {
            return NOptional.ofNamedEmpty(NMsg.ofC("item %s of plan %s", itemId, planId));
        }
        if (newIndex < 0 || newIndex >= plan.items().size()) {
            return NOptional.ofNamedError(NMsg.ofC("position %d is outside plan %s, which has %d items",
                    newIndex, plan.id(), plan.items().size()));
        }
        // order decides which ready item a dispatcher offers first, so a running item may still be
        // moved: its text and edges are untouched, only its place in the reading order changes
        plan.moveItem(item.id(), newIndex);
        plan.touch();
        changed();
        return NOptional.of(plan);
    }

    @Override
    public NOptional<NaruPlanItem> editItem(String planId, String itemId, NaruPlanItemPatch patch) {        NOptional<NaruPlan> foundPlan = findPlan(planId);
        if (!foundPlan.isPresent()) {
            return NOptional.ofNamedEmpty(NMsg.ofC("plan %s", planId));
        }
        NaruPlan plan = foundPlan.orNull();
        NaruPlanItem item = itemId == null ? null : plan.findItemByPrefix(itemId);
        if (item == null) {
            return NOptional.ofNamedEmpty(NMsg.ofC("item %s of plan %s", itemId, planId));
        }
        if (patch == null || patch.isEmpty()) {
            return NOptional.ofNamedError(NMsg.ofC("nothing to change on item %s", itemId));
        }
        if (patch.description().isSet()) {
            String description = patch.description().value();
            if (description == null || description.isBlank()) {
                return NOptional.ofNamedError(NMsg.ofC(
                        "item %s needs a description; remove it with /plan remove instead", item.id()));
            }
            NaruPlanItemStatus status = item.status();
            if (status == NaruPlanItemStatus.RUNNING || status == NaruPlanItemStatus.VALIDATING) {
                return NOptional.ofNamedError(NMsg.ofC(
                        "item %s is %s; its text is the instruction a task is working from, so stop that task "
                                + "before rewriting it", item.id(), status.name().toLowerCase()));
            }
            item.setDescription(description.trim());
        }
        if (patch.notes().isSet()) {
            // setNotes maps blank to null on its own, so an explicit clear needs no branch here
            item.setNotes(patch.notes().value());
        }
        if (patch.validator().isSet()) {
            item.setValidator(patch.validator().value());
        }
        plan.touch();
        recompute(plan.id());
        changed();
        return NOptional.of(item);
    }

    @Override
    public NOptional<NaruPlan> removeItem(String planId, String itemId) {
        NOptional<NaruPlan> found = findPlan(planId);
        if (!found.isPresent()) {
            return NOptional.ofNamedEmpty(NMsg.ofC("plan %s", planId));
        }
        NaruPlan plan = found.orNull();
        NaruPlanItem item = itemId == null ? null : plan.findItemByPrefix(itemId);
        if (item == null) {
            return NOptional.ofNamedEmpty(NMsg.ofC("item %s of plan %s", itemId, planId));
        }
        NaruPlanItemStatus status = item.status();
        if (status == NaruPlanItemStatus.RUNNING || status == NaruPlanItemStatus.VALIDATING) {
            return NOptional.ofNamedError(NMsg.ofC(
                    "item %s is %s and a task is reporting against it; stop that task first",
                    item.id(), status.name().toLowerCase()));
        }
        // removeItem already drops the inbound edges and touches; the pass afterwards is what
        // lets a dependent become READY now that its gate is gone
        plan.removeItem(item.id());
        recompute(plan.id());
        changed();
        return NOptional.of(plan);
    }

    @Override
    public NOptional<NaruPlanItem> reportItem(String planId, String itemId, NaruPlanItemStatus status, String notes) {
        NOptional<NaruPlanItem> found = findItem(planId, itemId);
        if (found.isError()) {
            return found;
        }
        if (!found.isPresent()) {
            return NOptional.ofNamedEmpty(NMsg.ofC("item %s of plan %s", itemId, planId));
        }
        if (status == null) {
            return NOptional.ofNamedError(NMsg.ofC("no status given for item %s", itemId));
        }
        if (status == NaruPlanItemStatus.DONE) {
            return NOptional.ofNamedError(NMsg.ofC(
                    "item %s cannot be marked done directly; report the validator outcome instead", itemId));
        }
        if (status == NaruPlanItemStatus.READY) {
            return NOptional.ofNamedError(NMsg.ofC(
                    "item %s cannot be marked ready directly; readiness is derived from dependencies", itemId));
        }
        NaruPlanItem item = found.orNull();
        if (item.status().isTerminal() && status != NaruPlanItemStatus.FAILED) {
            return NOptional.ofNamedError(NMsg.ofC(
                    "item %s is already %s", itemId, item.status().name().toLowerCase()));
        }
        item.setStatus(status);
        if (notes != null) {
            item.setNotes(notes);
        }
        plan(planId).touch();
        recompute(planId);
        changed();
        return NOptional.of(item);
    }

    @Override
    public NOptional<NaruPlanItem> completeItem(String planId, String itemId, boolean validationPassed, String notes) {
        NOptional<NaruPlanItem> found = findItem(planId, itemId);
        if (found.isError()) {
            return found;
        }
        if (!found.isPresent()) {
            return NOptional.ofNamedEmpty(NMsg.ofC("item %s of plan %s", itemId, planId));
        }
        NaruPlanItem item = found.orNull();
        // a failed gate lands on FAILED either way; the distinction only matters for the
        // note, which the caller supplies
        item.setStatus(validationPassed ? NaruPlanItemStatus.DONE : NaruPlanItemStatus.FAILED);
        if (notes != null) {
            item.setNotes(notes);
        }
        plan(planId).touch();
        recompute(planId);
        changed();
        return NOptional.of(item);
    }

    @Override
    public NOptional<List<NaruPlanItem>> reopenItem(String planId, String itemId, boolean cascade) {
        NaruPlan plan = planId == null ? null : plans.get(planId);
        if (plan == null) {
            return NOptional.ofNamedEmpty(NMsg.ofC("plan %s", planId));
        }
        NaruPlanItem item = itemId == null ? null : plan.item(itemId);
        if (item == null) {
            return NOptional.ofNamedEmpty(NMsg.ofC("item %s of plan %s", itemId, planId));
        }
        List<NaruPlanItem> demoted = new ArrayList<>();
        demote(plan, item, cascade, demoted);
        plan.touch();
        recompute(planId);
        changed();
        return NOptional.of(demoted);
    }

    /**
     * Demotes a finished item and, when cascading, everything that transitively
     * depended on it. A non-terminal item is left alone: un-starting live work is the
     * caller's job (it has to cancel the item's task first), and silently resetting a
     * running item's bookkeeping would desynchronise it from the task doing the work.
     */
    private void demote(NaruPlan plan, NaruPlanItem item, boolean cascade, List<NaruPlanItem> demoted) {
        if (!item.status().isTerminal()) {
            return;
        }
        item.setStatus(NaruPlanItemStatus.PENDING);
        item.setTaskId(null);
        demoted.add(item);
        if (!cascade) {
            return;
        }
        for (NaruPlanItem s : plan.items()) {
            if (s.dependsOn(item.id()) && !demoted.contains(s)) {
                demote(plan, s, true, demoted);
            }
        }
    }

    @Override
    public NOptional<NaruPlan> recomputeAndFill(String planId) {
        NOptional<NaruPlan> result = recompute(planId);
        changed();
        return result;
    }

    /**
     * The readiness pass, without announcing it. Every mutator below finishes with one of these, so
     * an internal pass must not fire its own event on top of the write that caused it — a board would
     * otherwise read the graph twice for one click.
     */
    private NOptional<NaruPlan> recompute(String planId) {
        NaruPlan plan = planId == null ? null : plans.get(planId);
        if (plan == null) {
            return NOptional.ofNamedEmpty(NMsg.ofC("plan %s", planId));
        }
        boolean moved = false;
        for (NaruPlanItem s : plan.items()) {
            if (s.status().isTerminal() || s.status() == NaruPlanItemStatus.RUNNING
                    || s.status() == NaruPlanItemStatus.VALIDATING) {
                continue;
            }
            boolean satisfied = dependenciesSatisfied(plan, s);
            NaruPlanItemStatus want = satisfied ? NaruPlanItemStatus.READY : NaruPlanItemStatus.PENDING;
            if (s.status() != want) {
                s.setStatus(want);
                moved = true;
            }
        }
        if (moved) {
            plan.touch();
        }
        return NOptional.of(plan);
    }

    // ── change announcements (the seam NaruPlanExtension forwards to its hosts) ──

    /** Announce the graph's new shape to a host that displays it. */
    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /**
     * Tell everyone that the graph moved. A listener that throws is a display bug in whoever
     * registered it and must not undo the write that already landed, so its failure stops here.
     */
    private void changed() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // the plan is written; a broken panel is not a reason to lose that
            }
        }
    }

    private boolean dependenciesSatisfied(NaruPlan plan, NaruPlanItem s) {
        for (String dep : s.dependsOn()) {
            NaruPlanItem target = plan.item(dep);
            if (target == null || target.status() != NaruPlanItemStatus.DONE) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean removePlan(String planId) {
        boolean removed = planId != null && plans.remove(planId) != null;
        if (removed && planId.equals(activePlanId)) {
            activePlanId = null;
        }
        if (removed) {
            changed();
        }
        return removed;
    }

    @Override
    public NOptional<NaruPlan> activePlan() {
        return findPlan(activePlanId);
    }

    @Override
    public String activePlanId() {
        return activePlanId;
    }

    @Override
    public void setActivePlanId(String planId) {
        if (planId == null) {
            activePlanId = null;
        } else if (plans.containsKey(planId)) {
            activePlanId = planId;
        } else {
            throw new IllegalArgumentException("unknown plan " + planId);
        }
        changed();
    }

    private NaruPlan plan(String planId) {
        return plans.get(planId);
    }

    /**
     * Drops every plan and the active-plan pointer. Used when a session ends.
     */
    public void clear() {
        plans.clear();
        activePlanId = null;
        listeners.clear();
    }

    // ── persistence (driven by NaruPlanExtension, via the session extension SPI) ──

    /**
     * Serialises every plan plus the active-plan pointer. Returns null when there is
     * nothing worth persisting, which tells the core to remove any stale state file.
     */
    public NElement toElement() {
        if (plans.isEmpty()) {
            return null;
        }
        NObjectElementBuilder b = NElement.ofObjectBuilder();
        b.set("schemaVersion", NaruPlan.SCHEMA_VERSION);
        NArrayElementBuilder arr = NArrayElementBuilder.of();
        for (NaruPlan p : plans.values()) {
            arr.add(p.toElement());
        }
        b.set("plans", arr.build());
        if (activePlanId != null) {
            b.set("activePlanId", activePlanId);
        }
        return b.build();
    }

    /**
     * Replaces the in-memory state with whatever the state element holds. Null state is
     * not an error: it just means this session has no plans yet.
     */
    public void loadFrom(NElement state) {
        plans.clear();
        activePlanId = null;
        if (state == null) {
            return;
        }
        try {
            NObjectElement o = state.asObject().get();
            List<NaruPlan> loaded = new ArrayList<>();
            NArrayElement arr = o.getArray("plans").orNull();
            if (arr != null) {
                for (NElement el : arr.children()) {
                    NObjectElement po = el.asObject().orNull();
                    if (po == null) {
                        continue;
                    }
                    // a plan from a pre-DAG build has 'steps' and no 'id'; it cannot be
                    // represented any more, so skip it instead of loading a broken graph
                    if (po.getStringValue("id").isEmpty() || po.getArray("items").isEmpty()) {
                        continue;
                    }
                    try {
                        NaruPlan p = new NaruPlan(el);
                        p.assertAcyclic();
                        loaded.add(p);
                    } catch (RuntimeException ex) {
                        // one corrupt plan must not cost the user every other plan
                    }
                }
            }
            for (NaruPlan p : loaded) {
                plans.put(p.id(), p);
            }
            String act = o.getStringValue("activePlanId").orNull();
            if (act != null && plans.containsKey(act)) {
                activePlanId = act;
            }
            // a board that was open while the session restored must hear that the graph it read
            // is gone and a different one arrived
            changed();
        } catch (Exception ex) {
            throw new NIllegalArgumentException(NMsg.ofC("failed to load plans: %s", ex.getMessage(), ex));
        }
    }
}
