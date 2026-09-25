package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.model.NaruMessage;
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
     * messages have their source and source name applied by the core.
     */
    default List<NaruMessage> contribute(NaruTask task) {
        return Collections.emptyList();
    }

    // ── durable state: <sessionFolder>/ext/<name>.tson ────────────────────────

    /**
     * Restores state from {@code file}, which may not exist. Returning
     * {@link NOptional#empty()} leaves the extension at its initial state. Called once
     * per session load, before {@link #open(NaruSession)}.
     */
    default NOptional<NElement> load(NaruSession session, NPath file) {
        return NOptional.ofNamedEmpty(NMsg.ofC("session extension '%s' has no persisted state", name()));
    }

    /**
     * Snapshots state for persistence. Returning null writes nothing, and causes any
     * stale state file to be deleted.
     * <p>
     * This is called far more often than a user-initiated save (session env changes
     * trigger a snapshot), so it must be cheap and idempotent.
     */
    default NElement save(NaruSession session) {
        return null;
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    /** Called after {@link #load(NaruSession)} and before any task runs. */
    default void open(NaruSession session) {
    }

    /** Called when the session is terminated. */
    default void close() {
    }
}
