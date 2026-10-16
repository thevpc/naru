package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionBuilder;
import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.store.NaruSessionScope;
import net.thevpc.naru.api.store.NaruSessionStore;
import net.thevpc.naru.api.scheduler.NaruTaskMode;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.api.access.NaruActor;
import net.thevpc.naru.api.access.NaruPrincipal;
import net.thevpc.naru.api.util.NaruTerminalFormatter;
import net.thevpc.naru.impl.cmdline.NaruNArgCompleteResolver;
import net.thevpc.naru.impl.interaction.NaruTerminalInteraction;
import net.thevpc.nuts.artifact.NVersion;
import net.thevpc.nuts.io.NIO;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NSystemTerminal;
import net.thevpc.nuts.text.NMsg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Default {@link NaruSessionBuilder}. Created per session by the agent.
 */
class NaruSessionBuilderImpl implements NaruSessionBuilder {

    private final NaruWorkspaceImpl agent;
    private NPath directory;
    private NaruInteraction interaction;
    private NaruTaskSpec task;
    private boolean interactive;
    private boolean banner;
    private NMsg bannerMessage;
    private boolean richTerm;
    private String id;
    private NaruSessionStore store;
    private NaruSessionScope scope = NaruSessionScope.PRIVATE;
    private NaruInputBlockPolicy inputBlockPolicy = NaruInputBlockPolicy.HIBERNATE;
    private Duration hibernateAfter = Duration.ZERO;
    private boolean streaming = true;
    private NaruBusyPolicy busyPolicy = NaruBusyPolicy.REJECT;
    private NaruTurnEventListener turnListener;
    private int maxLiveSessions = Integer.MAX_VALUE;
    private boolean checkpointEachMessage = true;
    private Duration capacityTimeout = Duration.ofSeconds(30);
    /**
     * Owner of the session; null means "the OS user", resolved by the session.
     */
    private NaruPrincipal user;
    /**
     * Owner already bound as an actor; takes precedence over {@link #user}.
     */
    private NaruActor userActor;



    private NaruSessionListener sessionListener;

    private boolean configureDefaults = true;

    private Predicate<NaruDirective> directiveFilter;

    private Predicate<NaruTool> toolFilter;

    private Predicate<NaruToolTag> tagFilter;

    NaruSessionBuilderImpl(NaruWorkspaceImpl agent) {
        this.agent = agent;
    }

    @Override
    public NaruSessionBuilder sessionListener(NaruSessionListener value) {
        this.sessionListener = value;
        return this;
    }

    @Override
    public NaruSessionBuilder configureDefaults(boolean value) {
        this.configureDefaults = value;
        return this;
    }

    @Override
    public NaruSessionBuilder directiveFilter(Predicate<NaruDirective> value) {
        this.directiveFilter = value;
        return this;
    }

    @Override
    public NaruSessionBuilder toolFilter(Predicate<NaruTool> value) {
        this.toolFilter = value;
        return this;
    }

    @Override
    public NaruSessionBuilder tagFilter(Predicate<NaruToolTag> value) {
        this.tagFilter = value;
        return this;
    }

    @Override
    public NaruSessionBuilder directory(NPath directory) {
        this.directory = directory;
        return this;
    }

    @Override
    public NaruSessionBuilder user(NaruPrincipal user) {
        this.user = user;
        return this;
    }

    @Override
    public NaruSessionBuilder userActor(NaruActor actor) {
        this.userActor = actor;
        return this;
    }

    @Override
    public NaruSessionBuilder id(String id) {
        this.id = id;
        return this;
    }

    @Override
    public NaruSessionBuilder store(NaruSessionStore store) {
        this.store = store;
        return this;
    }

    @Override
    public NaruSessionBuilder scope(NaruSessionScope scope) {
        this.scope = scope;
        return this;
    }

    @Override
    public NaruSessionBuilder onInputBlock(NaruInputBlockPolicy policy) {
        this.inputBlockPolicy = policy;
        return this;
    }

    @Override
    public NaruSessionBuilder hibernateAfter(Duration grace) {
        if (grace == null || grace.isNegative())
            throw new IllegalArgumentException("hibernate grace must be non-negative");
        this.hibernateAfter = grace;
        return this;
    }

    @Override
    public NaruSessionBuilder streaming(boolean enabled) {
        this.streaming = enabled;
        return this;
    }

    @Override
    public NaruSessionBuilder busy(NaruBusyPolicy policy) {
        this.busyPolicy = policy;
        return this;
    }

    @Override
    public NaruSessionBuilder listener(NaruTurnEventListener listener) {
        this.turnListener = listener;
        return this;
    }

    @Override
    public NaruSessionBuilder maxLiveSessions(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("max live sessions must be positive");
        this.maxLiveSessions = maximum;
        return this;
    }

    @Override
    public NaruSessionBuilder checkpointEachMessage(boolean enabled) {
        this.checkpointEachMessage = enabled;
        return this;
    }

