package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;

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
     * How the session reaches its user: a terminal, a stream, or anything else.
     * <p>
     * Defaults to a {@link NaruTerminalInteraction} on the process terminal, which is what
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

    /**
     * Creates the session without starting it.
     *
     * @throws IllegalStateException if the agent has no project directory and none was set
     */
    NaruSession build();
}
