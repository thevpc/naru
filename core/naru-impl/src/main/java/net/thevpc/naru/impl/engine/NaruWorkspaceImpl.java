package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.registry.NaruAgentExtension;
import net.thevpc.naru.api.registry.NaruConfigKey;
import net.thevpc.naru.api.registry.NaruConfigKeyProvider;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.model.NaruModelFilter;
import net.thevpc.naru.api.store.NaruDataStore;
import net.thevpc.naru.api.store.NaruDataStoreConfig;
import net.thevpc.naru.api.store.NaruDataStoreProvider;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.registry.NaruRegistry;
import net.thevpc.naru.impl.config.NaruCoreConfigKeyProvider;
import net.thevpc.nuts.app.NApplication;
import net.thevpc.nuts.artifact.*;
import net.thevpc.nuts.command.NSearch;
import net.thevpc.nuts.concurrent.NCallable;
import net.thevpc.nuts.ext.NServiceLoader;
import net.thevpc.nuts.io.*;
import net.thevpc.nuts.log.NLogger;
import net.thevpc.nuts.reflect.NClassLoader;
import net.thevpc.nuts.reflect.NMutableClassLoader;
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
public class NaruWorkspaceImpl implements NaruWorkspace {
    private static final NVersion API_VERSION = NVersion.of("1.1");
    private static final NVersion IMPL_VERSION = NVersion.of("1.1.0");
    private static final String DEFAULT_PROFILE = "net.thevpc.naru.extensions:naru-profile-dev";
    public static final String NET_THEVPC_NARU_EXTENSIONS = "net.thevpc.naru.extensions";

