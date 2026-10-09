package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.spawn.NaruSpawnContext;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.spi.NComponent;
import net.thevpc.nuts.util.NOptional;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A session-scoped feature that the core knows nothing about.
 * <p>
 * Exactly one instance is created per session, so an implementation may keep mutable
 * per-session state in instance fields. That single-instance guarantee is the reason
 * prompt contribution and durable state live on the same interface: a feature that both
 * renders a prompt and owns persisted state needs the <em>same</em> object to answer
 * both, and the registry creates providers once per lookup otherwise.
 * <p>
 * A session extension is optional. Removing its jar from the classpath removes the
 * feature, with no core change and no dangling references.
 */
public interface NaruSessionExtension extends NComponent {

    /**
     * Stable identifier. Used as the source name for contributed messages and as the
     * basename of this extension's state file, so it must be a safe file name and must
     * not change between releases without a migration.
     */
    String name();

    /**
     * Ascending contribution order. Built-in contributors sort first; extensions should
     * use 100 or more so their content appears after core content.
     */
    default int order() {
        return 100;
    }

    /**
     * The {@link NaruSource}s that must be enabled on a task for its contribution to be
     * considered. Defaults to {@link NaruSource#SYSTEM}, matching how a feature's
     * instructions are treated as system material.
     */
    default Set<NaruSource> sources() {
        return EnumSet.of(NaruSource.SYSTEM);
    }

    /**
     * The source stamped on contributed messages, so {@code /context} and {@code /stats}
     * can attribute them. Defaults to {@link NaruSource#SYSTEM}.
     */
    default NaruSource source() {
        return NaruSource.SYSTEM;
    }

    /**
     * Whether this extension has anything to say to this task. Called before
     * {@link #contribute(NaruTask)} so an inactive feature costs one predicate call.
     */
    default boolean isRelevant(NaruTask task) {
        return true;
    }

    /**
     * Messages to splice into the task's context. Evaluated on every call, so an
     * implementation must read current state rather than cache it. The returned
     * messages have their source applied by the core; a message that already carries a
     * source name keeps it, and one that does not is attributed to {@link #name()}.
     */
    default List<NaruMessage> contribute(NaruTask task) {
        return Collections.emptyList();
    }

    // ── reacting to task state ───────────────────────────────────────────────

    /**
     * Called after {@link NaruTask#promptMode(NaruPromptMode)} actually changes the mode
     * of a task, with the mode it had before.
     * <p>
     * This exists so a feature can react to a <em>human</em> mode switch without the
     * core (or another extension) knowing that the feature exists. A planning feature,
     * for instance, uses it to notice that the user has just switched to an executing
     * mode and to pick up the plan that is about to become runnable.
     * <p>
     * It is not fired when the mode is set to the value it already had, and not for
     * mode changes made by a child task inheriting its parent's mode. Implementations
     * must tolerate being called at any point in the session's life and must not throw:
     * a misbehaving extension may not break a mode switch.
     *
     * @param task the task whose mode changed
     * @param old  the mode in effect before the switch, never null
     * @param now  the mode now in effect, never null
     */
    default void onModeChanged(NaruTask task, NaruPromptMode old, NaruPromptMode now) {
    }

    /**
     * Called after a task reached a terminal state and was removed from the session.
     * <p>
     * An extension that keeps per-task state keyed by task id must drop that task's entry
     * here. Without it the state file accumulates an entry for every task the session ever
     * ran, and a later session load resurrects selections for tasks that no longer exist.
     * <p>
     * Must not throw: this runs inside the task's terminal status transition.
     *
     * @param session the session the task belonged to
     * @param taskId  the id that was just deregistered
     */
    default void onTaskDeregistered(NaruSession session, long taskId) {
    }

    // ── spawn ─────────────────────────────────────────────────────────────

    /**
     * Called while a task is being spawned, before anything is resolved, so an extension
     * can contribute spawn-kind defaults (tags, exclusions, env, skills, inherit kinds and
     * the context strategy) through the seed methods of {@link NaruSpawnContext}.
     * <p>
     * Applied in precedence order afterwards: extension/spawn-kind defaults → named policy
     * → call-site flags → contract validation. A broken extension must not break a spawn,
     * so the core swallows and logs any exception thrown here.
     *
     * @param context the spawn under construction; the parent, strategy, policy and
     *                contract are visible, the resolution is not yet computed
     */
    default void onSpawn(NaruSpawnContext context) {
    }

