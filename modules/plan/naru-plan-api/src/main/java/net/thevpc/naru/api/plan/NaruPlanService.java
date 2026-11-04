package net.thevpc.naru.api.plan;

import net.thevpc.naru.api.agent.NaruSession;

/**
 * The writable view of the session's plans, for a host that lets a reader shape them.
 *
 * <p>This is the same seam as {@link NaruPlanReader}, with the operations a plan board needs. It is
 * still implemented by the optional {@code naru-plan} extension and still looked up through the
 * registry, so the desktop does not compile against the extension jar. The extension remains the
 * only writer: it owns readiness, dependency edges, validators and the rule that a running item's
 * text belongs to its task. A host calls these methods instead of composing a {@code /plan} line,
 * so a click does not have to pass through the command parser to reach the graph.
 *
 * <p>Every call is synchronous and applies to the extension instance that answers the registry
 * query — the same instance {@link NaruPlanReader#allPlans()} reads. A host that calls from the EDT
 * is responsible for moving the call off it, because a plan can be touched by a running task at the
 * same time.
 *
 * <p>A refused write throws {@link NaruPlanException} with a message fit to show. The methods that
 * return a snapshot return the plan after the change, so a host can select or redraw it without
 * having to guess which plan it was.
 */
public interface NaruPlanService extends NaruPlanReader {

    /**
     * Create a plan holding only its goal. The plan starts dormant, exactly like
     * {@code /plan create}; activation is a separate, explicit choice.
     */
    NaruPlanSnapshot createPlan(String goal);

    /**
     * Create a plan with a goal and a long description. The description may be blank and is kept
     * verbatim: it is Markdown the reader writes, not a one-line field to flatten.
     */
    default NaruPlanSnapshot createPlan(String goal, String description) {
        NaruPlanSnapshot created = createPlan(goal);
        if (description == null || description.isBlank()) {
            return created;
        }
        return setDescription(created.id(), description);
    }

    /** Restate what a plan is for, without changing any item. */
    NaruPlanSnapshot setGoal(String planId, String goal);

    /**
     * Replace a plan's long description, or clear it with blank text. The text is Markdown and is
     * stored verbatim apart from trimming surrounding whitespace.
     */
    NaruPlanSnapshot setDescription(String planId, String description);

    /** Append one item to a plan. The item has no dependencies and no validator. */
    NaruPlanSnapshot addItem(String planId, String description);

    /** Rewrite one item's description. Refused while a task is running or validating it. */
    NaruPlanSnapshot editItem(String planId, String itemId, String description);

    /**
     * Rename an item's declaration key, the short handle shown beside it. An empty key clears it
     * and lets the item fall back to its id prefix. Dependencies are held by id and are unaffected.
     */
    NaruPlanSnapshot renameItem(String planId, String itemId, String key);

    /**
     * Move one item to a zero-based position in the plan's declaration order. Reordering changes
     * which ready item is offered first; it does not rewrite dependencies or any item's text.
     */
    NaruPlanSnapshot moveItem(String planId, String itemId, int newIndex);

    /** Drop one item and every dependency edge that pointed at it. */
    NaruPlanSnapshot removeItem(String planId, String itemId);

    /** Mark one plan active so its ready items may be dispatched. */
    NaruPlanSnapshot activate(String planId);

    /** Stop dispatching the active plan without discarding any progress. */
    void deactivate();

    /** Remove one plan and all of its items. */
    void deletePlan(String planId);

    /**
     * The session's writable plan view, or {@code null} when the {@code naru-plan} extension is not
     * installed. A host must not show editing controls without it.
     */
    static NaruPlanService of(NaruSession session) {
        if (session == null || session.registry() == null) {
            return null;
        }
        try {
            return session.registry().extension(EXTENSION, NaruPlanService.class).orNull();
        } catch (RuntimeException e) {
            // an extension that is present but half-initialised must not cost the host its session
            return null;
        }
    }
}
