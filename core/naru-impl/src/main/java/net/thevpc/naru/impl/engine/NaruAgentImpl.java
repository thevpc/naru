package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.scheduler.NaruTaskMode;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.api.registry.NaruRegistry;
import net.thevpc.naru.impl.ia.budget.NaruMeteringServiceImpl;
import net.thevpc.naru.impl.cmdline.NaruTerminalFormatter;
import net.thevpc.naru.impl.cmdline.NaruNCmdLineAutoCompleteResolver;
import net.thevpc.naru.impl.util.StoredStringMap;
import net.thevpc.nuts.artifact.NVersion;
import net.thevpc.nuts.concurrent.NCallable;
import net.thevpc.nuts.io.*;
import net.thevpc.nuts.log.NLogger;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.util.*;

import java.util.*;
import java.util.concurrent.*;

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

    private final NaruMeteringServiceImpl meteringService = new NaruMeteringServiceImpl();
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
                if(session.isRunning()) {
                    session.scheduler().runRetention();
                    session.scheduler().runBlockedDrain();
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
                        "╵ ╵╵ ╵╵╰╴╰─╯ v%s", NVersion.of("0.8.9.0")));
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
        return new NaruSessionImpl(this, dir.toAbsolute(), meteringService, true, asSessionListener);
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

    private void enableRichTerm(NaruSession session) {
        NSystemTerminal.enableRichTerm();
        NIO.of().systemTerminal()
                .commandAutoCompleteResolver(new NaruNCmdLineAutoCompleteResolver(session))
                .commandHighlighter(new NaruTerminalFormatter(session))
        ;
    }
//    private void handleCallDirective(String raw, NaruSession ctx, NaruRoutine routine) {
//        // Parse: /call subName arg1 arg2 (simple split; upgrade to tokenizer later)
//        String[] parts = raw.substring(6).trim().split("\\s+");
//        String subName = parts[0];
//        List<String> args = Arrays.asList(Arrays.copyOfRange(parts, 1, parts.length));
//
//        SubroutineDef sub = routine.getSubroutines().get(subName);
//        if (sub == null) {
//            log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error : Unknown subroutine: %s", subName).asError());
//            advancePcOrEnd(ctx, routine);
//            return;
//        }
//        if (sub.params().size() != args.size()) {
//            log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error : Arg mismatch for /call %s: expected %d, got %d",
//                    subName, sub.params().size(), args.size()).asError());
//            advancePcOrEnd(ctx, routine);
//            return;
//        }
//
//        // Save return point: next line after /call
//        Integer nextLine = routine.getLinesSet().higherKey(ctx.pc());
//
//        // Push new context frame with returnPc + params
//        ctx.pushContext(sub.startLine(), nextLine); // Adds new RunContext at index 0
//
//        // Bind params to new frame
//        for (int i = 0; i < sub.params().size(); i++) {
//            ctx.getTopContext().bindParam(sub.params().get(i), args.get(i));
//        }
//
//        // Continue execution at subroutine start
//        ctx.pushStatement(NaruStatementHelper.ofExecRoutineLine());
//    }

//    private void advancePcOrEnd(NaruSession ctx, NaruRoutine routine) {
//        Integer next = routine.getLinesSet().higherKey(ctx.pc());
//        if (next != null) {
//            ctx.pc(next);
//            ctx.pushStatement(NaruStatementHelper.ofExecRoutineLine());
//        } else {
//            log(NaruLogMode.PROGRESS, NMsg.ofC("Routine execution finished."));
//            ctx.pc(-1);
////            if (ctx.isForever()) {
////                ctx.pushStatement(NaruStatementHelper.ofReadLine());
////            }
//        }
//    }

//    private void handleReturnDirective(NaruSession ctx) {
//        RunContext current = ctx.getTopContext();
//        Integer resumePc = current.returnPc();
//
//        // Pop the subroutine frame
//        ctx.popStatement(); // Removes index 0 RunContext
//
//        if (resumePc != null) {
//            ctx.pc(resumePc);
//            ctx.pushStatement(NaruStatementHelper.ofExecRoutineLine());
//        } else {
//            // No return point → end of routine
//            log(NaruLogMode.PROGRESS, NMsg.ofC("Subroutine returned to end of routine."));

    /// /            if (ctx.isForever()) {
    /// /                ctx.pushStatement(NaruStatementHelper.ofReadLine());
    /// /            }
//        }
//    }
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
