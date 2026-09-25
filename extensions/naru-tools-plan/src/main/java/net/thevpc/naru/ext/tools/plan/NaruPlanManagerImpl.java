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

    @Override
    public NaruPlan createPlan(String goal, List<NaruPlanItemSpec> items) {
        NaruPlan plan = new NaruPlan(goal);
        plan.addItems(items == null ? new ArrayList<>() : items);
        plans.put(plan.id(), plan);
        // the graph is built with every item PENDING; the first readiness pass is what
        // makes the roots of the plan runnable
        recomputeAndFill(plan.id());
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
        recomputeAndFill(planId);
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
        recomputeAndFill(planId);
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
        recomputeAndFill(planId);
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
        recomputeAndFill(planId);
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
        NaruPlan plan = planId == null ? null : plans.get(planId);
        if (plan == null) {
            return NOptional.ofNamedEmpty(NMsg.ofC("plan %s", planId));
        }
        boolean changed = false;
        for (NaruPlanItem s : plan.items()) {
            if (s.status().isTerminal() || s.status() == NaruPlanItemStatus.RUNNING
                    || s.status() == NaruPlanItemStatus.VALIDATING) {
                continue;
            }
            boolean satisfied = dependenciesSatisfied(plan, s);
            NaruPlanItemStatus want = satisfied ? NaruPlanItemStatus.READY : NaruPlanItemStatus.PENDING;
            if (s.status() != want) {
                s.setStatus(want);
                changed = true;
            }
        }
        if (changed) {
            plan.touch();
        }
        return NOptional.of(plan);
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
        if (planId != null && planId.equals(activePlanId)) {
            activePlanId = null;
        }
        return planId != null && plans.remove(planId) != null;
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
     * Replaces the in-memory state with whatever the file holds. A missing file is not
     * an error: it just means this session has no plans yet.
     */
    public void loadFrom(NPath file) {
        plans.clear();
        activePlanId = null;
        if (file == null || !file.exists()) {
            return;
        }
        try {
            NElement e = NElementReader.ofTson().ntf(false).read(file);
            NObjectElement o = e.asObject().get();
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
        } catch (Exception ex) {
            throw new NIllegalArgumentException(NMsg.ofC("failed to load plans from %s: %s", file, ex.getMessage(), ex));
        }
    }
}
