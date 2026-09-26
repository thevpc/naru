package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.registry.NaruRegistry;
import net.thevpc.naru.impl.util.StoredStringMap;
import net.thevpc.nuts.concurrent.NCallable;
import net.thevpc.nuts.io.*;
import net.thevpc.nuts.log.NLogger;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.*;

import java.util.*;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
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
    /**
     * Live sessions. Populated from session lifecycle callbacks, which can arrive on any
     * thread, so the collection must be concurrent: a plain list was a race as soon as
     * two sessions started at once.
     */
    private final Set<NaruSession> sessions = new CopyOnWriteArraySet<>();
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

    @Override
    public Predicate<NaruDirective> directiveFilter() {
        return directiveFilter;
    }

    @Override
    public NaruAgent directiveFilter(Predicate<NaruDirective> directiveFilter) {
        this.directiveFilter = directiveFilter;
        return this;
    }

    @Override
    public Predicate<NaruTool> toolFilter() {
        return toolFilter;
    }

    @Override
    public NaruAgentImpl toolFilter(Predicate<NaruTool> toolFilter) {
        this.toolFilter = toolFilter;
        return this;
    }

    @Override
    public Predicate<NaruToolTag> tagFilter() {
        return tagFilter;
    }

    @Override
    public NaruAgentImpl tagFilter(Predicate<NaruToolTag> tagFilter) {
        this.tagFilter = tagFilter;
        return this;
    }

    public <T> Future<T> postAction(NCallable<T> action) {
        // The maintenance loop is what drains pendingActions. It used to be started only
        // when a session started, so a save() issued before the first start() parked on a
        // future nobody was going to complete.
        ensureGlobal();
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

    /**
     * Starts the single maintenance loop that runs retention, drains blocked tasks and
     * executes posted actions.
     * <p>
     * Started on demand rather than per session, and the check is double-checked because
     * session lifecycle callbacks and posted actions reach it from several threads at once.
     * The loop never exits, so once started it keeps serving actions even while no session
     * is live.
     */
    private void ensureGlobal() {
        if (maintenanceThread == null) {
            synchronized (this) {
                if (maintenanceThread == null) {
                    Thread t = new Thread(this::maintenanceLoop, "naru-maintenance");
                    t.setDaemon(true);
                    t.start();
                    maintenanceThread = t;
                }
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
    public NPath projectDirectory() {
        return projectDirectory;
    }

    @Override
    public NaruAgent projectDirectory(NPath projectDirectory) {
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

    @Override
    public NaruSessionBuilder newSession() {
        return new NaruSessionBuilderImpl(this);
    }

    /**
     * Where a session lives when the caller does not say: the project directory, or the
     * user's home directory when the agent has none.
     */
    NPath defaultSessionDirectory() {
        if (projectDirectory != null) {
            return projectDirectory;
        }
        return NPath.ofUserDirectory();
    }

    NaruSessionListener sessionListener() {
        return asSessionListener;
    }

    /**
     * A snapshot, not a window: a host iterating the result cannot be broken by, or break,
     * a session starting at the same moment.
     */
    @Override
    public List<NaruSession> sessions() {
        return List.copyOf(sessions);
    }

    @Override
    public NOptional<NaruSession> session(String id) {
        if (id == null) {
            return NOptional.ofNamedEmpty("session with null id");
        }
        synchronized (sessions) {
            for (NaruSession s : sessions) {
                if (Objects.equals(s.uuid(), id)) {
                    return NOptional.of(s);
                }
            }
        }
        return NOptional.ofNamedEmpty("session " + id);
    }

    @Override
    public void log(NaruLogMode mode, NMsg message) {
        if (logger != null) {
            logger.log(message);
        }
    }

}
