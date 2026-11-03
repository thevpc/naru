package net.thevpc.naru.api.plan;

/**
 * Someone wants to be told when the session's plans changed underneath it.
 *
 * <p>A plan is not only written by the host that displays it. A running task reports progress
 * through the {@code plan_update} tool, a directive adds an item, a mode switch activates a plan,
 * and a readiness pass moves an item from {@code PENDING} to {@code READY} — none of which the
 * panel asked for. Without this, a board only shows what it happened to read last, which reads as
 * the plan being ignored.
 *
 * <p>The event carries no payload on purpose. The graph is live state another thread may be
 * mutating, so the honest signal is "read it again" — a listener re-queries
 * {@link NaruPlanReader#allPlans()} rather than being handed a structure that may change while it
 * is being drawn. Hosts are expected to coalesce: several events in one turn should cost one read.
 */
@FunctionalInterface
public interface NaruPlanListener {

    /**
     * Some part of the plan set changed. Called on whichever thread made the change, almost never
     * the event dispatch thread, so a listener that touches a widget must move to the EDT itself.
     */
    void plansChanged();
}
