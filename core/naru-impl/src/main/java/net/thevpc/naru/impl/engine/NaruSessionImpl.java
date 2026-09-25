package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.budget.NaruMeteringService;
import net.thevpc.naru.api.mode.NaruStandardMode;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.scheduler.*;
import net.thevpc.naru.api.skills.NaruSkillManager;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.api.registry.NaruRegistry;
import net.thevpc.naru.api.registry.NaruSessionExtension;
import net.thevpc.naru.impl.ia.budget.NaruMeteringServiceImpl;
import net.thevpc.naru.impl.registry.NaruRegistryImpl;
import net.thevpc.naru.impl.engine.routine.NaruRoutineMem;
import net.thevpc.naru.impl.engine.routine.RoutineHelper;
import net.thevpc.naru.impl.engine.scheduler.NaruSchedulerImpl;
import net.thevpc.naru.impl.engine.scheduler.NaruSessionEventLogImpl;
import net.thevpc.naru.impl.engine.scheduler.NaruTaskImpl;
import net.thevpc.naru.impl.ia.skill.NaruSkillManagerImpl;
import net.thevpc.naru.impl.util.ImplNaruUtils;
import net.thevpc.nuts.concurrent.NCallable;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.io.*;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.*;
import net.thevpc.nuts.collections.NMaps;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class NaruSessionImpl implements NaruSession, NToElement {
    private final NaruAgent agent;
    /**
     * public|private
     */
    private NAruVisibility visibility = NAruVisibility.PRIVATE;
    private NaruModelConfig model;
    private final Set<NPath> alreadyLoadedFiles = new HashSet<>();

    /**
     * Optional: additional context the user wants to share with every tool.
     */
    private final NaruSkillManager skillManager;
    private final NaruMeteringService meteringService;
    private final NaruSessionManagerImpl sessionManager;

    private String uuid = UUID.randomUUID().toString();
    private String name = "NO_NAME";
    private Instant creationInstant = Instant.now();
    private Instant modificationInstant = creationInstant;
    // Add to NaruSessionImpl fields:
    //use NOptional because ConcurrentHashMap cannot support null
    private final Map<String, NOptional<Object>> env = new ConcurrentHashMap<>();
    private final AtomicLong maxTaskId = new AtomicLong(0);
    private long foregroundTaskId;
    private final Map<Long, NaruTask> tasks = new ConcurrentHashMap<>();
    private NPath workingDir;
    /**
     * Root directory of the project being worked on.
     */
    private NPath projectDir;
    private final NaruScheduler scheduler;
    // session has one dedicated readline thread, parked until needed
    private final BlockingQueue<NaruTask> inputRequests = new LinkedBlockingQueue<>();
    private Thread readlineThread;
    private boolean readlineThreadRunning = true;
    private boolean running = false;
    private final NaruRegistry registry;
    private String systemPrompt;
    private final NaruSessionEventLog eventLog;
    private final NaruSessionListener sessionListener;
    private final List<NaruSessionListener> sessionListeners = new ArrayList<>();
    private boolean stopped;
    private final Map<String, NaruRoutine> routines = new ConcurrentHashMap<>();
    private NAruVisibility loadTimeVisibility;
    private int schedulerThreadCount = 1;
    private volatile long schedulerThrottleDelayMs = 500;
    /**
     * Rows displayed by the last '/model' listing, in display order. Kept so that
     * '/model use <n>' resolves against the very same rows the user saw, even when
     * the listing was filtered (--free, --provider, keyword) and therefore renumbered.
     */
    private volatile List<NaruModelKey> listedModels = Collections.emptyList();


    public NaruSessionImpl(NaruAgent agent, NPath projectDir, NaruMeteringService meteringService, boolean configureDefaults
            , NaruSessionListener sessionListener
            , Predicate<NaruDirective> directiveFilter
            , Predicate<NaruTool> toolFilter
            , Predicate<NaruToolTag> tagFilter
    ) {
        this.agent = agent;
        this.projectDir = projectDir.normalize();
        this.workingDir = projectDir.normalize();
        this.meteringService = meteringService == null ? new NaruMeteringServiceImpl() : meteringService;
        this.sessionManager = new NaruSessionManagerImpl(this);
        this.skillManager = new NaruSkillManagerImpl(this);
        this.registry = new NaruRegistryImpl(this,directiveFilter, toolFilter, tagFilter);
        this.sessionListener = sessionListener;
        NaruModelConfig model0 = null;
        NaruModelConfig model = model0;
        if (model == null) {
            model = getProjectEnv("model").flatMap(x -> findModel(new NaruModelConfig(x))).orNull();
        } else {
            model = findModel(model0).orNull();
        }
        if (model == null) {
            List<NaruModelInfo> any = registry.modelsInfos(this)
                    .stream().filter(x -> x.capabilities().isTools()).collect(Collectors.toList());
            if (any.isEmpty()) {
                if (model0 == null) {
                    NOut.println(NMsg.ofC("no model (with tools capability) was found at all", model0).asError());
                } else {
                    NOut.println(NMsg.ofC("model %s not found. actually no model (with tools capability) was found at all", model0).asError());
                }
            } else {
                model = findModel(any.get(0).key().toString()).orNull();
                if (model == null) {
                    NOut.println(NMsg.ofC("model %s not found.", model0).asWarning());
                } else {
                    NOut.println(NMsg.ofC("model %s not found. auto select %s", model0, model.toText()).asWarning());
                }
            }
        }
        this.model = model;
        this.scheduler = new NaruSchedulerImpl(this);
        this.eventLog = new NaruSessionEventLogImpl(new NaruEventLogListener() {
            @Override
            public void onEventAppended(NaruEvent newEvent) {
                NaruTask t = findTask(newEvent.sourceTid()).orNull();
                if (t != null) {
                    t.log(NaruLogMode.SCHEDULER, NMsg.ofC("[%s] fire %s", newEvent.sourceTid(), newEvent));
                } else {
                    log(NaruLogMode.SCHEDULER, NMsg.ofC("[%s/dead] fire %s", newEvent.sourceTid(), newEvent));
                }
                sessionListener.onEventAppended(newEvent);
                for (NaruSessionListener listener : sessionListeners) {
                    listener.onEventAppended(newEvent);
                }
            }
        });
        if (configureDefaults) {
            ((NaruRegistryImpl) registry).registerDefaults();
        }
    }

    @Override
    public NaruSession throttleDelay(long ms) {
        this.schedulerThrottleDelayMs = ms;
        return this;
    }

    public int getSchedulerThreadCount() {
        return schedulerThreadCount;
    }

    public NaruSessionImpl setSchedulerThreadCount(int schedulerThreadCount) {
        this.schedulerThreadCount = schedulerThreadCount;
        return this;
    }

    public long getSchedulerThrottleDelayMs() {
        return schedulerThrottleDelayMs;
    }

    public NaruSessionImpl setSchedulerThrottleDelayMs(long schedulerThrottleDelayMs) {
        this.schedulerThrottleDelayMs = schedulerThrottleDelayMs;
        return this;
    }

    private void ensureNotStopped() {
        if (stopped) {
            throw new IllegalStateException("session is stopped");
        }
    }

    public NaruScheduler scheduler() {
        ensureNotStopped();
        return scheduler;
    }

    @Override
    public NAruVisibility getVisibility() {
        return visibility;
    }

    @Override
    public NaruSession setVisibility(NAruVisibility visibility) {
        ensureNotStopped();
        NAssert.requireNamedNonNull(visibility, "visibility");
        if (visibility != NAruVisibility.PUBLIC && visibility != NAruVisibility.PRIVATE) {
            NAssert.requireNamedTrue(false, "valid visibility");
        }
        this.visibility = visibility;
        return this;
    }

    // Accessors
    public NaruSession unsetSessionEnv(String key) {
        ensureNotStopped();
        if (env.containsKey(key)) {
            env.remove(key);
            fireChanged();
        }
        return this;
    }

    @Override
    public List<NaruTask> tasks() {
        ensureNotStopped();
        return new ArrayList<>(tasks.values());
    }

    @Override
    public NaruTask newTask(NaruTaskSpec taskBuilder) {
        ensureNotStopped();
        long parentId = taskBuilder.parentId();
        NPath cwd = taskBuilder.workingDirectory();
        NaruTask parent = tasks.get(parentId);
        long id = maxTaskId.incrementAndGet();
        NaruTaskImpl natuTask = new NaruTaskImpl(id, parent == null ? -1 : parent.id(), this);
        natuTask.name(taskBuilder.name());
        if (parent == null) {
            natuTask._setInputMode(NAruInputMode.LINE);
            natuTask._setWorkingDir(cwd == null ? workingDir : cwd);
            natuTask._setProjectDir(projectDir);
            natuTask._setMode(NUtils.firstNonNull(taskBuilder.promptMode(), registry().mode(NaruStandardMode.PLANNING).get()));
            natuTask._setInputBuffer("");
            natuTask._setLastResult(null);
            natuTask._setReturnResult(null);
            natuTask._setModel(model);
            natuTask._setSkills(new HashSet<>());
        } else {
            natuTask._setInputMode(NAruInputMode.LINE);
            natuTask._setWorkingDir(cwd == null ? parent.workingDir() : cwd);
            natuTask._setProjectDir(parent.projectDir());
            // an explicit mode on the spec wins over the inherited one, so that a
            // plan can spawn implement-type executors from a PLANNING parent
            natuTask._setMode(NUtils.firstNonNull(taskBuilder.promptMode(), parent.promptMode(), registry().mode(NaruStandardMode.PLANNING).get()));
            natuTask._setInputBuffer("");
            natuTask._setLastResult(null);
            natuTask._setReturnResult(null);
            natuTask._setModel(parent.model());
            natuTask._setSkills(parent.skillNames());
        }
        // tags are never inherited: a task only sees a tagged tool when it holds
        // one of that tool's tags, so grant them explicitly
        for (String tag : taskBuilder.toolTags()) {
            natuTask.addToolTag(tag);
        }
        natuTask.addSystemHistory(s->NaruMessage.system(buildSystemPrompt(s)));
        natuTask._prependInitHooks();
        natuTask.addStatements(
                taskBuilder.statements().stream().map(x -> natuTask.parseStatement(x).get().injected(true)).collect(Collectors.toList())
                        .stream().map(x -> x.injected(true)).toArray(NaruStatement[]::new));
        tasks.put(id, natuTask);
        return natuTask;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private String buildSystemPrompt(NaruTask task) {
        String s = systemPrompt();
        if (NBlankable.isBlank(s)) {
            StringBuilder sb = new StringBuilder();
            sb.append("You are NARU (Nuts AI Reasoning Unit), a durable actor-model based multi-task agent.\n");
            if (task.projectDir() != null) {
                sb.append("Project directory: ").append(task.projectDir()).append('\n');
            }
            if (!registry().isEmpty()) {
                sb.append("Available tools: ").append(task.findTools().stream().map(NaruToolDefinition::getName).toList()).append('\n');
            }
            return sb.toString();
        }
        return s.trim();
    }


    @Override
    public NOptional<NaruTask> findTask(long tid) {
        ensureNotStopped();
        return NOptional.ofNamed(tasks.get(tid), "task " + tid);
    }

    public NaruSession setSessionEnv(String key, Object value) {
        ensureNotStopped();
        if (env.containsKey(key) && Objects.equals(env.get(key).orNull(), value)) {
            return this;
        }

        env.put(key, NOptional.ofNullable(value));
        fireChanged();
        return this;
    }

    public NOptional<Object> getSessionEnv(String key) {
        if (env.containsKey(key)) {
            Object u = env.get(key).get();
            return NOptional.ofNullable(u);
        }
        return NOptional.ofNamedEmpty(key);
    }

    @Override
    public NOptional<NElement> getProjectEnv(String key) {
        ensureNotStopped();
        NaruEnv a = agent.env();
        return a.get(key);
    }

    @Override
    public void setProjectEnv(String key, NElement value, NAruVisibility visibility) {
        ensureNotStopped();
        NaruEnv a = agent.env();
        a.put(key, value, visibility);
    }


    @Override
    public String name() {
        ensureNotStopped();
        return name;
    }

    @Override
    public NaruSession setName(String name) {
        ensureNotStopped();
        this.name = NStringUtils.firstNonBlankStripped(name, "NO_NAME");
        this.fireChanged();
        return this;
    }


    @Override
    public Map<String, NaruModelConfig> modelAliases() {
        ensureNotStopped();
        return ((NaruAgentImpl) agent).getModelAliases().toMap();
    }


    @Override
    public Map<NaruModelConfig, List<String>> reversedModelAliases() {
        ensureNotStopped();
        HashMap<NaruModelConfig, List<String>> m = new HashMap<>();
        for (Map.Entry<String, NaruModelConfig> e : modelAliases().entrySet()) {
            NaruModelConfig k = e.getValue();
            List<String> v = m.computeIfAbsent(k, k1 -> new ArrayList<>());
            v.add(e.getKey());
        }
        return m;
    }

    public NOptional<NaruModelConfig> findModel(NaruModelConfig keyOrName) {
        ensureNotStopped();
        if (keyOrName == null) {
            return NOptional.ofNamedEmpty(NMsg.ofC("model"));
        }
        if (!NBlankable.isBlank(keyOrName.name())) {
            NOptional<NaruModelConfig> u = findModel(keyOrName.name());
            if (u.isPresent()) {
                return u;
            }
        }
        String p = keyOrName.provider();
        String n = keyOrName.name();
        if (NBlankable.isBlank(p)) {
            return findModel(n);
        } else if (NBlankable.isBlank(n)) {
            return NOptional.ofNamedEmpty(NMsg.ofC("model"));
        } else {
            return findModel(p + "/" + n);
        }
    }

    public NOptional<NaruModelConfig> findModel(String keyOrName) {
        ensureNotStopped();
        String ref = keyOrName == null ? null : NStringUtils.stripToNull(keyOrName);
        if (ref == null) {
            return NOptional.ofNamedEmpty(NMsg.ofC("model"));
        }
        // single provider round: modelsKeys() is network bound, never call it twice
        List<NaruModelKey> catalog = registry().modelsKeys(this);
        // 1. explicit alias ("/model alias foo=openrouter/x", then "/model use foo")
        NaruModelConfig a = findModelAlias(ref).orNull();
        if (a != null && catalog.contains(a.key())) {
            return NOptional.of(a);
        }
        // 2. fully qualified key "provider/model"
        if (ref.contains("/")) {
            NOptional<NaruModelConfig> r = NaruModelKey.parse(ref).map(NaruModelConfig::new);
            if (r.isPresent() && catalog.contains(r.get().key())) {
                return r;
            }
        } else {
            // 3. exact model name
            for (NaruModelKey k : catalog) {
                if (k.model().equals(ref)) {
                    return NOptional.of(new NaruModelConfig(k));
                }
            }
        }
        // 4. positional index. The last '/model' listing wins because its own numbering
        //    (possibly filtered) is what the user is referring to. Only when no listing is
        //    available do we fall back to the raw catalog order.
        Integer idx = NLiteral.of(ref).asInt().orNull();
        if (idx != null) {
            int i = idx - 1;
            List<NaruModelKey> listed = listedModels;
            if (!listed.isEmpty()) {
                // the listing is authoritative: never silently fall through to another
                // row, otherwise an out-of-range index selects an unrelated model.
                if (i >= 0 && i < listed.size()) {
                    return NOptional.of(new NaruModelConfig(listed.get(i)));
                }
            } else if (i >= 0 && i < catalog.size()) {
                return NOptional.of(new NaruModelConfig(catalog.get(i)));
            }
        }
        return NOptional.ofNamedEmpty(NMsg.ofC("model '%s'", keyOrName));
    }

    @Override
    public List<NaruModelKey> listedModels() {
        ensureNotStopped();
        return listedModels;
    }

    @Override
    public NaruSession setListedModels(List<NaruModelKey> models) {
        ensureNotStopped();
        this.listedModels = models == null ? Collections.emptyList() : List.copyOf(models);
        return this;
    }

    @Override
    public void removeModelAlias(String alias) {
        ensureNotStopped();
        alias = NStringUtils.stripToNull(alias);
        ((NaruAgentImpl) agent).getModelAliases().remove(alias);
        fireChanged();
    }

    @Override
    public void addModelAlias(String alias, NaruModelConfig model) {
        ensureNotStopped();
        alias = NStringUtils.stripToNull(alias);
        if (!NBlankable.isBlank(alias)) {
            if (model != null) {
                ((NaruAgentImpl) agent).getModelAliases().put(alias, model);
                fireChanged();
            }
        }
    }

    @Override
    public NOptional<NaruModelConfig> findModelAlias(String alias) {
        ensureNotStopped();
        return ((NaruAgentImpl) agent).getModelAliases().get(alias);
    }

    @Override
    public Instant creationInstant() {
        ensureNotStopped();
        return creationInstant;
    }

    @Override
    public Instant modificationInstant() {
        ensureNotStopped();
        return modificationInstant;
    }

    @Override
    public String uuid() {
        return uuid;
    }

    public void fireChanged() {
//        ensureNotStopped();
        this.modificationInstant = Instant.now();
        saveSnapshot();
    }

    private NaruSession loadFolder(NPath folder) {
        ensureNotStopped();
        NPath path = folder.resolve("session.tson");
        NElement element = NElementReader.ofTson().read(path);
        ensureNotStopped();
        NObjectElement o = element.asObject().get();
        this.uuid = NStringUtils.firstNonBlankStripped(o.getStringValue("uuid").orElse(null), UUID.randomUUID().toString());
        this.name = NStringUtils.firstNonBlankStripped(o.getStringValue("name").orElse(null), "NO_NAME");
        this.creationInstant = NUtils.firstNonNull(o.getInstantValue("creationDate").orElse(null), Instant.now());
        this.modificationInstant = NUtils.firstNonNull(o.getInstantValue("modificationDate").orElse(null), creationInstant);
        this.visibility = NAruVisibility.parse(o.getStringValue("visibility").orElse(null)).orElse(NAruVisibility.PRIVATE);
        if (this.visibility != NAruVisibility.PRIVATE && this.visibility != NAruVisibility.PUBLIC) {
            this.visibility = NAruVisibility.PRIVATE;
        }
        NElement mv = o.get("model").orElse(null);
        this.model = mv == null || mv.isNull() ? null : new NaruModelConfig(mv);
        this.projectDir = o.getStringValue("projectDir").map(x -> NPath.of(x)).orElse(projectDir);
        this.workingDir = o.getStringValue("workingDir").map(x -> NPath.of(x)).orElse(workingDir);
        NListContainerElement env1 = o.get("env").flatMap(x -> x.isNull() ? null : x.asListContainer()).orNull();
        env.clear();
        if (env1 != null) {
            for (NPairElement nElement : env1.asListContainer().get().namedPairs()) {
                env.put(nElement.key().asStringValue().orNull(),
                        NOptional.ofNullable(NElement.simpleOf(nElement.value()))
                );
            }
        }

        routines.clear();
        path.resolveSibling("routines").list().stream().filter(x -> x.name().endsWith(".tson")).forEach(x -> {
            String n = x.name().substring(0, x.name().length() - 5);
            NaruRoutineMem r = loadRoutineTson(x);
            if (r != null) {
                routines.put(n, r);
            }
        });


        tasks.clear();
        NLongRef maxLong = NRef.ofLong(0);
        path.resolveSibling("tasks").list().stream().filter(x -> x.name().endsWith(".tson")).forEach(x -> {
            NElement elem = NElementReader.ofTson().ntf(false).read(x);
            NaruTaskImpl t = new NaruTaskImpl(elem, this);
            maxLong.set(Math.max(maxLong.get(), t.id()));
            tasks.put(t.id(), t);
        });
        long finalMaxLong = maxLong.get() == 0 ? 0 : maxLong.get() + 1;
        maxTaskId.updateAndGet(current -> Math.max(current, finalMaxLong));
        loadSessionExtensions(folder);
        return this;
    }

    /**
     * Hands each installed feature extension its slice of the session folder. Extensions
     * own their state file, so the core neither knows nor cares what is in it.
     */
    private void loadSessionExtensions(NPath folder) {
        NPath extDir = folder.mkdirs().resolve("ext");
        for (NaruSessionExtension extension : registry.sessionExtensions()) {
            try {
                extension.load(this, extDir.resolve(extension.name() + ".tson"));
                extension.open(this);
            } catch (Exception ex) {
                // one broken extension must not cost the user the whole session
                log(NaruLogMode.SCRIPT, NMsg.ofC(
                        "session extension '%s' failed to load: %s", extension.name(), ex.getMessage()).asError());
            }
        }
    }

    private static NaruRoutineMem loadRoutineTson(NPath x) {
        NaruRoutineMem r = null;
        try {
            r = new NaruRoutineMem(NElementReader.ofTson().ntf(false).read(x));
        } catch (Exception ex) {
            //
        }
        return r;
    }

    private static NaruRoutineMem loadRoutineText(NPath x) {
        NaruRoutineMem r = null;
        try {
            r = new NaruRoutineMem(NElementReader.ofTson().ntf(false).read(x));
        } catch (Exception ex) {
            //
        }
        return r;
    }


    @Override
    public NaruSession copy() {
        ensureNotStopped();
        this.uuid = UUID.randomUUID().toString();
        this.sessionListener.onSessionReloaded(this);
        for (NaruSessionListener listener : sessionListeners) {
            listener.onSessionReloaded(this);
        }
        return this;
    }

    @Override
    public NaruSession reset(boolean preserveIdentity) {
        ensureNotStopped();
        if (!preserveIdentity) {
            this.uuid = UUID.randomUUID().toString();
            this.name = "NO_NAME";
            this.creationInstant = Instant.now();
            this.modificationInstant = creationInstant;
        }
        //clearHistory();
        maxTaskId.set(0);
        saveSnapshot();
        sessionListener.onSessionReloaded(this);
        for (NaruSessionListener listener : sessionListeners) {
            listener.onSessionReloaded(this);
        }
        return this;
    }

    private <T> T stopTheWorldAndWait(NCallable<T> e) {
        Future<T> f = stopTheWorldAndDo(e);
        try {
            return f.get();
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        } catch (ExecutionException ex) {
            throw new RuntimeException(ex);
        }
    }

    private <T> Future<T> stopTheWorldAndDo(NCallable<T> e) {
//        ensureNotStopped();
        return ((NaruAgentImpl) agent).postAction(
                () -> {
                    if (!scheduler.isHeld()) {
                        log(NaruLogMode.SCHEDULER, NMsg.ofC("Stop the world..."));
                        scheduler.hold();
                        T a = e.call();
                        scheduler.resume();
                        log(NaruLogMode.SCHEDULER, NMsg.ofC("Resume the world..."));
                        return a;
                    } else {
                        return e.call();
                    }
                }
        );
    }

    public NaruSession saveSnapshot() {
        stopTheWorldAndDo(() -> {
            NPath snapshotFolder = snapshotFile().parent();
            saveFolder(snapshotFolder);
            return null;
        });
        return this;
    }

    @Override
    public NaruSession save() {
        stopTheWorldAndWait(() -> {
            NPath publicFolder = projectDir.resolve(".naru/sessions/" + uuid());
            NPath privateFolder = projectDir.resolve(".naru/local/sessions/" + uuid());
            if (getVisibility() == NAruVisibility.PUBLIC) {
                saveFolder(publicFolder);
                if (privateFolder.exists()) {
                    privateFolder.deleteTree();
                }
            } else {
                saveFolder(privateFolder);
                if (publicFolder.exists()) {
                    publicFolder.deleteTree();
                }
            }
            return null;
        });
        return this;
    }

    private void saveFolder(NPath folder) {
        NElementWriter.ofTson().ntf(false).formatter(NElementFormatterStyle.PRETTY)
                .write(toElement(), folder.mkdirs().resolve("session.tson"));
        NPath r = folder.resolve("routines");
        r.mkdirs();
        r.list().stream().filter(x -> x.name().endsWith(".tson")).forEach(x -> x.delete());
        for (Map.Entry<String, NaruRoutine> e : routines.entrySet()) {
            NElementWriter.ofTson().ntf(false).formatter(NElementFormatterStyle.PRETTY)
                    .write(e.getValue().toElement(), r.resolve(e.getKey() + ".tson"));
        }
        r = folder.resolve("tasks");
        r.mkdirs();
        r.list().stream().filter(x -> x.name().endsWith(".tson")).forEach(x -> x.delete());
        for (Map.Entry<Long, NaruTask> e : tasks.entrySet()) {
            NElementWriter.ofTson().ntf(false).formatter(NElementFormatterStyle.PRETTY)
                    .write(e.getValue().toElement(), r.resolve(e.getKey() + ".tson"));
        }
        saveSessionExtensions(folder);
    }

    /**
     * Asks each installed feature extension to snapshot its state. Note this runs on
     * every snapshot, not only on a user-initiated save, so extension save hooks are
     * expected to be cheap and idempotent.
     */
    private void saveSessionExtensions(NPath folder) {
        NPath extDir = folder.mkdirs().resolve("ext");
        for (NaruSessionExtension extension : registry.sessionExtensions()) {
            try {
                NElement state = extension.save(this);
                NPath file = extDir.resolve(extension.name() + ".tson");
                if (state == null) {
                    // an extension with nothing to persist must not leave a stale file
                    // behind, or a later load would resurrect discarded state
                    if (file.exists()) {
                        file.delete();
                    }
                } else {
                    NElementWriter.ofTson().ntf(false).formatter(NElementFormatterStyle.PRETTY)
                            .write(state, file);
                }
            } catch (Exception ex) {
                // failing to snapshot one extension must not fail the whole session save
                log(NaruLogMode.SCRIPT, NMsg.ofC(
                        "session extension '%s' failed to save: %s", extension.name(), ex.getMessage()).asError());
            }
        }
    }

    public NaruSession load(String otherUuid) {
        stopTheWorldAndWait(() -> {
            NPath publicFolder = publicFolder(otherUuid);
            NPath privateFolder = privateFolder(otherUuid);

            if (isValidSessionFolder(privateFolder)) {
                loadFolder(privateFolder);
                setVisibility(NAruVisibility.PRIVATE);
                this.loadTimeVisibility = NAruVisibility.PRIVATE;
            } else if (isValidSessionFolder(publicFolder)) {
                loadFolder(publicFolder);
                setVisibility(NAruVisibility.PUBLIC);
                this.loadTimeVisibility = NAruVisibility.PUBLIC;
            } else {
                throw new NIllegalArgumentException(NMsg.ofC("Session '%s' not found", otherUuid));
            }
            ((NaruSchedulerImpl) scheduler).reloadState();
            return this;
        });
        sessionListener.onSessionReloaded(this);
        for (NaruSessionListener listener : sessionListeners) {
            listener.onSessionReloaded(this);
        }
        return this;
    }

//    private NPath publicFile(String uuid) {
//        return projectDir.resolve(".naru/sessions/" + uuid + "/session.tson");
//    }
//    private NPath privateFile(String uuid) {
//        return projectDir.resolve(".naru/local/sessions/" + uuid + "/session.tson");
//    }

    private boolean isValidSessionFolder(NPath path) {
        return path.resolve("session.tson").isFile();
    }

    private NPath publicFolder(String uuid) {
        return projectDir.resolve(".naru/sessions/" + uuid);
    }

    private NPath privateFolder(String uuid) {
        return projectDir.resolve(".naru/local/sessions/" + uuid);
    }


    @Override
    public NaruSession reload() {
        stopTheWorldAndWait(() -> {
            NPath publicFolder = publicFolder(uuid());
            NPath privateFolder = publicFolder(uuid());
            if (loadTimeVisibility == null) {
                if (isValidSessionFolder(privateFolder)) {
                    loadFolder(privateFolder);
                    setVisibility(NAruVisibility.PRIVATE);
                    this.loadTimeVisibility = NAruVisibility.PRIVATE;
                } else {
                    if (isValidSessionFolder(publicFolder)) {
                        loadFolder(publicFolder);
                        setVisibility(NAruVisibility.PUBLIC);
                        this.loadTimeVisibility = NAruVisibility.PUBLIC;
                    } else {
                        this.uuid = UUID.randomUUID().toString();
                        this._prepareInit();
                    }
                }
            } else if (loadTimeVisibility == NAruVisibility.PUBLIC) {
                if (isValidSessionFolder(publicFolder)) {
                    loadFolder(publicFolder);
                    setVisibility(NAruVisibility.PUBLIC);
                    this.loadTimeVisibility = NAruVisibility.PUBLIC;
                } else {
                    this.uuid = UUID.randomUUID().toString();
                    this._prepareInit();
                }
            } else {
                if (isValidSessionFolder(privateFolder)) {
                    loadFolder(privateFolder);
                    setVisibility(NAruVisibility.PRIVATE);
                    this.loadTimeVisibility = NAruVisibility.PRIVATE;
                } else {
                    this.uuid = UUID.randomUUID().toString();
                    this._prepareInit();
                }
            }
            ((NaruSchedulerImpl) scheduler).reloadState();
            return null;
        });
        sessionListener.onSessionReloaded(this);
        for (NaruSessionListener listener : sessionListeners) {
            listener.onSessionReloaded(this);
        }
        return this;
    }

    public NaruSession restoreSnapshot() {
        Boolean b = stopTheWorldAndWait(() -> {
            NPath snapshotFile = snapshotFile();
            if (snapshotFile.isFile()) {
                loadFolder(snapshotFile);
                ((NaruSchedulerImpl) scheduler).reloadState();
                return true;
            }
            return false;
        });
        if (b) {
            sessionListener.onSessionReloaded(this);
            for (NaruSessionListener listener : sessionListeners) {
                listener.onSessionReloaded(this);
            }
        }
        return this;
    }

    private NPath snapshotFile() {
        return projectDir().resolve(".naru/local/sessions/snapshot/session.tson");
    }

    private void _prepareInit() {
        this.name = "NO_NAME";
        this.creationInstant = Instant.now();
        this.modificationInstant = creationInstant;
        this.maxTaskId.set(0);
        this.loadTimeVisibility = NAruVisibility.PRIVATE;
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder o = NObjectElementBuilder.of();
        o.set("uuid", uuid());
        o.set("name", name());
        o.set("creationDate", NElement.ofInstant(creationInstant));
        o.set("modificationDate", NElement.ofInstant(modificationInstant));
        o.set("model", model == null ? null : model.toElement());
        o.set("projectDir", NElement.ofString(projectDir.toString()));
        o.set("workingDir", workingDir == null ? null : NElement.ofString(workingDir.toString()));
        NObjectElementBuilder _props = NObjectElementBuilder.of();
        for (Map.Entry<String, NOptional<Object>> e : env.entrySet()) {
            _props.add(e.getKey(), NElement.of(e.getValue().orNull()));
        }
        o.set("env", _props.build());
        return o.build();
    }

    // readline thread
    public void deliverInput(String line) {
        ensureNotStopped();
        NaruTask fg = findTask(foregroundTaskId).orNull();
        if (fg == null) {
            handleSessionCommand(line);
            return;
        }
        if (fg.status() == NaruTaskStatus.BLOCKED_ON_INPUT) {
            ((NaruTaskSchedulerView) fg).deliverInput(line);// store in task
            ((NaruSchedulerImpl) scheduler).ready(fg.id());
        }
    }

    // called by scheduler when task becomes BLOCKED_ON_INPUT
    public void onInputRequested(NaruTask task) {
        ensureNotStopped();
        inputRequests.add(task);
    }

    private void handleSessionCommand(String line) {
        ensureNotStopped();
        //
    }

    @Override
    public void log(NaruLogMode mode, NMsg s) {
        if (mode == NaruLogMode.SCHEDULER && !isTrace()) {
            return;
        }
        agent.log(mode, s);
    }

    private boolean isTrace() {
        return getSessionEnv("trace").map(x -> NLiteral.of(x).asBoolean().orNull()).orElse(false);
    }


    public void _reportUsing(NPath source) {
        if (alreadyLoadedFiles.add(source)) {
            log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loading agent from %s", source));
        }
    }

    public List<NPath> listOverridablePaths(NPath publicPath, NPath privatePath, Predicate<String> nameFilter) {
        ensureNotStopped();
        Map<String, NPath> publicFiles = publicPath.list().stream().filter(x -> x.isRegularFile() && nameFilter.test(x.name())).collect(Collectors.toMap(x -> x.name(), x -> x));
        Map<String, NPath> privateFiles = privatePath.list().stream().filter(x -> x.isRegularFile() && nameFilter.test(x.name())).collect(Collectors.toMap(x -> x.name(), x -> x));
        Map<String, NPath> all = new HashMap<>();
        all.putAll(publicFiles);
        all.putAll(privateFiles);
        return all.values().stream().sorted(Comparator.comparing(NPath::name)).collect(Collectors.toList());
    }

    private List<NaruMessage> loadAgentClassPath(String[] agentFileNames) {
        ensureNotStopped();
        Set<ClassLoader> classLoaders = new LinkedHashSet<>(Arrays.asList(
                getClass().getClassLoader(),
                Thread.currentThread().getContextClassLoader()
        ));
        StringBuilder sb = new StringBuilder();
        Set<String> validNames = new TreeSet<>();
        for (String agentFileName : agentFileNames) {
            String r = "META-INF/naru/agents/" + agentFileName;
            for (ClassLoader classLoader : classLoaders) {
                URL u = classLoader.getResource(r);
                if (u != null) {
                    byte[] b = NIOUtils.readBytes(u);
                    if (b.length > 0) {
                        if (alreadyLoadedFiles.add(NPath.of(u))) {
                            log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loading agent from %s", NPath.of(u)));
                        }
                        String content = new String(b, StandardCharsets.UTF_8).trim();
                        if (!NBlankable.isBlank(content)) {
                            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                                sb.append("\n");
                            }
                            validNames.add(u.toString());
                            sb.append(content);
                        }
                    }
                }
            }
        }
        if (sb.toString().trim().length() > 0) {
            return Collections.singletonList(NaruMessage.user(
                    "### AGENT CLASSPATH:\n" + sb
            ).setSource(NaruSource.CLASSPATH).setSourceName(validNames.size() == 1 ? validNames.stream().findFirst().get() : validNames.toString()));
        }
        return new ArrayList<>();
    }


    public NPath workingDir() {
        ensureNotStopped();
        return workingDir;
    }

    public NaruSession setWorkingDir(NPath workingDir) {
        ensureNotStopped();
        NPath nf = workingDir.toAbsolute(this.workingDir);
        if (!nf.equals(this.workingDir)) {
            this.workingDir = nf;
            fireChanged();
        }
        return this;
    }


    @Override
    public NPath projectDir() {
        ensureNotStopped();
        return projectDir;
    }

    @Override
    public NaruSkillManager skillManager() {
        ensureNotStopped();
        return skillManager;
    }

    @Override
    public NaruSession terminate() {
        // Idempotent, like stop(): terminating an already stopped/terminated session
        // is a no-op instead of throwing (ensureNotStopped would throw).
        if (stopped) {
            return this;
        }
        for (Map.Entry<Long, NaruTask> e : new HashMap<>(tasks).entrySet()) {
            e.getValue().kill();
        }
        return this;
    }

    public boolean hasMoreStatements() {
        ensureNotStopped();
        for (Map.Entry<Long, NaruTask> e : new HashMap<>(tasks).entrySet()) {
            if (e.getValue().hasMoreStatements()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public NaruAgent agent() {
        ensureNotStopped();
        return agent;
    }

    @Override
    public NaruSessionManager sessionManager() {
        ensureNotStopped();
        return sessionManager;
    }

    @Override
    public NaruRegistry registry() {
        ensureNotStopped();
        return registry;
    }

    public NaruMeteringService meteringService() {
        ensureNotStopped();
        return meteringService;
    }

    @Override
    public long foregroundTaskId() {
        ensureNotStopped();
        return foregroundTaskId;
    }

    @Override
    public NaruSession foregroundTaskId(long taskId) {
        ensureNotStopped();
        this.foregroundTaskId = taskId;
        return this;
    }


    // readline thread loop
    private void readlineLoop() {
        ensureNotStopped();
        while (readlineThreadRunning) {
            NaruTask task = null; // parks here until request arrives
            try {
                task = inputRequests.take();
            } catch (InterruptedException e) {
                break;
            }
            NaruTaskSchedulerView t = (NaruTaskSchedulerView) task;
            NMsg prompt = t.pendingPrompt();
            String line = null;
            try {
                line = NTerminal.of().readLine(prompt); // blocks until user types
            } catch (Exception ex) {
                //
            }
            if (line == null) {
                continue;
            }
            t.deliverInput(line);
            t.status(NaruTaskStatus.READY);
            ((NaruSchedulerImpl) scheduler).enqueue(task);
        }
    }


    @Override
    public void start() {
        ensureNotStopped();
        if (running) {
            return;
        }
        running = true;
        // start readline thread
        readlineThread = new Thread(this::readlineLoop, "naru-readline");
        // daemon: this thread only services interactive input requests. It must never
        // keep the JVM alive after the work of a batch session is done, otherwise the
        // process (and any test using it) never exits.
        readlineThread.setDaemon(true);
        readlineThread.start();

        // start scheduler (its workers)
        scheduler.start();
        sessionListener.sessionStarted(this);
        for (NaruSessionListener listener : sessionListeners) {
            listener.sessionStarted(this);
        }
    }

    @Override
    public boolean isRunning() {
        return running && !stopped;
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        if (stopped) {
            return;
        }
        stopped = true;
        scheduler.shutdown();
        readlineThreadRunning = false;
        readlineThread.interrupt();
        running = false;
        sessionListener.sessionStopped(this);
        for (NaruSessionListener listener : sessionListeners) {
            listener.sessionStopped(this);
        }
    }

    @Override
    public void addSessionListener(NaruSessionListener listener) {
        ensureNotStopped();
        if (listener != null) {
            sessionListeners.add(listener);
        }
    }

    @Override
    public void removeSessionListener(NaruSessionListener listener) {
        ensureNotStopped();
        if (listener != null) {
            sessionListeners.remove(listener);
        }
    }

    @Override
    public void waitFor() {
        // Note: intentionally not calling ensureNotStopped() here. In batch usage the
        // session commonly finishes (and therefore stops itself) before waitFor() is
        // even called; that race must simply return instead of throwing.
        try {
            // wait for scheduler workers to terminate
            scheduler.awaitTermination();
            // defensive, bounded join: stop() interrupts the readline thread so it
            // should exit promptly. The bound guarantees waitFor() can never hang
            // forever if the terminal read is not interruptible.
            if (readlineThread != null) {
                readlineThread.join(10_000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String systemPrompt() {
        return systemPrompt;
    }

    @Override
    public NaruSession systemPrompt(String systemPrompt) {
        ensureNotStopped();
        this.systemPrompt = systemPrompt;
        return this;
    }

    public void onTerminated(long tid) {
        ensureNotStopped();
        if (foregroundTaskId() == tid) {
            foregroundTaskId(-1);
        }
        if (tasks.containsKey(tid)) {
            NaruTask i = tasks.remove(tid);
            Map<String, Object> payload = NMaps.of(
                    "status", i.status().name(),
                    "name", i.name()
            );
            ((NaruSchedulerImpl) scheduler).onTerminated(tid);
            eventLog.append(new NaruEvent(
                    NaruEvent.TASK_TERMINATED,
                    payload, tid, i.parentId(), Instant.now(), NaruEventTargets.ofEveryone(), NaruRetentionPolicies.ofDefault()));
            if (tasks.isEmpty()) {
                stop();
            }
        }
    }

    /**
     * Dispatches a task status transition to session listeners.
     * <p>
     * Note that {@link #onTerminated(long)} runs first for terminal states and
     * unregisters the task, so a listener must use the {@code task} argument
     * rather than {@code findTask(id)} — by the time it is called the lookup
     * already returns empty.
     */
    public void onTaskStatusChanged(NaruTask task, NaruTaskStatus oldStatus, NaruTaskStatus newStatus) {
        // deliberately not guarded on 'stopped': onTerminated() stops the session when the
        // last task dies, and a listener must still observe that final transition
        for (NaruSessionListener listener : sessionListeners) {
            try {
                listener.onTaskStatusChanged(task, oldStatus, newStatus);
            } catch (Throwable error) {
                // must not escape: this runs inside NaruTaskImpl.status(), so a
                // throwing listener would otherwise fail the task being ticked
                log(NaruLogMode.SCHEDULER, NMsg.ofC("[%s] session listener failed on task %s: %s",
                        error, task.id(), newStatus).asError());
            }
        }
    }

    @Override
    public NaruSessionEventLog eventLog() {
        ensureNotStopped();
        return eventLog;
    }

    @Override
    public long[] findTaskIdsByParent(long taskId) {
        ensureNotStopped();
        Set<Long> ids = new HashSet<>();
        for (NaruTask t : tasks.values()) {
            if (t.parentId() == taskId) {
                ids.add(t.id());
            }
        }
        return ids.stream().mapToLong(x -> x).toArray();
    }


//    public String resolveRoutineUuid(String uuidOrName) {
//        List<NaruResourceInfo> list = routines();
//        for (NaruResourceInfo s : list) {
//            if (Objects.equals(s.getUuid(), uuidOrName)) {
//                return s.getUuid();
//            }
//        }
//        for (NaruResourceInfo s : list) {
//            if (Objects.equals(NStringUtils.strip(s.getName()), NStringUtils.strip(uuidOrName))) {
//                return s.getUuid();
//            }
//        }
//        if (Objects.equals(session.name(), uuidOrName)) {
//            return session.uuid();
//        }
//        Integer index = NLiteral.of(uuidOrName).asInt().orNull();
//        if (index != null) {
//            if (index - 1 >= 0 && index - 1 < list.size()) {
//                return list.get(index - 1).getUuid();
//            }
//        }
//        return null;
//    }

//    @Override
//    public NaruRoutine ensureRoutineExists(String routineName, NAruVisibility visibilityOnCreate, NaruTask naruTask) {
//        routineName = NStringUtils.firstNonBlankStripped(routineName, "main");
//        NaruRoutine rt = routine(routineName, naruTask).orNull();
//        if (rt != null) {
//            return rt;
//        }
//        if (NaruUtils.isPath(routineName) || NBlankable.isBlank(routineName)) {
//            throw new NIllegalArgumentException(NMsg.ofC("Invalid routine name: %s", routineName));
//        } else {
//            return session.ensureRoutineExists(routineName);
//        }
//    }

//    @Override
//    public NaruRoutine newRoutine(String routineName, NaruTask naruTask) {
//        if (NaruUtils.isPath(routineName) || NBlankable.isBlank(routineName)) {
//            throw new NIllegalArgumentException(NMsg.ofC("Invalid routine name: %s", routineName));
//        } else {
//            NaruRoutine rt = routine(routineName, naruTask).orNull();
//            if (rt != null) {
//                throw new NIllegalArgumentException(NMsg.ofC("Routine already exist name: %s", routineName));
//            }
//            return session.ensureRoutineExists(routineName);
//        }
//    }

    @Override
    public List<NaruResourceInfo> routines() {
        return routines.entrySet().stream().map(x -> {
            return new NaruResourceInfo()
                    .setName(x.getKey())
                    .setCreationInstant(x.getValue().creationInstant())
                    .setModificationInstant(x.getValue().modificationInstant())
                    .setVisibility(x.getValue().visibility())
                    .setUuid(x.getValue().uuid());
        }).sorted((o1, o2) -> o2.getModificationInstant().compareTo(o1.getModificationInstant())).collect(Collectors.toList());
    }


    @Override
    public Map<String, Object> getSessionEnv() {
        return env.entrySet().stream().collect(Collectors.toMap(x -> x.getKey(), x -> x.getValue().orNull()));
    }

    @Override
    public NOptional<NaruModelConfig> loadModelConfig(String modelName) {
        return ((NaruAgentImpl) agent()).getModelAliases().get(modelName);
    }

    @Override
    public void saveModelConfig(String modelName, NaruModelConfig config) {
        ((NaruAgentImpl) agent()).getModelAliases().put(modelName, config);
    }

    @Override
    public NOptional<NaruRoutine> routine(String nameOrPath, NaruTask task, boolean orCreate) {
        if (ImplNaruUtils.isPath(nameOrPath)) {
            NPath path = NPath.of(nameOrPath).toAbsolute(task.workingDir());
            if (path.exists()) {
                return RoutineHelper.loadFileRoutine(path, true);
            }
            if (!path.name().endsWith(".naru") && !path.name().endsWith(".")) {
                path = NPath.of(nameOrPath + ".naru").toAbsolute(task.workingDir());
                if (path.exists()) {
                    return RoutineHelper.loadFileRoutine(path, true);
                }
            }
        } else if (ImplNaruUtils.isValidRoutineName(nameOrPath)) {
            NaruRoutine r = routines.get(nameOrPath);
            if (r != null) {
                return NOptional.of(r);
            }
        }
        NPath path = NPath.of(nameOrPath).toAbsolute(task.workingDir());
        if (path.exists()) {
            return RoutineHelper.loadFileRoutine(path, true);
        }
        if (!path.name().endsWith(".naru") && !path.name().endsWith(".")) {
            path = NPath.of(nameOrPath + ".naru").toAbsolute(task.workingDir());
            if (path.exists()) {
                return RoutineHelper.loadFileRoutine(path, true);
            }
        }
        if (ImplNaruUtils.isValidRoutineName(nameOrPath) && orCreate) {
            return NOptional.of(routines.computeIfAbsent(nameOrPath, x ->
                    new NaruRoutineMem(UUID.randomUUID().toString(), x, getVisibility())));
        }
        return NOptional.ofEmpty(NMsg.ofC("Error statement: routine not found %s", nameOrPath));
    }

    public void fireChangedTask(long id) {
        NaruTask t = tasks.get(id);
        if (t != null) {
            NPath r = snapshotFile().parent().resolve("tasks");
            r.mkdirs();
            r.list().stream().filter(x -> x.name().endsWith(".tson")).forEach(x -> x.delete());
            NElementWriter.ofTson().ntf(false).formatter(NElementFormatterStyle.PRETTY)
                    .write(t.toElement(), r.resolve(t.id() + ".tson"));

        }
    }
}
