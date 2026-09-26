package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionBuilder;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.scheduler.NaruTaskMode;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.api.util.NaruTerminalFormatter;
import net.thevpc.naru.impl.interaction.NaruTerminalInteraction;
import net.thevpc.nuts.artifact.NVersion;
import net.thevpc.nuts.io.NIO;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NSystemTerminal;
import net.thevpc.nuts.text.NMsg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/**
 * Default {@link NaruSessionBuilder}. Created per session by the agent.
 */
class NaruSessionBuilderImpl implements NaruSessionBuilder {

    private final NaruAgentImpl agent;
    private NPath directory;
    private NaruInteraction interaction;
    private NaruTaskSpec task;
    private boolean interactive;
    private boolean banner;
    private boolean richTerm;

    NaruSessionBuilderImpl(NaruAgentImpl agent) {
        this.agent = agent;
    }

    @Override
    public NaruSessionBuilder directory(NPath directory) {
        this.directory = directory;
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
    public NaruSession build() {
        NPath dir = directory != null ? directory : agent.defaultSessionDirectory();
        NaruInteraction useInteraction = interaction != null
                ? interaction
                : new NaruTerminalInteraction();
        NaruSessionImpl session = new NaruSessionImpl(
                agent, dir.toAbsolute(), useInteraction, true,
                agent.sessionListener(), agent.directiveFilter(), agent.toolFilter(), agent.tagFilter());
        if (task != null) {
            // the task is its own builder, so the mode is set on the handle rather than
            // on the spec
            NaruTask handle = session.newTask(task);
            if (interactive) {
                handle.taskMode(NaruTaskMode.INTERACTIVE);
            }
            handle.fg().unhold();
        }
        if (richTerm) {
            enableRichTerm(useInteraction, session);
        }
        if (banner) {
            printBanner();
        }
        return session;
    }

    /**
     * Turns on ANSI styling and command highlighting.
     * <p>
     * <b>This is process-global and cannot be made per-session.</b> Nuts exposes
     * {@code commandHighlighter} only on the system terminal singleton, with no
     * per-instance setter, so two interactive sessions in one JVM necessarily share it and
     * the last one configured wins. It is therefore off by default and should be enabled by
     * a launcher that owns the console, not by a host running many sessions.
     * <p>
     * A headless session ignores it entirely — there is no terminal to decorate.
     */
    private void enableRichTerm(NaruInteraction useInteraction, NaruSession session) {
        NSystemTerminal.enableRichTerm();
        if (useInteraction instanceof NaruTerminalInteraction) {
            NIO.of().systemTerminal()
                    .commandHighlighter(new NaruTerminalFormatter(session));
        }
    }

    private void printBanner() {
        agent.log(NaruLogMode.RAW, NMsg.ofC(
                "╭╮╷╭─╮╭─╮╷ ╷\n" +
                        "│╰┤├─┤├┬╯│ │ Nuts AI Reasoning Unit\n" +
                        "╵ ╵╵ ╵╵╰╴╰─╯ v%s\n" +
                        "Type %s%s (or %s%s) for help and %s%s to exit.\n"
                , NVersion.of("1.0.0.0")
                , NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1("help")
                , NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1("?")
                , NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1("exit")
        ));
    }
}
