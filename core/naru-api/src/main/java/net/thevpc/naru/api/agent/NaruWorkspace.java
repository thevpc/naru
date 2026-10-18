package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.registry.NaruAgentExtension;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.model.NaruModelFilter;
import net.thevpc.naru.api.store.NaruDataStore;
import net.thevpc.nuts.artifact.NVersion;
import net.thevpc.nuts.ext.NExtensions;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.reflect.NClassLoader;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

import java.util.List;
import java.util.function.Predicate;
import javax.sql.DataSource;

public interface NaruWorkspace {
    NVersion apiVersion();

    NVersion implVersion();

    /**
     * The agent implementation on the classpath.
     * <p>
     * Resolved through the nuts extension registry when a workspace is available, and
     * by name otherwise, so a plain {@code main()} that never bootstrapped a workspace
     * can still start an agent. Fails loudly when {@code naru-impl} is absent: there is
     * no meaningful agent without an implementation.
     */
    static NaruWorkspace of() {
        NOptional<NaruWorkspace> bySpi = NExtensions.get()
                .flatMap(x -> x.createComponent(NaruWorkspace.class));
        if (bySpi.isPresent()) {
            return bySpi.get();
        }
        try {
            Class<?> impl = Class.forName("net.thevpc.naru.impl.engine.NaruWorkspaceImpl");
            return (NaruWorkspace) impl.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "no naru workspace implementation found on the classpath (naru-impl is missing?)", e);
        }
    }

    /**
     * A feature that belongs to the agent rather than to a session, looked up by name.
     * <p>
     * Created lazily on first lookup and retained for the agent's lifetime, exactly
     * like session extensions. Empty when no extension with that name is installed.
     */
    <T extends NaruAgentExtension> NOptional<T> extension(String name, Class<T> as);

    Predicate<NaruDirective> directiveFilter();

    NaruWorkspace directiveFilter(Predicate<NaruDirective> directiveFilter);

    Predicate<NaruTool> toolFilter();

    NaruWorkspace toolFilter(Predicate<NaruTool> toolFilter);

    Predicate<NaruToolTag> tagFilter();

    NaruWorkspace tagFilter(Predicate<NaruToolTag> tagFilter);

    /**
     * The model filter every session of this workspace inherits, or null for "allow every
     * model". A session may narrow it further with
     * {@link NaruSessionBuilder#modelFilter(NaruModelFilter)}; the two are AND-combined.
     */
    NaruModelFilter modelFilter();

    NaruWorkspace modelFilter(NaruModelFilter modelFilter);

    NPath projectDirectory();

    NaruWorkspace projectDirectory(NPath projectDirectory);

    /**
     * Begins configuring a new session. Nothing is created or started until
     * {@link NaruSessionBuilder#build()} is called, so the caller chooses both the
     * configuration and the moment work begins.
     * <pre>{@code
     * NaruSession s = agent.newSession().interactive().task(spec).build();
     * s.start();
     * }</pre>
     */
    NaruSessionBuilder newSession();

    /**
     * The sessions currently running under this agent.
     * <p>
     * A live view, not a snapshot: a session appears when it starts and disappears when it
     * stops. A server hosting many sessions uses this to find one by id.
     */
    List<NaruSession> sessions();

    NClassLoader classLoader();

    /**
     * Looks up a running session by its id.
     *
     * @return the session, or null if no running session has that id
     */
    NOptional<NaruSession> session(String id);

    /**
     * Agent-level output: the startup banner and failures of the agent's own maintenance
     * loop. Session output goes through the session's {@link NaruInteraction} instead, so
     * that concurrent sessions stay distinguishable.
     */
    void log(NaruLogMode mode, NMsg message);

    NaruEnv env();

    /**
     * The in-memory, per-process agent env.
     *
     * <p>Distinct from {@link #env()}, which is the on-disk project config (the two
     * {@code env.tson} files). Values here are shared by every session of this process and
     * live between the session env and the config files in the resolution chain, so
     * {@code /set --agent model.thinking=false} overrides a saved setting for this run
     * without writing a file. Nothing survives a restart.
     */
    NaruEnv agentEnv();

    /** Opens a shared, backend-neutral feature store scoped to the project. */
    NaruDataStore dataStore(String namespace);

    /** Opens a shared feature store scoped to the given project or user directory. */
    NaruDataStore dataStore(String namespace, NPath scopeDir);

    /** Opens a shared store with an application-supplied JDBC DataSource when selected. */
    NaruDataStore dataStore(String namespace, NPath scopeDir, DataSource dataSource);
}