    /**
     * Called after a spawned child task was created, registered and its resolved seeds were
     * applied, with the same {@link NaruSpawnContext} now carrying the
     * {@code context.resolution()}: the resolved sets and the source of every item.
     * <p>
     * This is the post-creation counterpart of {@link #onSpawn(NaruSpawnContext)}: a
     * feature that needs the actual child task (to load skills onto it, to warn about a
     * skill/tag mismatch) does it here. Must not throw.
     *
     * @param session the session the task was spawned in
     * @param task    the child task, fully seeded
     * @param context the spawn context with the resolved plan
     */
    default void onSpawned(NaruSession session, NaruTask task, NaruSpawnContext context) {
    }

    // ── reacting to the request itself ──────────────────────────────────────

    /**
     * Called immediately before a model request is built, at a statement boundary with no
     * tool call in flight.
     *
     * <p>The seam for anything that has to happen while the request does not yet exist.
     * {@link #contribute(NaruTask)} can only add messages to a context that is being
     * assembled, which is enough to inject instructions but not enough to react to the
     * request's size -- by the time a caller can measure it, the messages are already
     * fixed. An implementation that needs to change which history items are sent, or to
     * compact them, does it here, mutating the task; the request is built from the task
     * immediately afterwards and so picks the change up.
     *
     * <p>Must not throw. A misbehaving extension may not turn a model call into an error,
     * and a feature that cannot act must leave the request exactly as it found it: the core
     * swallows any exception and continues.
     *
     * <p>Called once per model request, including the extra requests an agent loop makes
     * while working through tool calls. Each of those is a fresh opportunity to compact, and
     * each is a safe boundary.
     */
    default void beforeModelRequest(NaruTask task) {
    }

    // ── durable state ────────────────────────────────────────────────────────

    /**
     * Restores state previously returned by {@link #save(NaruSession)}.
     *
     * <p>{@code state} is null for a session that has never been saved, and for an
     * extension that had nothing to persist last time. Either way the extension starts at
     * its initial state: a state of null and a missing call are the same thing.
     *
     * <p>The state is handed over as a value rather than a location because extensions
     * must not depend on the store's layout. An extension that knew the file name could
     * not be moved to a different backend, and would have to reimplement whatever
     * durability the store provides.
     *
     * <p>Called once per session load, before {@link #open(NaruSession)}.
     */
    default void load(NaruSession session, NElement state) {
    }

    /**
     * Returns state for persistence, or null to persist nothing -- which also removes any
     * state saved earlier.
     *
     * <p>Called far more often than a user-initiated save (it runs on every persist, which
     * happens after every statement), so it must be cheap and idempotent.
     */
    default NElement save(NaruSession session) {
        return null;
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    /** Called after {@link #load(NaruSession)} and before any task runs. */
    default void open(NaruSession session) {
    }

    /**
     * Called once when the session starts serving, before any task runs (WP8). A feature
     * that owns session-wide defaults installs them here, or declares them in an init
     * script; the {@code session-start} event is fired alongside this callback.
     * <p>
     * Must not throw: a broken extension may not cost the user the session.
     */
    default void onSessionStart(NaruSession session) {
    }

    /**
     * Called after the session's project directory changed through {@code /project}, with
     * the old and new roots (WP7). A feature that snapshotted anything rooted at the
     * project -- skill roots, for instance -- re-resolves it here. Per-task selections and
     * grants are deliberately <em>not</em> reset: only availability is re-resolved, so a
     * loaded skill that disappeared is reported by the feature's own doctor rather than
     * silently unloaded.
     * <p>
     * Must not throw: a broken extension may not break the navigation.
     */
    default void onProjectChanged(NaruSession session, NPath oldProjectDir, NPath newProjectDir) {
    }

    /** Called when the session is terminated. */
    default void close() {
    }
}
