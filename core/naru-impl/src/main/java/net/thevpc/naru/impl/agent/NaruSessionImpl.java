package net.thevpc.naru.impl.agent;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.budget.NaruMeteringService;
import net.thevpc.naru.api.mode.NaruStandardMode;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.routine.NaruRoutineManager;
import net.thevpc.naru.api.scheduler.*;
import net.thevpc.naru.api.skills.NaruSkillManager;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.api.registry.NaruRegistry;
import net.thevpc.naru.impl.budget.NaruMeteringServiceImpl;
import net.thevpc.naru.impl.registry.NaruRegistryImpl;
import net.thevpc.naru.impl.routine.NaruRoutineManagerImpl;
import net.thevpc.naru.impl.scheduler.NaruSchedulerImpl;
import net.thevpc.naru.impl.scheduler.NaruSessionEventLogImpl;
import net.thevpc.naru.impl.scheduler.NaruTaskImpl;
import net.thevpc.naru.impl.skill.NaruSkillManagerImpl;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.io.NIOUtils;
import net.thevpc.nuts.io.NOut;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NTerminal;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.*;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class NaruSessionImpl implements NaruSession, NToElement {
    private final NaruAgent agent;
    /**
     * public|private
     */
    private NAruVisibility visibility;
    private NaruModelConfig model;
    private final Set<NPath> alreadyLoadedFiles = new HashSet<>();

    /**
     * Optional: additional context the user wants to share with every tool.
     */
    private final NaruRoutineManager routineManager;
    private final NaruSkillManager skillManager;
    private final NaruMeteringService meteringService;
    private final NaruSessionManagerImpl sessionManager;

    private String uuid = UUID.randomUUID().toString();
    private String name = "NO_NAME";
    private Instant creationDate = Instant.now();
    private Instant modificationDate = creationDate;
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


    public NaruSessionImpl(NaruAgent agent, NPath projectDir, NaruMeteringService meteringService, boolean configureDefaults, NaruSessionListener sessionListener) {
        this.agent = agent;
        this.projectDir = projectDir;
        this.workingDir = projectDir;
        this.meteringService = meteringService == null ? new NaruMeteringServiceImpl() : meteringService;
        this.sessionManager = new NaruSessionManagerImpl(this);
        this.routineManager = new NaruRoutineManagerImpl(this);
        this.skillManager = new NaruSkillManagerImpl(this);
        this.registry = new NaruRegistryImpl(this);
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
        this.scheduler = new NaruSchedulerImpl(this, 1);
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
            natuTask._setMode(registry().mode(NaruStandardMode.PLANNING).get());
            natuTask._setInputBuffer("");
            natuTask._setLastResult(null);
            natuTask._setReturnResult(null);
            natuTask._setModel(model);
            natuTask._setSkills(new HashSet<>());
        } else {
            natuTask._setInputMode(NAruInputMode.LINE);
            natuTask._setWorkingDir(cwd == null ? parent.workingDir() : cwd);
            natuTask._setProjectDir(parent.projectDir());
            natuTask._setMode(NUtils.firstNonNull(parent.promptMode(), registry().mode(NaruStandardMode.PLANNING).get()));
            natuTask._setInputBuffer("");
            natuTask._setLastResult(null);
            natuTask._setReturnResult(null);
            natuTask._setModel(parent.model());
            natuTask._setSkills(parent.skillNames());
        }
        natuTask.addHistory(NaruMessage.system(buildSystemPrompt(this)));
        natuTask._prependInitHooks();
        natuTask.addStatements(
                taskBuilder.statements().stream().map(x -> natuTask.parseStatement(x).get().injected(true)).collect(Collectors.toList())
                        .stream().map(x -> x.injected(true)).toArray(NaruStatement[]::new));
        tasks.put(id, natuTask);
        return natuTask;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private String buildSystemPrompt(NaruSession session) {
        String s = systemPrompt();
        if (NBlankable.isBlank(s)) {
            StringBuilder sb = new StringBuilder();
            sb.append("You are NARU (Nuts AI Reasoning Unit), an expert software engineering agent.\n");
            sb.append("You have access to tools that let you read/write files, run shell commands, ");
            sb.append("compile Maven projects, and inspect images using a vision model.\n\n");
            sb.append("Guidelines:\n");
            sb.append("- Always read the relevant files before modifying them.\n");
            sb.append("- After modifying Java files, always compile to check for errors.\n");
            sb.append("- Use inspect_image to verify that generated images match expectations.\n");
            sb.append("- Be concise in your final answer. Summarise what you changed and why.\n");

            if (session.projectDir() != null) {
                sb.append("\nProject directory: ").append(session.projectDir()).append('\n');
            }
            if (!registry().isEmpty()) {
                sb.append("\nAvailable tools: ").append(registry().toolNames()).append('\n');
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
        ensureNotStopped();
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
        this.name = NStringUtils.firstNonBlankTrimmed(name, "NO_NAME");
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
        List<NaruModelConfig> models = registry().modelsKeys(this).stream().map(NaruModelConfig::new).collect(Collectors.toList());
        NaruModelConfig a = findModelAlias(keyOrName).orNull();
        if (a != null) {
            for (NaruModelConfig m : models) {
                if (Objects.equals(m.key(), a.key())) {
                    return NOptional.of(a);
                }
            }
        }
        if (keyOrName.contains("/")) {
            NOptional<NaruModelConfig> r = NaruModelKey.parse(keyOrName).map(NaruModelConfig::new);
            if (r.isPresent()) {
                for (NaruModelConfig m : models) {
                    if (Objects.equals(m.key(), r.get().key())) {
                        return NOptional.of(r.get());
                    }
                }
            }
        } else {
            for (NaruModelConfig m : models) {
                if (m.model().equals(keyOrName)) {
                    return NOptional.of(m);
                }
            }
        }
        Integer ii = NLiteral.of(keyOrName).asInt().orNull();
        if (ii != null) {
            ii = ii - 1;
            if (ii >= 0 && ii < models.size()) {
                return NOptional.of(models.get(ii));
            }
        }
        return NOptional.ofNamedEmpty(NMsg.ofC("model '%s'", keyOrName));
    }

    @Override
    public void removeModelAlias(String alias) {
        ensureNotStopped();
        alias = NStringUtils.trimToNull(alias);
        ((NaruAgentImpl) agent).getModelAliases().remove(alias);
        fireChanged();
    }

    @Override
    public void addModelAlias(String alias, NaruModelConfig model) {
        ensureNotStopped();
        alias = NStringUtils.trimToNull(alias);
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
    public Instant creationDate() {
        ensureNotStopped();
        return creationDate;
    }

    @Override
    public Instant modificationDate() {
        ensureNotStopped();
        return modificationDate;
    }

    @Override
    public String uuid() {
        return uuid;
    }

    @Override
    public NaruSession save(NPath path) {
        ensureNotStopped();
        NElementWriter.ofTson().ntf(false).formatter(NElementFormatterStyle.PRETTY)
                .write(toElement(), path.mkParentDirs());
        return this;
    }

    public void fireChanged() {
        ensureNotStopped();
        this.modificationDate = Instant.now();
        sessionManager.saveSnapshot();
    }

    @Override
    public NaruSession load(NPath path) {
        ensureNotStopped();
        load(NElementReader.ofTson().read(path));
        return this;
    }

    @Override
    public NaruSession copy() {
        ensureNotStopped();
        this.uuid = UUID.randomUUID().toString();
        sessionListener.onSessionReloaded(this);
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
            this.creationDate = Instant.now();
            this.modificationDate = creationDate;
        }
        //clearHistory();
        maxTaskId.set(0);
        this.sessionManager.saveSnapshot();
        sessionListener.onSessionReloaded(this);
        for (NaruSessionListener listener : sessionListeners) {
            listener.onSessionReloaded(this);
        }
        return this;
    }


    @Override
    public NaruSession save() {
        ensureNotStopped();
        NPath publicFile = projectDir.resolve(".naru/sessions/" + uuid() + ".tson");
        NPath privateFile = projectDir.resolve(".naru/local/sessions/" + uuid() + ".tson");
        if (getVisibility() == NAruVisibility.PUBLIC) {
            save(publicFile);
            if (privateFile.isRegularFile()) {
                privateFile.delete();
            }
        } else {
            save(privateFile);
            if (publicFile.isRegularFile()) {
                publicFile.delete();
            }
        }
        return this;
    }

    @Override
    public NaruSession load() {
        ensureNotStopped();
        NPath publicFile = projectDir.resolve(".naru/sessions/" + uuid() + ".tson");
        NPath privateFile = projectDir.resolve(".naru/local/sessions/" + uuid() + ".tson");
        if (publicFile.isRegularFile()) {
            load(publicFile);
            setVisibility(NAruVisibility.PUBLIC);
        } else {
            load(privateFile);
            setVisibility(NAruVisibility.PRIVATE);
        }
        sessionListener.onSessionReloaded(this);
        for (NaruSessionListener listener : sessionListeners) {
            listener.onSessionReloaded(this);
        }
        return this;
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder o = NObjectElementBuilder.of();
        o.set("uuid", uuid());
        o.set("name", name());
        o.set("creationDate", NElement.ofInstant(creationDate));
        o.set("modificationDate", NElement.ofInstant(modificationDate));
        o.set("model", model == null ? null : model.toElement());
        o.set("projectDir", NElement.ofString(projectDir.toString()));
        o.set("workingDir", workingDir == null ? null : NElement.ofString(workingDir.toString()));
        o.set("tasks", NElement.ofArray(tasks.values().stream().map(NToElement::toElement).toArray(NElement[]::new)));
        return o.build();
    }

    @Override
    public NaruSession load(NElement element) {
        ensureNotStopped();
        NObjectElement o = element.asObject().get();
        this.uuid = NStringUtils.firstNonBlankTrimmed(o.getStringValue("uuid").orElse(null), UUID.randomUUID().toString());
        this.name = NStringUtils.firstNonBlankTrimmed(o.getStringValue("name").orElse(null), "NO_NAME");
        this.creationDate = NUtils.firstNonNull(o.getInstantValue("creationDate").orElse(null), Instant.now());
        this.modificationDate = NUtils.firstNonNull(o.getInstantValue("modificationDate").orElse(null), creationDate);
        NElement mv = o.get("model").orElse(null);
        this.model = mv == null || mv.isNull() ? null : new NaruModelConfig(mv);
        this.projectDir = o.getStringValue("projectDir").map(x -> NPath.of(x)).orElse(projectDir);
        this.workingDir = o.getStringValue("workingDir").map(x -> NPath.of(x)).orElse(workingDir);
        NArrayElement todo1 = o.get("tasks").flatMap(x -> x.isNull() ? null : x.asArray()).orNull();
        tasks.clear();
        long maxLong = 0;
        if (todo1 != null) {
            for (NElement nElement : todo1) {
                NaruTaskImpl t = new NaruTaskImpl(nElement, this);
                maxLong = Math.max(0, t.id());
                tasks.put(t.id(), t);
            }
        }
        long finalMaxLong = maxLong == 0 ? 0 : maxLong + 1;
        maxTaskId.updateAndGet(current -> Math.max(current, finalMaxLong));
        return this;
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
        if (mode == NaruLogMode.SCHEDULER) {
            if (isTrace()) {
                agent.log(mode, s);
            }
        } else {
            agent.log(mode, s);
        }
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
    public NaruRoutineManager routineManager() {
        ensureNotStopped();
        return routineManager;
    }

    @Override
    public NaruSkillManager skillManager() {
        ensureNotStopped();
        return skillManager;
    }

    @Override
    public NaruSession terminate() {
        ensureNotStopped();
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
            String line = NTerminal.of().readLine(prompt); // blocks until user types
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
        readlineThread.setDaemon(false);
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
        return running;
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
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
        ensureNotStopped();
        try {
            // wait for scheduler workers
            scheduler.awaitTermination();
            // wait for readline thread
            readlineThread.join();
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
}