    /**
     * Optional step listener for CLI progress printing.
     */
    private NLogger logger;
    private NPath projectDirectory;
    private NaruRegistrationStore registrations;
    private NaruProjectEnv projectEnv;
    private volatile NClassLoader classLoader;
    private List<NId> requestedExtensions = new ArrayList<>();
    private List<NId> effectiveExtensions = new ArrayList<>();
    /**
     * The in-memory agent env. Unlike {@link #projectEnv}, it is not rebuilt when the
     * project directory changes: it belongs to the process, not to a project.
     */
    private final NaruMemoryEnv agentEnv = new NaruMemoryEnv();
    /**
     * Live sessions. Populated from session lifecycle callbacks, which can arrive on any
     * thread, so the collection must be concurrent: a plain list was a race as soon as
     * two sessions started at once.
     */
    private final Set<NaruSession> sessions = new CopyOnWriteArraySet<>();
    /**
     * The saved-session catalog, built on first use and rebuilt when the project changes.
     * Guarded by {@code this} rather than being volatile-only: two readers racing to build
     * it would otherwise create two handles to the same folder, which is the duplication
     * this class exists to prevent.
     */
    private NaruSessionStoreManager sessionCatalog;
    private NPath catalogProjectDirectory;
    private final Object signal = new Object();
    private volatile Thread maintenanceThread;
    private final ExecutorService STOP_THE_WORLD_EXECUTOR =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "naru-action");
                t.setDaemon(true);
                return t;
            });

    @Override
    public NVersion apiVersion() {
        return API_VERSION;
    }

    @Override
    public NVersion implVersion() {
        return IMPL_VERSION;
    }

    /**
     * Runs user-supplied {@code NaruTask.onComplete} callbacks.
     * <p>
     * Deliberately not {@link #STOP_THE_WORLD_EXECUTOR}: that pool exists to run saves while
     * the engine is halted, and a completion callback is free to call {@code save()} itself,
     * which would then be queued behind itself.
     */
    private final ExecutorService runCallbackExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "naru-run-callback");
                t.setDaemon(true);
                return t;
            });
    private final ConcurrentLinkedQueue<Runnable> pendingActions = new ConcurrentLinkedQueue<>();

    private Predicate<NaruDirective> directiveFilter;
    private Predicate<NaruTool> toolFilter;
    private Predicate<NaruToolTag> tagFilter;
    private NaruModelFilter modelFilter;

    /**
     * Agent-level extensions, retained for the agent's lifetime. Created lazily on
     * first lookup, mirroring how a session registry retains its session extensions:
     * a project-level feature owns state that outlives every session, so creating it
     * per-lookup would split that state across instances.
     */
    private final Map<String, NaruAgentExtension> agentExtensions = new ConcurrentHashMap<>();
    private volatile boolean agentExtensionsLoaded;
    private final Map<String, NaruDataStore> dataStores = new ConcurrentHashMap<>();

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

    public NaruWorkspaceImpl() {
        this(null);
    }

    public NaruWorkspaceImpl(NId[] extensions) {
        if (extensions != null) {
            for (NId e : extensions) {
                if (e != null) {
                    requestedExtensions.add(e);
                }
            }
        }
        this.logger = a -> {
            NOut.resetLine().println(a);
        };
    }

    @Override
    public NaruDataStore dataStore(String namespace) {
        return dataStore(namespace, projectDirectory);
    }

    @Override
    public NaruDataStore dataStore(String namespace, NPath scopeDir) {
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("namespace is required");
        NPath scope = (scopeDir == null ? projectDirectory : scopeDir).normalize();
        String cacheKey = namespace + "|" + scope;
        return dataStores.computeIfAbsent(cacheKey, k -> {
            String providerName = System.getProperty("naru.store." + namespace + ".provider", "file").trim();
            NPath location = scope.resolve(".naru/stores/" + namespace);
            Map<String, String> props = new LinkedHashMap<>();
            for (String p : List.of("naru.jdbc.url", "naru.jdbc.user", "naru.jdbc.password", "naru.jdbc.driver")) {
                String v = System.getProperty(p);
                if (v != null) props.put(p, v);
            }
            NaruDataStoreConfig config = new NaruDataStoreConfig(namespace, projectDirectory, scope,
                    location, props);
            List<NaruDataStoreProvider> providers = NServiceLoader.of(NaruDataStoreProvider.class, null,
                    classLoader().asClassLoader()).loadAll(null);
            for (NaruDataStoreProvider provider : providers) {
                if (!providerName.equals(provider.name())) continue;
                NOptional<NaruDataStore> opened = provider.open(config);
                if (opened.isPresent()) return opened.get();
                throw new IllegalStateException("data-store provider '" + providerName + "' cannot serve " + namespace);
            }
            throw new IllegalStateException("no data-store provider named '" + providerName + "' is available for " + namespace);
        });
    }

    @Override
    public <T extends NaruAgentExtension> NOptional<T> extension(String name, Class<T> as) {
        if (!agentExtensionsLoaded) {
            synchronized (this) {
                if (!agentExtensionsLoaded) {
                    try {
                        NServiceLoader<NaruAgentExtension> cl = NServiceLoader.of(NaruAgentExtension.class, null, classLoader().asClassLoader());
                        for (NaruAgentExtension e : cl.loadAll(null)) {
                            if (agentExtensions.containsKey(e.name())) {
                                continue;
                            }
                            e.open(this);
                            agentExtensions.put(e.name(), e);
                        }
                    } catch (Exception ex) {
                        // discovery must never break the agent; an extension that cannot
                        // be listed is simply not available
                        log(NaruLogMode.SCRIPT, NMsg.ofC(
                                "agent extensions unavailable: %s", ex.getMessage()));
                    }
                    agentExtensionsLoaded = true;
                }
            }
        }
        NaruAgentExtension e = agentExtensions.get(name);
        return NOptional.ofNullable(e == null || !as.isInstance(e) ? null : as.cast(e));
    }

    @Override
    public Predicate<NaruDirective> directiveFilter() {
        return directiveFilter;
    }

    @Override
    public NaruWorkspace directiveFilter(Predicate<NaruDirective> directiveFilter) {
        this.directiveFilter = directiveFilter;
        return this;
    }

    @Override
    public Predicate<NaruTool> toolFilter() {
        return toolFilter;
    }

    @Override
    public NaruWorkspaceImpl toolFilter(Predicate<NaruTool> toolFilter) {
        this.toolFilter = toolFilter;
        return this;
    }

    @Override
    public Predicate<NaruToolTag> tagFilter() {
        return tagFilter;
    }

    @Override
    public NaruWorkspaceImpl tagFilter(Predicate<NaruToolTag> tagFilter) {
        this.tagFilter = tagFilter;
        return this;
    }

    @Override
    public NaruModelFilter modelFilter() {
        return modelFilter;
    }

    @Override
    public NaruWorkspaceImpl modelFilter(NaruModelFilter modelFilter) {
        this.modelFilter = modelFilter;
        return this;
    }

    /**
     * The pool that {@code NaruTask.onComplete} callbacks are dispatched on.
     * <p>
     * Package-visible rather than public: run handles are created by the session, which is the
     * only thing that should be choosing an executor for a user callback.
     */
    java.util.concurrent.Executor runCallbackExecutor() {
        return runCallbackExecutor;
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
    public NaruWorkspace projectDirectory(NPath projectDirectory) {
        this.projectDirectory = projectDirectory;
        registrations = new NaruRegistrationStore(
                projectDirectory.resolve(".naru/config/registrations.tson"),
                projectDirectory.resolve(".naru/local/config/registrations.tson")
        );
        projectEnv = new NaruProjectEnv(
                projectDirectory.resolve(".naru/config/env.tson"),
                projectDirectory.resolve(".naru/local/config/env.tson")
        );
        return this;
    }

    public NaruProjectEnv env() {
        return projectEnv;
    }

    @Override
    public NaruEnv agentEnv() {
        return agentEnv;
    }

    /**
     * Discovered once and kept: service loading walks the extension classloader, and the
     * catalog is queried per keystroke by any editor, so it must not re-load per call.
     * A provider's keys may still change between calls (dynamic keys), only the provider
     * set is cached.
     */
    private volatile List<NaruConfigKeyProvider> configKeyProviders;

    @Override
    public List<NaruConfigKey> configKeys() {
        List<NaruConfigKeyProvider> providers = configKeyProviders;
        if (providers == null) {
            List<NaruConfigKeyProvider> loaded = new ArrayList<>();
            loaded.add(new NaruCoreConfigKeyProvider());
            try {
                for (NaruConfigKeyProvider provider : NServiceLoader
                        .of(NaruConfigKeyProvider.class, null, classLoader().asClassLoader())
                        .loadAll(null)) {
                    boolean duplicate = false;
                    for (NaruConfigKeyProvider known : loaded) {
                        if (known.name().equals(provider.name())) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (!duplicate) {
                        loaded.add(provider);
                    }
                }
            } catch (RuntimeException ex) {
                // discovery must not break the catalog: the core keys alone are still an answer
                log(NaruLogMode.SCRIPT, NMsg.ofC(
                        "config-key providers unavailable: %s", ex.getMessage()));
            }
            configKeyProviders = loaded;
            providers = loaded;
        }
        // asked with a null session: session-scoped providers contribute nothing or throw,
        // and both are tolerated the same way NaruRegistryImpl.configKeys() tolerates them
        TreeMap<String, NaruConfigKey> byKey = new TreeMap<>();
        for (NaruConfigKeyProvider provider : providers) {
            try {
                List<NaruConfigKey> keys = provider.configKeys(null);
                if (keys != null) {
                    for (NaruConfigKey key : keys) {
                        if (key != null) {
                            byKey.putIfAbsent(key.key(), key);
                        }
                    }
                }
            } catch (RuntimeException e) {
                log(NaruLogMode.DEBUG, NMsg.ofC(
                        "config-key provider '%s' failed without a session: %s",
                        provider.name(), e.getMessage()));
            }
        }
        return new ArrayList<>(byKey.values());
    }

    public NaruRegistrationStore getRegistrations() {
        return registrations;
    }

    public NaruWorkspace logger(NLogger logger) {
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

    /**
     * The saved catalog, one per workspace.
     * <p>
     * Rebuilt whenever the project directory changes, because the catalog is disk state
     * rooted at that directory: keeping the old one would list the previous project's
     * sessions under the new project's name. Held otherwise, so every reader shares one
     * handle and a delete always hits the row that was listed.
     */
    @Override
    public NaruSessionStoreManager sessionCatalog() {
        NaruSessionStoreManager current = sessionCatalog;
        if (current != null && catalogProjectDirectory == projectDirectory) {
            return current;
        }
        synchronized (this) {
            if (sessionCatalog == null || catalogProjectDirectory != projectDirectory) {
                sessionCatalog = new NaruWorkspaceSessionCatalog(this);
                catalogProjectDirectory = projectDirectory;
            }
            return sessionCatalog;
        }
    }

    @Override
    public NClassLoader classLoader() {
        if (classLoader == null) {
            synchronized (this) {
                if (classLoader == null) {
                    // a set: a profile and an explicit --extension may name the same artifact, and it
                    // must be loaded once
                    NVersion currentVersion = apiVersion();
                    NMutableClassLoader nopsClassLoader = NMutableClassLoader.of("nops", Thread.currentThread().getContextClassLoader());
                    List<NId> loaded = new ArrayList<>();
                    Set<String> visited = new LinkedHashSet<>();
                    for (NId id : requestedExtensions) {
                        NIdBuilder bb = id.builder();
                        if (NBlankable.isBlank(bb.groupId())) {
                            bb.groupId(NET_THEVPC_NARU_EXTENSIONS);
                        }
                        if (NBlankable.isBlank(bb.artifactId())) {
                            NErr.println(NMsg.ofC("extension not found %s", id));
                            continue;
                        }
                        if (NET_THEVPC_NARU_EXTENSIONS.equals(bb.groupId())) {
                            if (!bb.artifactId().startsWith("naru-")) {
                                bb.artifactId("naru-" + bb.artifactId());
                            }
                        }
                        if (
                                !NBlankable.isBlank(bb.artifactId())
                                        && !NBlankable.isBlank(bb.groupId())
                        ) {
                            if (visited.contains(bb.shortName())) {
                                continue;
                            }
                        }
                        NId found = NSearch.of(bb.build())
                                .addDefinitionFilter(NDefinitionFilter.ofVersion(NVersionFilter.ofPredicate(
                                        x ->
                                                x.value().equals(currentVersion.value())
                                                        || x.value().startsWith(currentVersion.value() + ".")
                                )))
                                .latest(true)
                                .getResultIds().findFirst().orNull();
                        if (found == null) {
                            if (bb.version().isBlank()) {
                                found = NSearch.of(bb.build())
                                        .addDefinitionFilter(NDefinitionFilter.ofVersion(NVersionFilter.ofPredicate(
                                                x ->
                                                        x.value().equals(currentVersion.value())
                                                                || x.value().startsWith(currentVersion.value() + ".")
                                        )))
                                        .latest(true)
                                        .getResultIds().findFirst().orNull();
                            }
                            if (found == null) {
                                NErr.println(NMsg.ofC("extension not found %s", id));
                            } else {
                                if (visited.add(found.shortName())) {
                                    nopsClassLoader.add(found);
                                    loaded.add(found);
                                    NOut.println(NMsg.ofC("loaded extension %s", found));
                                }
                            }
                        } else {
                            if (visited.add(found.shortName())) {
                                nopsClassLoader.add(found);
                                loaded.add(found);
                                NOut.println(NMsg.ofC("loaded extension %s", found));
                            }
                        }
                    }
                    if (loaded.isEmpty()) {
                        NId found = NSearch.of(NId.of(DEFAULT_PROFILE))
                                .addDefinitionFilter(NDefinitionFilter.ofVersion(NVersionFilter.ofPredicate(
                                        x ->
                                                x.value().equals(currentVersion.value())
                                                        || x.value().startsWith(currentVersion.value() + ".")
                                )))
                                .latest(true)
                                .getResultIds().findFirst().get();
                        nopsClassLoader.add(found);
                        loaded.add(found);
                        log(NaruLogMode.TRACE, NMsg.ofC("loaded extension %s", found));
                    }
                    effectiveExtensions.addAll(loaded);
                    classLoader = nopsClassLoader;
                }
            }
        }
        return classLoader;
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

    @Override
    public String toString() {
        return "NaruWorkspace(" +
                (projectDirectory == null ? System.getProperty("user.dir") : projectDirectory.toString()) +
                ')';
    }
}
