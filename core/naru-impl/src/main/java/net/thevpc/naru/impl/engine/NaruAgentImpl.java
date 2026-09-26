package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.scheduler.NaruTaskMode;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.api.registry.NaruRegistry;
import net.thevpc.naru.api.util.NaruTerminalFormatter;
import net.thevpc.naru.impl.cmdline.NaruNArgCompleteResolver;
import net.thevpc.naru.impl.util.StoredStringMap;
import net.thevpc.nuts.artifact.NVersion;
import net.thevpc.nuts.concurrent.NCallable;
import net.thevpc.nuts.io.*;
import net.thevpc.nuts.log.NLogger;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.util.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/**
 * The core agent loop.
 *
 * <ol>
 *   <li>Adds a system prompt + user task to the conversation history.</li>
 *   <li>Calls the model.</li>
 *   <li>If the model returns {@code tool_calls}: dispatches each call via
 *       {@link NaruRegistry}, appends the results, and loops.</li>
 *   <li>If the model returns plain text: that is the final answer.</li>
 *   <li>Stops after {@code maxSteps} iterations regardless.</li>
 * </ol>
 *
 * <p>This class is pure Java — no Nuts dependency — so it can be extracted
 * into a standalone library later.
 */
public class NaruAgentImpl implements NaruAgent {

