package net.thevpc.naru.api.plan;

import java.util.List;

/**
 * A plan as a host sees it: its goal, its derived state, and its items in declaration order.
 *
 * <p>Immutable and free of extension types on purpose. The plan graph is live data the plan
 * extension mutates while a task runs, so a host reading it off the EDT would otherwise be walking
 * a structure nobody is synchronising. A snapshot is taken on the caller's request and cannot change
 * underneath it.
 *
 * @param id     the plan's id
 * @param goal   what the plan is for
 * @param state  the aggregate state, derived from the items
 * @param items  the items, in the order they were declared
 * @param active whether this is the plan the session is currently working through
 */
public record NaruPlanSnapshot(String id, String goal, NaruPlanState state,
                               List<NaruPlanItemSnapshot> items, boolean active) {

    /** Items that reached {@code DONE}. */
    public int doneCount() {
        int n = 0;
        for (NaruPlanItemSnapshot i : items) {
            if (i.state() == NaruPlanItemState.DONE) {
                n++;
            }
        }
        return n;
    }

    /** {@code 3/7}, the same count {@code /plan list} prints. */
    public String progressLabel() {
        return doneCount() + "/" + items.size();
    }
}
