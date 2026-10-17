package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.model.NaruModelFilter;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.api.access.NaruActor;
import net.thevpc.naru.api.access.NaruPrincipal;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.naru.api.store.NaruSessionScope;
import net.thevpc.naru.api.store.NaruSessionStore;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * Configures and creates one {@link NaruSession}.
 * <p>
 * A builder rather than a set of {@code startXxx} methods, because the things a session
 * needs — where it lives, how it reaches its user, what it runs first — are independent
 * choices, and every combination used to need its own method on the agent.
 *
 * <pre>{@code
 * // a console session, blocking until the user leaves
 * NaruSession s = agent.newSession()
 *         .interactive()
 *         .blocking()
 *         .build();
 *
 * // a headless session for a web request
 * NaruSession s = agent.newSession()
 *         .interaction(new NaruStreamInteraction(output -> sse.send(output)))
 *         .task(NaruTaskSpec.of().statements(script))
 *         .build();
 * }</pre>
 *
 * <h2>What this does not do</h2>
 * It does not start the session. {@link #build()} returns a session that is configured but
 * not running, so the caller decides when work begins and whether to wait for it. That
 * separation is what lets a server hold a session open across requests.
 *
 * <h2>Thread safety</h2>
 * A builder is not thread-safe and is meant to be used once, to make one session. Build one
 * per session; the agent hands out a fresh builder each time.
 */
public interface NaruSessionBuilder {

    /**
     * Where the session keeps its state. Defaults to the agent's project directory, or the
     * user's home directory when the agent has none.
     */
    NaruSessionBuilder directory(NPath directory);

    /**
     * The principal that owns the session. Defaults to the OS user
     * ({@code System.getProperty("user.name")}), so a single-user host needs to
     * say nothing; a host that serves several people sets it explicitly.
     */
    default NaruSessionBuilder user(NaruPrincipal user) {
        throw new UnsupportedOperationException();
    }

    /**
     * Binds the owner as an already-acted actor, carrying its kind and groups.
     */
    default NaruSessionBuilder userActor(NaruActor actor) {
        throw new UnsupportedOperationException();
    }

    /**
     * Stable id used by a backend handle; existing ids are resolved by the store.
     */
    default NaruSessionBuilder id(String id) {
        throw new UnsupportedOperationException();
    }

    default NaruSessionBuilder store(NaruSessionStore store) {
        throw new UnsupportedOperationException();
    }

    default NaruSessionBuilder scope(NaruSessionScope scope) {
        throw new UnsupportedOperationException();
    }

    default NaruSessionBuilder onInputBlock(NaruInputBlockPolicy policy) {
        throw new UnsupportedOperationException();
    }

    default NaruSessionBuilder hibernateAfter(Duration grace) {
        throw new UnsupportedOperationException();
    }

    default NaruSessionBuilder streaming(boolean enabled) {
        throw new UnsupportedOperationException();
    }

    default NaruSessionBuilder busy(NaruBusyPolicy policy) {
        throw new UnsupportedOperationException();
    }

    default NaruSessionBuilder listener(NaruTurnEventListener listener) {
        throw new UnsupportedOperationException();
    }

    default NaruSessionBuilder maxLiveSessions(int maximum) {
        throw new UnsupportedOperationException();
    }

    default NaruSessionBuilder checkpointEachMessage(boolean enabled) {
        throw new UnsupportedOperationException();
    }

    default NaruSessionBuilder capacityTimeout(Duration timeout) {
        throw new UnsupportedOperationException();
    }

    /**
     * How the session reaches its user: a terminal, a stream, or anything else.
     * <p>
     * Defaults to a {@link NaruInteraction} on the process terminal, which is what
     * every caller relied on before this seam existed. A host that will supply input
     * itself should pass a headless interaction instead, so no thread is started and no
     * process stdin is claimed.
     */
    NaruSessionBuilder interaction(NaruInteraction interaction);

    /**
     * The task the session runs when it starts. Optional; a session may be started with no
     * work and driven later through {@link NaruSession#newTask}.
     */
    NaruSessionBuilder task(NaruTaskSpec task);

    /**
     * Shorthand for running {@code statements} as the session's first task, with input
     * arriving line by line. Ignored if {@link #task} was already set.
     */
    NaruSessionBuilder statements(String... statements);

    /**
     * Reads the session's script from a stream, one statement per line.
     * <p>
     * Blank lines are kept rather than skipped: the parser turns them into no-ops, except
     * inside a {@code /buffer on ... /buffer off} block where they are meaningful parts of
     * a multi-line prompt.
     */
    NaruSessionBuilder script(java.io.InputStream in);

    /**
     * The first task reads user input line by line, which is what makes a session feel
     * interactive rather than batch.
     */
    NaruSessionBuilder interactive();

    /**
     * Prints the startup banner on the agent's own channel. Off by default, since a
     * library or server host has no banner to print.
     */
    NaruSessionBuilder banner(boolean banner);

    NaruSessionBuilder bannerMessage(NMsg bannerMessage);

    NMsg bannerMessage();

    /**
     * Enables the rich terminal: ANSI styling and command highlighting. Only meaningful for
     * a terminal interaction, and applied to that session's terminal rather than to
     * process-global state, so concurrent sessions do not fight over it.
     */
    NaruSessionBuilder richTerm(boolean richTerm);

    NaruSessionBuilder sessionListener(NaruSessionListener value);

    /**
     * Whether the session registers the built-in directives, tools, tags and every
     * extension provider found on the classpath.
     * <p>
     * On by default: that is what makes a session useful the moment it is built. A test
     * that wants to observe one subsystem in isolation turns it off and registers only
     * what it is exercising, so an unrelated provider cannot perturb it.
     */
    default NaruSessionBuilder configureDefaults(boolean value) {
        throw new UnsupportedOperationException();
    }

    NaruSessionBuilder directiveFilter(Predicate<NaruDirective> value);

    NaruSessionBuilder toolFilter(Predicate<NaruTool> value);

    NaruSessionBuilder tagFilter(Predicate<NaruToolTag> value);

    /**
     * Narrows the models this session may use, on top of the workspace's own filter (the
     * two are AND-combined). A denied model disappears from listings and resolution and a
     * call to it is refused before the transport is built.
     */
    default NaruSessionBuilder modelFilter(NaruModelFilter value) {
        throw new UnsupportedOperationException();
    }

    /**
     * Creates the session without starting it.
     *
     * @throws IllegalStateException if the agent has no project directory and none was set
     */
    NaruSession build();

    /**
     * Builds a lazy backend handle while preserving {@link #build()} for existing callers.
     */
    default NaruSessionHandle buildHandle() {
        throw new UnsupportedOperationException();
    }
}
