package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.nuts.spi.NComponent;

/**
 * A feature that belongs to the <b>agent</b> (the project level), not to a session.
 *
 * <p>Exactly one instance is created per agent and retained for the agent's lifetime,
 * the same way {@link NaruSessionExtension} instances are retained per session: a
 * project-level feature owns state that outlives every session — a shared transaction
 * store, a price table — and creating it per-lookup would split that state.
 *
 * <p>The instance is created lazily, on the first {@code agent.extension(...)} lookup,
 * and {@link #open(NaruWorkspace)} is called once before it is handed out. There is no
 * {@code close}: the agent itself has no lifecycle end short of the process, so an
 * agent extension is process-scoped and its resources are released with the JVM. An
 * implementation that touches files must therefore re-check on every use that the
 * project directory is still the one it opened against.
 *
 * <p>An agent extension is optional. Removing its jar from the classpath removes the
 * feature with no core change.
 */
public interface NaruAgentExtension extends NComponent {

    /**
     * Stable identifier. Used to look the extension up and as the basename of its
     * on-disk files, so it must be a safe file name and must not change between
     * releases without a migration.
     */
    String name();

    /**
     * Brings the feature up, once, before any lookup returns it. The agent's project
     * directory may not be set yet when this runs (it is called on first lookup), so
     * an implementation that needs it should resolve it lazily rather than in here.
     */
    default void open(NaruWorkspace agent) {
    }
}
