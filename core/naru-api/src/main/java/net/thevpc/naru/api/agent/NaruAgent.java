package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

import java.util.List;
import java.util.function.Predicate;

public interface NaruAgent {
    Predicate<NaruDirective> directiveFilter();

    NaruAgent directiveFilter(Predicate<NaruDirective> directiveFilter);

    Predicate<NaruTool> toolFilter();

    NaruAgent toolFilter(Predicate<NaruTool> toolFilter);

    Predicate<NaruToolTag> tagFilter();

    NaruAgent tagFilter(Predicate<NaruToolTag> tagFilter);

    NPath projectDirectory();

    NaruAgent projectDirectory(NPath projectDirectory);

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
}