    @Override
    public NaruSessionBuilder capacityTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative())
            throw new IllegalArgumentException("capacity timeout must be non-negative");
        this.capacityTimeout = timeout;
        return this;
    }

    @Override
    public NaruSessionBuilder interaction(NaruInteraction interaction) {
        this.interaction = interaction;
        return this;
    }

    @Override
    public NaruSessionBuilder task(NaruTaskSpec task) {
        this.task = task;
        return this;
    }

    @Override
    public NaruSessionBuilder statements(String... statements) {
        return task(NaruTaskSpec.of().statements(statements).resolveNameOr("naru"));
    }

    @Override
    public NaruSessionBuilder interactive() {
        this.interactive = true;
        return this;
    }

    @Override
    public NaruSessionBuilder banner(boolean banner) {
        this.banner = banner;
        return this;
    }

    @Override
    public NaruSessionBuilder richTerm(boolean richTerm) {
        this.richTerm = richTerm;
        return this;
    }

    /**
     * Reads a script from a stream, one statement per line.
     * <p>
     * Blank lines are kept: the parser turns them into no-ops, except inside a
     * {@code /buffer on ... /buffer off} block where they are meaningful parts of a
     * multi-line prompt.
     */
    @Override
    public NaruSessionBuilder script(InputStream in) {
        if (in == null) {
            throw new IllegalArgumentException("null script input stream");
        }
        String content;
        try {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("fail to read script input stream : " + e, e);
        }
        return statements(content.split("\r\n|\r|\n", -1));
    }

    @Override
    public NaruSessionBuilder bannerMessage(NMsg bannerMessage) {
        this.bannerMessage = bannerMessage;
        return this;
    }

    @Override
    public NMsg bannerMessage() {
        return bannerMessage;
    }

    @Override
    public NaruSession build() {
        if (banner) {
            printBanner();
        }
        NPath dir = directory != null ? directory : agent.defaultSessionDirectory();
        NaruInteraction useInteraction = interaction != null
                ? interaction
                : new NaruTerminalInteraction();
        NaruSessionImpl session = new NaruSessionImpl(
                agent, dir.toAbsolute(), useInteraction, configureDefaults,
                combineSessionListener(),
                combineAnd(agent.directiveFilter(), directiveFilter),
                combineAnd(agent.toolFilter(), toolFilter),
                combineAnd(agent.tagFilter(), tagFilter),
                store
        );
        if (userActor != null) {
            session.userActor(userActor);
        } else if (user != null) {
            session.user(user);
        }
        if (task != null) {
            // the task is its own builder, so the mode is set on the handle rather than
            // on the spec
            NaruTask handle = session.newTask(task);
            if (interactive) {
                handle.taskMode(NaruTaskMode.INTERACTIVE);
            }
            handle.fg().unhold();
            // so startRun() hands back a handle for *this* task rather than making another
            session.configuredTask(handle);
        }
        if (richTerm) {
            enableRichTerm(useInteraction, session);
        }

        return session;
    }

    private NaruSessionListenerDelegate combineSessionListener() {
        List<NaruSessionListener> ad = new ArrayList<>();
        ad.add(agent.sessionListener());
        if (sessionListener != null) {
            ad.add(sessionListener);
        }
        return new NaruSessionListenerDelegate(ad);
    }

    @Override
    public NaruSessionHandle buildHandle() {
        return new NaruSessionHandleImpl(agent,
                directory != null ? directory : agent.defaultSessionDirectory(),
                interaction, task, id, store, scope, inputBlockPolicy, hibernateAfter,
                streaming, busyPolicy, turnListener, maxLiveSessions,
                checkpointEachMessage, capacityTimeout, agent.sessionListener(), agent.directiveFilter(), agent.toolFilter(), agent.tagFilter());
    }

    /**
     * Turns on ANSI styling, command highlighting, and tab completion.
     * <p>
     * <b>This is process-global and cannot be made per-session.</b> Nuts exposes
     * {@code commandHighlighter} and {@code commandAutoCompleteResolver} only on the
     * system terminal singleton, with no per-instance setters, so two interactive
     * sessions in one JVM necessarily share them and the last one configured wins. It is
     * therefore off by default and should be enabled by a launcher that owns the console,
     * not by a host running many sessions.
     * <p>
     * A headless session gets no completer and no highlighter: it has no terminal
     * to own, and taking the process-global one would decorate a console it is
     * never reading from.
     */
    private void enableRichTerm(NaruInteraction useInteraction, NaruSession session) {
        NSystemTerminal.enableRichTerm();
        if (useInteraction instanceof NaruTerminalInteraction) {
            NIO.of().systemTerminal()
                    // registered here because this is the only place that owns the
                    // console: without it the REPL reads plain lines and tab does
                    // nothing, even though the resolver itself is still available
                    .commandAutoCompleteResolver(new NaruNArgCompleteResolver(session))
                    .commandHighlighter(new NaruTerminalFormatter(session));
        }
    }

    private void printBanner() {
        NMsg b = bannerMessage();
        if (b == null) {
            b = NMsg.ofC(
                    "╭╮╷╭─╮╭─╮╷ ╷\n" +
                            "│╰┤├─┤├┬╯│ │ Nuts AI Reasoning Unit\n" +
                            "╵ ╵╵ ╵╵╰╴╰─╯ v%s\n" +
                            "Type %s%s (or %s%s) for help and %s%s to exit.\n"
                    , NVersion.of("1.1.0.0")
                    , NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1("help")
                    , NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1("?")
                    , NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1("exit")
            );
        }
        agent.log(NaruLogMode.RAW, b);
    }

    private <T> Predicate<T> combineAnd(Predicate<T> a, Predicate<T> b) {
        if (a == null && b == null) {
            return x -> true;
        }
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.and(b);
    }
}