    /**
     * Optional step listener for CLI progress printing.
     */
    private NLogger logger;
    private NPath projectDirectory;
    private StoredStringMap<NaruModelConfig> modelAliases;
    private NaruProjectEnv projectEnv;
    private final List<NaruSession> sessions = new ArrayList<>();
    private final Object signal = new Object();
    private volatile Thread maintenanceThread;
    private final ExecutorService STOP_THE_WORLD_EXECUTOR =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "naru-action");
                t.setDaemon(true);
                return t;
            });
    private final ConcurrentLinkedQueue<Runnable> pendingActions = new ConcurrentLinkedQueue<>();

    private Predicate<NaruDirective> directiveFilter;
    private Predicate<NaruTool> toolFilter;
    private Predicate<NaruToolTag> tagFilter;

    private final NaruSessionListener asSessionListener = new NaruSessionListener() {

        @Override
        public void sessionStarted(NaruSession session) {
            sessions.add(session);
            ensureGlobal();
        }

        @Override
        public void sessionStopped(NaruSession session) {
            sessions.remove(session);
            ensureGlobal();
        }

        @Override
        public void onSessionReloaded(NaruSession naruSession) {
            ensureGlobal();
        }

        @Override
        public void onEventAppended(NaruEvent newEvent) {
            if (maintenanceThread != null) {
                synchronized (signal) {
                    signal.notifyAll();
                }
            }
        }
    };

    public NaruAgentImpl() {
        this.logger = NLogger.STDOUT;
    }

    public Predicate<NaruDirective> directiveFilter() {
        return directiveFilter;
    }

    public NaruAgentImpl setDirectiveFilter(Predicate<NaruDirective> directiveFilter) {
        this.directiveFilter = directiveFilter;
        return this;
    }

    public Predicate<NaruTool> toolFilter() {
        return toolFilter;
    }

    public NaruAgentImpl setToolFilter(Predicate<NaruTool> toolFilter) {
        this.toolFilter = toolFilter;
        return this;
    }

    public Predicate<NaruToolTag> tagFilter() {
        return tagFilter;
    }

    public NaruAgentImpl setTagFilter(Predicate<NaruToolTag> tagFilter) {
        this.tagFilter = tagFilter;
        return this;
    }

    public <T> Future<T> postAction(NCallable<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        pendingActions.add(() -> {
            try {
                future.complete(action.call());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        //signal.;
        return future;
    }

    private void ensureGlobal() {
        if (sessions.isEmpty()) {

        } else {
            if (maintenanceThread == null) {
                Thread t = new Thread(this::maintenanceLoop, "naru-maintenance");
                t.setDaemon(true);
                t.start();
                maintenanceThread = t;
            }
        }
    }

    private void maintenanceLoop() {
        while (true) {
            for (NaruSession session : new ArrayList<>(sessions)) {
                if (session.isRunning()) {
                    try {
                        session.scheduler().runRetention();
                        session.scheduler().runBlockedDrain();
                    } catch (Exception e) {
                        if (!session.isRunning()) {
                            //just ignore
                        } else {
                            NErr.println(NMsg.ofC("maintenanceLoop error: %s", e));
                        }
                    }
                }
            }
            Runnable action;
            while ((action = pendingActions.poll()) != null) {
                STOP_THE_WORLD_EXECUTOR.submit(action); // non-blocking
            }
            sleepOrSignal(100);
        }
    }


    private void sleepOrSignal(long ms) {
        synchronized (signal) {
            try {
                signal.wait(ms <= 0 ? 50 : ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public NPath getProjectDirectory() {
        return projectDirectory;
    }

    @Override
    public NaruAgent setProjectDirectory(NPath projectDirectory) {
        this.projectDirectory = projectDirectory;
        modelAliases = new StoredStringMap<>(projectDirectory.resolve(".naru/model/aliases.tson"), NaruModelConfig.class)
                .setSerializer(x -> x.toElement())
                .setDeserializer(x -> NaruModelConfig.of(x).get())
        ;
        projectEnv = new NaruProjectEnv(
                projectDirectory.resolve(".naru/config/env.tson"),
                projectDirectory.resolve(".naru/local/config/env.tson")
        );
        return this;
    }

    public NaruProjectEnv env() {
        return projectEnv;
    }

    public StoredStringMap<NaruModelConfig> getModelAliases() {
        return modelAliases;
    }

    public NaruAgent logger(NLogger logger) {
        this.logger = logger;
        return this;
    }

    public NaruSession startInteractiveSession(String... commands) {
        log(NaruLogMode.RAW, NMsg.ofC(
                "╭╮╷╭─╮╭─╮╷ ╷\n" +
                        "│╰┤├─┤├┬╯│ │ Nuts AI Reasoning Unit\n" +
                        "╵ ╵╵ ╵╵╰╴╰─╯ v%s\n" +
                        "Type %s%s (or %s%s) for help and %s%s to exit.\n"
                , NVersion.of("1.0.0.0")
                , NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1("help")
                , NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1("?")
                , NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1("exit")
        ));
        NaruSession session = newSession(null);
        enableRichTerm(session);
        NOut.resetLine();
        session.newTask(NaruTaskSpec.of().statements(commands).resolveNameOr("naru"))
                .taskMode(NaruTaskMode.INTERACTIVE)
                .fg()
                .unhold()
        ;
        session.start(); // ← missing
        session.waitFor();
        return session;
    }

    public NaruSession newSession(NPath dir) {
        if (dir == null) {
            dir = projectDirectory;
        }
        if (dir == null) {
            dir = NPath.ofUserDirectory();
        }
        return new NaruSessionImpl(this, dir.toAbsolute(), true, asSessionListener,directiveFilter, toolFilter, tagFilter);
    }


    @Override
    public NaruSession startSession(String... commands) {
        NaruSession session = newSession(null);
        session.newTask(NaruTaskSpec.of().statements(commands).resolveNameOr("naru"))
                .fg()
                .unhold();
        session.start();
        return session;
    }

    @Override
    public NaruSession startSession(InputStream in) {
        if (in == null) {
            throw new NIllegalArgumentException(NMsg.ofC("null script input stream"));
        }
        String content;
        try {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new NIllegalArgumentException(NMsg.ofC("fail to read script input stream : %s", e));
        }
        // Keep ALL lines (blank ones included): parseStatement turns blanks into
        // no-ops, except inside a "/buffer on ... /buffer off" block where they
        // are meaningful parts of a multi-line prompt.
        return startSession(content.split("\r\n|\r|\n", -1));
    }

    private void enableRichTerm(NaruSession session) {
        NSystemTerminal.enableRichTerm();
        NIO.of().systemTerminal()
                .commandAutoCompleteResolver(new NaruNArgCompleteResolver(session))
                .commandHighlighter(new NaruTerminalFormatter(session))
        ;
    }

    @Override
    public void log(NaruLogMode mode, NMsg message) {
        //if (config.isVerbose() && logger != null) {
        switch (mode) {
            case RAW: {
                logger.log(message);
                break;
            }
            case MODEL_RESPONSE: {
                for (NText line : NaruTerminalFormatter.formatOutputLines(message.toString(), NText.ofStyled("  \u258C", NTextStyle.primary3()))) {
                    logger.log(NMsg.ofC("%s", line));
                }
                break;
            }
            case MODEL_THINKING: {
                for (NText line : NaruTerminalFormatter.formatOutputLines(message.toString(), NText.ofStyled("  \u258C", NTextStyle.primary9()))) {
                    logger.log(NMsg.ofC("%s", line));
                }
                break;
            }
            case AGENT_RESPONSE: {
                logLines(message, 1, "\u258C", 4);
                break;
            }
            case SCRIPT: {
                logLines(message, 2, "▶️", 5);
                break;
            }
            case TRACE: {
                logLines(message, 2, "\u258C", 6);
                break;
            }
            case PROGRESS: {
                logLines(message, 2, "\u258C", 7);
                break;
            }
            case DEBUG: {
                logLines(message, 2, "\u258C", 8);
                break;
            }
            case SCHEDULER: {
                logLines(message, 0, "\u258C", 9);
                break;
            }
            default: {
                logger.log(message);
            }
        }
        //}
    }

    private void logLines(NMsg message, int indent, String prefix, int style) {
        List<NText> all = NText.of(message).split("\n", false);
        String spaces = NStringUtils.repeat(" ", indent * 2);
        for (NText o : all) {
            logger.log((NMsg.ofC("%s%s %s", spaces, NMsg.ofStyled(prefix, NTextStyle.primary(style)), o)));
        }
    }

}
