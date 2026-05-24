package net.thevpc.naru.impl.agent;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.budget.NaruMeteringService;
import net.thevpc.naru.api.budget.NaruTokenTransaction;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.routine.NaruRoutineManager;
import net.thevpc.naru.api.routine.RunContext;
import net.thevpc.naru.api.skills.NaruSkill;
import net.thevpc.naru.api.skills.NaruSkillManager;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.naru.impl.routine.NaruRoutineManagerImpl;
import net.thevpc.naru.impl.skill.NaruSkillManagerImpl;
import net.thevpc.naru.impl.stmt.NaruIncrementalStmt;
import net.thevpc.naru.impl.stmt.NaruStatementHelper;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.core.NStoreKey;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.expr.*;
import net.thevpc.nuts.io.NIOUtils;
import net.thevpc.nuts.io.NOut;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NChronometer;
import net.thevpc.nuts.util.*;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class NaruSessionImpl implements NaruSession, NToElement {
    private final List<NaruMessage> systemHistory = new ArrayList<>();
    private final List<NaruMessage> history = new ArrayList<>();
    private int userQueries;
    private String inputBuffer = "";
    private NaruMessage lastResult;
    private boolean requireUserInput;
    private final List<RunContextImpl> todo = new ArrayList<>();
    private final NaruAgent agent;
    private boolean publicSession;
    private NPath workingDir;
    private NaruModelConfig model;
    private NAruInputMode inputMode = NAruInputMode.LINE;
    /**
     * Root directory of the project being worked on.
     */
    private NPath projectDir;
    private final Set<NPath> alreadyLoadedMdFiles = new HashSet<>();
    private final Set<NPath> alreadyLoadedDirectiveFiles = new HashSet<>();
    private final Set<String> skills = new TreeSet<>();

    /**
     * Optional: additional context the user wants to share with every tool.
     */
    private String extraContext;
    private final NaruRoutineManager routineManager;
    private final NaruSkillManager skillManager;
    private final NaruMeteringService meteringService;
    private final NaruSessionManagerImpl sessionManager;

    private String uuid = UUID.randomUUID().toString();
    private String name = "NO_NAME";
    private Instant creationDate = Instant.now();
    private Instant modificationDate = creationDate;
    // Add to NaruSessionImpl fields:
    private final Map<String, Object> globalState = new ConcurrentHashMap<>();

    public NAruInputMode inputMode() {
        return inputMode;
    }

    public NaruSessionImpl inputMode(NAruInputMode inputMode) {
        if (inputMode != null) {
            if (inputMode != this.inputMode) {
                this.inputMode = inputMode;
                fireChanged();
            }
        }
        return this;
    }

    public boolean isPublicSession() {
        return publicSession;
    }

    public NaruSessionImpl publicSession(boolean publicSession) {
        this.publicSession = publicSession;
        return this;
    }

    // Accessors
    public void setSessionEnv(String key, Object value) {
        globalState.put(key, value);
        fireChanged();
    }

    @Override
    public NOptional<NElement> getProjectEnv(String key) {
        NaruEnv a = agent.env();
        return a.get(key);
    }

    @Override
    public void setProjectEnv(String key, NElement value, NAruVisibility visibility) {
        NaruEnv a = agent.env();
        a.put(key, value, visibility);
    }

    public Object getGlobalState(String key) {
        return globalState.get(key);
    }

    public Map<String, Object> getGlobalStateSnapshot() {
        return new HashMap<>(globalState);
    }


    public NaruSessionImpl(NaruAgent agent, NPath projectDir, NaruMeteringService meteringService) {
        this.agent = agent;
        this.projectDir = projectDir;
        this.workingDir = projectDir;
        this.meteringService = meteringService;
        this.sessionManager = new NaruSessionManagerImpl(this);
        this.routineManager = new NaruRoutineManagerImpl(this);
        this.skillManager = new NaruSkillManagerImpl(this);

        NaruModelConfig model0 = null;
        NaruModelConfig model = model0;
        NaruRegistry registry = agent.registry();
        if (model == null) {
            model = getProjectEnv("model").flatMap(x -> findModel(new NaruModelConfig(x))).orNull();
        } else {
            model = findModel(model0).orNull();
        }
        if (model == null) {
            List<NaruModelInfo> any = registry.modelsInfos(this)
                    .stream().filter(x -> x.capabilities().isTools()).collect(Collectors.toList());
            if (any.isEmpty()) {
                NOut.println(NMsg.ofC("model %s not found. actually no model (with tools capability) was found at all", model0).asError());
                return;
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
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public NaruSession setName(String name) {
        this.name = NStringUtils.firstNonBlankTrimmed(name, "NO_NAME");
        this.fireChanged();
        return this;
    }


    @Override
    public Map<String, NaruModelConfig> modelAliases() {
        return ((NaruAgentImpl) agent).getModelAliases().toMap();
    }

    @Override
    public NaruResponse chat(NaruModelConfig modelKey, NaruModelRequest request) {
        Instant now = Instant.now();
        NChronometer chronometer = NChronometer.of();
        NaruResponse r = registry().protocol(modelKey, this).get().chat(request, this);
        meteringService.trackTransaction(new NaruTokenTransaction(
                uuid(),
                null,
                model(),
                r.getPromptTokens(),
                r.getEvalTokens(),
                now,
                chronometer.stop().duration()
        ), this);
        return r;
    }

    @Override
    public Map<NaruModelConfig, List<String>> reversedModelAliases() {
        HashMap<NaruModelConfig, List<String>> m = new HashMap<>();
        for (Map.Entry<String, NaruModelConfig> e : modelAliases().entrySet()) {
            NaruModelConfig k = e.getValue();
            List<String> v = m.computeIfAbsent(k, k1 -> new ArrayList<>());
            v.add(e.getKey());
        }
        return m;
    }

    public NOptional<NaruModelConfig> findModel(NaruModelConfig keyOrName) {
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
        List<NaruModelConfig> models = registry().modelsKeys(this).stream().map(NaruModelConfig::new).collect(Collectors.toList());
        NaruModelConfig a = findModelAlias(keyOrName).orNull();
        if (a != null) {
            for (NaruModelConfig m : models) {
                if(Objects.equals(m.key(),a.key())){
                    return NOptional.of(a);
                }
            }
        }
        if (keyOrName.contains("/")) {
            NOptional<NaruModelConfig> r = NaruModelKey.parse(keyOrName).map(NaruModelConfig::new);
            if (r.isPresent()) {
                for (NaruModelConfig m : models) {
                    if(Objects.equals(m.key(),r.get().key())){
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
        alias = NStringUtils.trimToNull(alias);
        ((NaruAgentImpl) agent).getModelAliases().remove(alias);
        fireChanged();
    }

    @Override
    public void addModelAlias(String alias, NaruModelConfig model) {
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
        return ((NaruAgentImpl) agent).getModelAliases().get(alias);
    }

    @Override
    public Instant creationDate() {
        return creationDate;
    }

    @Override
    public Instant modificationDate() {
        return modificationDate;
    }

    @Override
    public String uuid() {
        return uuid;
    }

    @Override
    public NaruSession save(NPath path) {
        NElementWriter.ofTson().ntf(false).formatter(NElementFormatterStyle.PRETTY)
                .write(toElement(), path.mkParentDirs());
        return this;
    }

    protected void fireChanged() {
        this.modificationDate = Instant.now();
        sessionManager.saveSnapshot();
    }

    @Override
    public NaruSession load(NPath path) {
        load(NElementReader.ofTson().read(path));
        return this;
    }

    @Override
    public NaruSession copy() {
        this.uuid = UUID.randomUUID().toString();
        return this;
    }

    @Override
    public NaruSession reset(boolean preserveIdentity) {
        if (!preserveIdentity) {
            this.uuid = UUID.randomUUID().toString();
            this.name = "NO_NAME";
            this.creationDate = Instant.now();
            this.modificationDate = creationDate;
        }
        clearHistory();
        userQueries = 0;
        lastResult = null;
        todo.clear();
//        if (forever) {
//            pushStatement(NaruStatementHelper.ofReadLine());
//        }
        this.extraContext = null;
        sessionManager.saveSnapshot();
        return this;
    }


    @Override
    public NaruSession save() {
        NPath publicFile = projectDir.resolve(".naru/sessions/" + uuid() + ".tson");
        NPath privateFile = projectDir.resolve(".naru/local/sessions/" + uuid() + ".tson");
        if (publicSession) {
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
        NPath publicFile = projectDir.resolve(".naru/sessions/" + uuid() + ".tson");
        NPath privateFile = projectDir.resolve(".naru/local/sessions/" + uuid() + ".tson");
        if (publicFile.isRegularFile()) {
            load(publicFile);
            publicSession(true);
        } else {
            load(privateFile);
            publicSession(false);
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
        o.set("extraContext", extraContext);
        o.set("requireUserInput", requireUserInput);
        o.set("projectDir", NElement.ofString(projectDir.toString()));
        o.set("workingDir", workingDir == null ? null : NElement.ofString(workingDir.toString()));
        o.set("userQueries", userQueries);
        o.set("lastResult", lastResult == null ? null : lastResult.toElement());
        o.set("inputMode", inputMode.name());
        o.set("inputBuffer", inputBuffer);
        NArrayElementBuilder _todos = NArrayElementBuilder.of();
        for (RunContextImpl a : todo) {
            _todos.add(a.toElement());
        }
        NArrayElementBuilder _history = NArrayElementBuilder.of();
        for (NaruMessage a : history) {
            _history.add(a.toElement());
        }
        o.set("todo", _todos.build());
        o.set("history", _history.build());
        return o.build();
    }

    @Override
    public NaruSession load(NElement element) {
        NObjectElement o = element.asObject().get();
        this.uuid = NStringUtils.firstNonBlankTrimmed(o.getStringValue("uuid").orElse(null), UUID.randomUUID().toString());
        this.name = NStringUtils.firstNonBlankTrimmed(o.getStringValue("name").orElse(null), "NO_NAME");
        this.creationDate = NUtils.firstNonNull(o.getInstantValue("creationDate").orElse(null), Instant.now());
        this.modificationDate = NUtils.firstNonNull(o.getInstantValue("modificationDate").orElse(null), creationDate);
        NElement mv = o.get("model").orElse(null);
        this.model = mv == null || mv.isNull() ? null : new NaruModelConfig(mv);
        this.extraContext = o.getStringValue("extraContext").orElse(null);
        this.userQueries = o.getIntValue("userQueries").orElse(0);
        this.requireUserInput = o.getBooleanValue("requireUserInput").orElse(false);
        this.projectDir = o.getStringValue("projectDir").map(x -> NPath.of(x)).orElse(projectDir);
        this.workingDir = o.getStringValue("workingDir").map(x -> NPath.of(x)).orElse(workingDir);
        this.lastResult = o.get("lastResult").map(x -> NaruMessage.of(x)).orNull();
        this.inputMode = o.get("inputMode").map(x -> NAruInputMode.parse(x).orElse(NAruInputMode.LINE)).orNull();
        this.inputBuffer = "";
        NOptional<NElement> ibe = o.get("inputBuffer");
        if (ibe.isPresent() && ibe.get().isAnyStringOrName()) {
            this.inputBuffer = ibe.get().asStringValue().get();
        }
        NArrayElement todo1 = o.get("todo").flatMap(x -> x.isNull() ? null : x.asArray()).orNull();
        todo.clear();
        if (todo1 != null) {
            for (NElement nElement : todo1) {
                todo.add(new RunContextImpl(nElement));
            }
        }
        NArrayElement history1 = o.get("history").flatMap(x -> x.isNull() ? null : x.asArray()).orNull();
        history.clear();
        if (history1 != null) {
            for (NElement nElement : history1) {
                history.add(new NaruMessage(nElement));
            }
        }
        return this;
    }

    public NaruSessionImpl setProjectDir(NPath projectDir) {
        this.projectDir = projectDir;
        fireChanged();
        return this;
    }

    @Override
    public String getExtraContext() {
        return extraContext;
    }

    public NaruSessionImpl setExtraContext(String extraContext) {
        this.extraContext = extraContext;
        fireChanged();
        return this;
    }

    @Override
    public void log(NaruLogMode mode, NMsg s) {
        agent.log(mode, s);
    }

    @Override
    public List<NaruMessage> history() {
        return history(false);
    }

    @Override
    public List<NaruMessage> history(boolean includeSystem) {
        if (includeSystem) {
            return context(NaruSource.values());
        } else {
            return context(NaruSource.USER);
        }
    }

    @Override
    public List<NaruMessage> context(NaruSource... sources) {
        Set<NaruSource> sourcesOk = new HashSet<>();
        if (sources != null) {
            for (NaruSource s : sources) {
                if (s != null) {
                    sourcesOk.add(s);
                }
            }
        }
        List<NaruMessage> all = new ArrayList<>();
        if (sourcesOk.contains(NaruSource.SYSTEM)) {
            all.addAll(systemHistory.stream().map(x -> x.copy().setSource(NaruSource.SYSTEM).setSourceName("system")).collect(Collectors.toList()));
        }

        String[] agentFileNames = resolveAgentFileNames();
        if (sourcesOk.contains(NaruSource.CLASSPATH)) {
            all.addAll(loadAgentClassPath(agentFileNames));
        }
        if (sourcesOk.contains(NaruSource.USER_HOME)) {
            all.addAll(loadAgentUserHome(agentFileNames));
        }
        // add project/folder level agent files
        if (sourcesOk.contains(NaruSource.PROJECT) || sourcesOk.contains(NaruSource.FOLDER)) {
            if (!workingDir.startsWith(projectDir)) {
                if (sourcesOk.contains(NaruSource.PROJECT)) {
                    all.addAll(loadAgentFolder(projectDir, agentFileNames, NaruSource.PROJECT));
                }
            } else {
                List<NPath> dirs = new ArrayList<>();
                NPath p = workingDir;
                while ((p != null && (p.equals(projectDir) || p.startsWith(projectDir)))) {
                    dirs.add(p);
                    if (p.equals(projectDir)) break;
                    p = p.parent();
                }
                // reverse: projectDir first, workingDir last (specific wins)
                Collections.reverse(dirs);
                for (NPath dir : dirs) {
                    if (dir.equals(projectDir)) {
                        if (sourcesOk.contains(NaruSource.PROJECT)) {
                            all.addAll(loadAgentFolder(dir, agentFileNames, NaruSource.PROJECT));
                        }
                    } else {
                        if (sourcesOk.contains(NaruSource.FOLDER)) {
                            all.addAll(loadAgentFolder(dir, agentFileNames, NaruSource.FOLDER));
                        }
                    }
                }
            }
        }

        if (sourcesOk.contains(NaruSource.SKILL)) {
            // add skills
            for (String skill : skills) {
                NaruSkill s = skillManager.findSkill(skill);
                if (s != null) {
                    String collected = s.getLines().stream().collect(Collectors.joining("\n"));
                    all.add(NaruMessage.user(
                            "## ACTIVE SKILL DIRECTIVE: " + s.getName().toUpperCase() + "\n" + collected
                    ).setSource(NaruSource.SKILL).setSourceName(s.getSourceName()));
                }
            }
        }
        if (sourcesOk.contains(NaruSource.USER)) {
            all.addAll(history.stream().map(x -> x.copy().setSource(NaruSource.USER)).collect(Collectors.toList()));
        }
        return all;
    }

    private List<NaruMessage> loadAgentClassPath(String[] agentFileNames) {
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
                        if (alreadyLoadedMdFiles.add(NPath.of(u))) {
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

    private List<NaruMessage> loadAgentUserHome(String[] agentFileNames) {
        StringBuilder sb = new StringBuilder();
        // this will resolve (for the default nuts workspace, and linux) to
        // ~/.config/nuts/ws/default-workspace/id/net/thevpc/naru/naru/SHARED/agents
        NPath agents = NPath.of(NStoreKey.ofShared(NId.of("net.thevpc.naru:naru"))).resolve("agents");
        Set<String> validNames = new TreeSet<>();
        for (String agentFileName : agentFileNames) {
            tryLoadAgentFile(agents.resolve(agentFileName), sb, validNames);
        }
        String u = sb.toString().trim();
        if (!u.isEmpty()) {
            return Collections.singletonList(NaruMessage.user(
                    "### USER CONTEXT:\n" + u
            ).setSource(NaruSource.USER_HOME).setSourceName(validNames.size() == 1 ? validNames.stream().findFirst().get() : validNames.toString()));
        }
        return new ArrayList<>();
    }

    private String[] resolveAgentFileNames() {
        List<String> a = new ArrayList<>();
        a.add("default.md");
        NaruModelConfig mm = model();
        if(mm==null){
            return new String[0];
        }
        String m = mm.model();
        String modelBase;
        String modelTag;
        if (m.indexOf(':') > 0) {
            modelBase = m.substring(0, m.indexOf(':'));
            modelTag = m.substring(m.indexOf(':') + 1);
        } else {
            modelBase = m;
            modelTag = "";
        }
        a.add(modelBase + ".md");
        if (!modelTag.isEmpty()) {
            a.add(modelBase + "@" + modelTag + ".md");
        }
        return a.toArray(new String[0]);
    }

    private List<NaruMessage> loadAgentFolder(NPath folder, String[] agentFileNames, NaruSource source) {
        List<NaruMessage> all = new ArrayList<>();
        if (folder.isDirectory()) {
            StringBuilder sb = new StringBuilder();
            Set<String> validNames = new TreeSet<>();
            for (String agentFileName : agentFileNames) {
                tryLoadAgentFile(folder.resolve(".naru/agents/" + agentFileName), sb, validNames);
                tryLoadAgentFile(folder.resolve(".naru/local/agents/" + agentFileName), sb, validNames);
            }
            String u = sb.toString().trim();
            String folderName = folder.name();
            if (NBlankable.isBlank(folderName)) {
                folderName = "Root Workspace";
            }
            if (!u.isEmpty()) {
                all.add(NaruMessage.user(
                        "### WORKSPACE CONTEXT OVERRIDE (" + folderName + "):\n" + u
                ).setSource(source).setSourceName(validNames.size() == 1 ? validNames.stream().findFirst().get() : validNames.toString()));
            }
        }
        return all;
    }

    private void tryLoadAgentFile(NPath a, StringBuilder sb, Set<String> validNames) {
        if (a.isRegularFile()) {
            if (alreadyLoadedMdFiles.add(a)) {
                log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loading agent from %s", a));
            }
            String content = a.readString().trim();
            if (!NBlankable.isBlank(content)) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append("\n");
                }
                validNames.add(a.toString());
                sb.append(content);
            }
        }
    }

    private List<NaruStatement> loadDirectivesFile(NPath path) {
        List<NaruStatement> a = new ArrayList<>();
        if (path.isRegularFile()) {
            if (alreadyLoadedDirectiveFiles.add(path)) {
                log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loaded directives from %s", path));
            }
            for (String line : path.lines()) {
                line = line.trim();
                if (!line.startsWith("#") && !line.isEmpty()) {
                    a.add(agent.parseStatement(line).get());
                }
            }
        }
        return a;
    }

    @Override
    public boolean removeHistoryAt(int index) {
        if (index >= 0 && index < history.size()) {
            history.remove(index);
            fireChanged();
            return true;
        }
        return false;
    }

    public int pc() {
        if (todo.isEmpty()) {
            return -1;
        }
        return todo.get(0).pc();
    }

    @Override
    public NaruSession pc(int nextPc) {
        if (!todo.isEmpty()) {
            todo.get(0).setPc(nextPc);
            fireChanged();
        }
        return this;
    }

    @Override
    public NPath resolve(String path) {
        if (NBlankable.isBlank(path)) return workingDir;
        NPath p = NPath.of(path);
        return p.isAbsolute() ? p : workingDir.resolve(p).normalize();
    }

    public NPath workingDir() {
        return workingDir;
    }

    public NaruSession setWorkingDir(NPath workingDir) {
        NPath nf = workingDir.toAbsolute(this.workingDir);
        if (!nf.equals(this.workingDir)) {
            this.workingDir = nf;
            List<NaruStatement> all = new ArrayList<>();
            for (NPath path : workingDir.resolve(".naru/directives/").list().stream().filter(x -> x.name().endsWith(".md")).sorted(Comparator.comparing(x -> x.name())).collect(Collectors.toList())) {
                all.addAll(loadDirectivesFile(path));
            }
            for (NPath path : workingDir.resolve(".naru/local/directives/").list().stream().filter(x -> x.name().endsWith(".md")).sorted(Comparator.comparing(x -> x.name())).collect(Collectors.toList())) {
                all.addAll(loadDirectivesFile(path));
            }
            if (!all.isEmpty()) {
                pushStatements(all.toArray(new NaruStatement[0]));
                this.run();
            }
            fireChanged();
        }
        return this;
    }

    @Override
    public int clearHistory() {
        int count = history.size();
        history.clear();
        if (count > 0) {
            fireChanged();
        }
        return count;
    }

    @Override
    public NaruModelConfig model() {
        if (model != null) {
            return model;
        }
        NaruModelConfig model = agent.env().get("model").map(x -> x == null || x.isNull() ? null : new NaruModelConfig(x)).orNull();
        if (model != null) {
            return model;
        }
        model = registry().modelsKeys(this).stream().findFirst().map(NaruModelConfig::new).orElse(null);
        return model;
    }

    public NaruSession setModel(NaruModelConfig model) {
        this.model = model;
        fireChanged();
        return this;
    }

    @Override
    public int trimHistory(int count) {
        if (count > 0) {
            if (count >= history.size()) {
                int i = clearHistory();
                fireChanged();
                return i;
            } else {
                history.subList(0, history.size() - count).clear();
                fireChanged();
                return count;
            }
        }
        return 0;
    }

    @Override
    public NPath projectDir() {
        return projectDir;
    }

    @Override
    public NaruRoutineManager routineManager() {
        return routineManager;
    }

    @Override
    public NaruSkillManager skillManager() {
        return skillManager;
    }

    @Override
    public NaruSession terminate() {
        todo.clear();
        return this;
    }

    public boolean hasMoreStatements() {
        return !todo.isEmpty();
    }


    @Override
    public NaruAgent agent() {
        return agent;
    }

    @Override
    public NaruStatement popStatement() {
        if (todo.isEmpty()) {
            return null;
        }
        NaruStatement result = null;
        normalizeTodo();
        if (!todo.isEmpty()) {
            List<NaruStatement> a = todo.get(0).todo;
            result = a.remove(0);
            normalizeTodo();
        }
        fireChanged();
        return result;
    }

    // Pop context (remove index 0)
    public NaruSession popContext() {
        if (!todo.isEmpty()) {
            todo.remove(0);
            normalizeTodo();
            fireChanged();
        }
        return this;
    }


    @Override
    public NaruSession pushContext() {
        todo.add(0, new RunContextImpl());
        fireChanged();
        return this;
    }

    @Override
    public NaruSession pushContext(int pc, Integer returnTo) {
        RunContextImpl cc = new RunContextImpl();
        cc.setPc(pc);
        cc.setReturnPc(pc);
        todo.add(0, cc);
        fireChanged();
        return this;
    }

    private void normalizeTodo() {
        while (!todo.isEmpty()) {
            List<NaruStatement> a = todo.get(0).todo;
            if (a.isEmpty()) {
                todo.remove(0);
            } else {
                return;
            }
        }
        fireChanged();
    }

//    @Override
//    public NaruSession pushStatementReadlineForever() {
//        pushStatement(NaruStatementHelper.ofReadLine());
//        setForever(true);
//        return this;
//    }

    @Override
    public NaruSession pushStatementModelCall(String prompt) {
        pushStatement(NaruStatementHelper.ofModelCall(prompt));
        return this;
    }

    @Override
    public NaruSession prepareProject() {
        List<NaruStatement> all = new ArrayList<>();
        all.addAll(loadDirectivesFile(NPath.of(NStoreKey.ofShared(NId.of("net.thevpc.naru:naru"))).resolve("directives")));
        for (NPath path : projectDir.resolve(".naru/directives/").list().stream().filter(x -> x.name().endsWith(".md")).sorted(Comparator.comparing(x -> x.name())).collect(Collectors.toList())) {
            all.addAll(loadDirectivesFile(path));
        }
        for (NPath path : projectDir.resolve(".naru/local/directives/").list().stream().filter(x -> x.name().endsWith(".md")).sorted(Comparator.comparing(x -> x.name())).collect(Collectors.toList())) {
            all.addAll(loadDirectivesFile(path));
        }
        if (!all.isEmpty()) {
            pushStatements(all.toArray(new NaruStatement[0]));
            this.run();
        }
        return this;
    }

    @Override
    public NaruSession pushStatement(NaruStatement any) {
        if (todo.isEmpty()) {
            todo.add(0, new RunContextImpl());
        }
        if (todo.get(0).todo.isEmpty()) {
            todo.get(0).todo.add(0, any);
        } else {
            NaruStatement z = todo.get(0).todo.get(0);
            if (z instanceof NaruIncrementalStmt && ((NaruIncrementalStmt) z).isPending()) {
                NaruIncrementalStmt z1 = (NaruIncrementalStmt) z;
                if (z1.acceptStatement(any, this)) {
                    z1.exec(this);
                }
            } else {
                todo.get(0).todo.add(0, any);
            }
        }
        fireChanged();
        return this;
    }

    @Override
    public NaruSession pushStatements(NaruStatement... any) {
        for (int i = any.length - 1; i >= 0; i--) {
            pushStatement(any[i]);
        }
        return this;
    }

    @Override
    public void throwError(NMsg nMsg) {
        log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error : %s", nMsg));
    }

    @Override
    public String inputBuffer() {
        return inputBuffer;
    }

    @Override
    public NaruSession inputBuffer(String buffer) {
        String n = buffer == null ? "" : buffer;
        if(!Objects.equals(n, this.inputBuffer)) {
            this.inputBuffer = n;
            fireChanged();
        }
        return this;
    }

    @Override
    public Object evalExpression(String condition) {
        NExprContext ctx = NExprContextBuilder.of()
                .declareBuiltins()
                .declareMathConstants()
                .declareMathFunctions()
                .declarePhysicsConstants()
                .declareVars(new NExprVarResolver() {
                    @Override
                    public NOptional<NExprVar> getVar(String varName, NExprContext context) {
                        return NOptional.of(NExprVar.ofVar(varName,
                                a -> resolveVariable(varName),
                                (a, v) -> setVariable(varName, a)
                        ));
                    }
                }).build();
        NOptional<NExprNode> n = ctx.parse(condition);
        if (!n.isPresent()) {
            throwError(NMsg.ofC("Error parsing expression '%s'", condition));
        }
        try {
            NOptional<Object> eval = n.get().eval(ctx);
            if (eval.isPresent()) {
                throwError(NMsg.ofC("Error evaluating expression '%s'", condition));
                return null;
            }
            return eval.get();
        } catch (Exception e) {
            throwError(NMsg.ofC("Error evaluating expression '%s'", condition));
            return null;
        }
    }

    public void advancePcOrEnd() {
        RunContextImpl c = (RunContextImpl) getTopContext();
        if (c == null) {
            return;
        }
        String routineName = c.getRoutine();
        if (routineName != null) {
            NaruRoutine routine = routineManager().getRoutine(routineName);
            Integer next = routine.getLines().higherKey(pc());
            if (next != null) {
                pc(next);
                pushStatement(NaruStatementHelper.ofExecRoutineLine());
            } else {
                log(NaruLogMode.PROGRESS, NMsg.ofC("Routine execution finished."));
                pc(-1);
//                if (isForever()) {
//                    pushStatement(NaruStatementHelper.ofReadLine());
//                }
            }
        } else {

        }
    }

    @Override
    public int userQueriesCount() {
        return userQueries;
    }

    @Override
    public boolean isRequireUserInput() {
        return requireUserInput;
    }

    @Override
    public NaruSession setRequireUserInput(boolean requireUserInput) {
        this.requireUserInput = requireUserInput;
        fireChanged();
        return this;
    }

    @Override
    public boolean addHistory(String m) {
        if (!NBlankable.isBlank(m)) {
            addHistory(NaruMessage.user(m));
            return true;
        }
        return false;
    }

    @Override
    public void addHistory(NaruMessage assistantMsg) {
        if (assistantMsg.getRole().equals(NaruRole.system)) {
            systemHistory.add(assistantMsg);
        } else {
            if (assistantMsg.getRole().equals(NaruRole.user)) {
                userQueries++;
            }
            history.add(assistantMsg);
        }
        fireChanged();
    }

    @Override
    public void setLastResult(NaruMessage lastResult) {
        this.lastResult = lastResult;
        fireChanged();
    }

    @Override
    public NaruSessionManager sessionManager() {
        return sessionManager;
    }

    @Override
    public NaruRegistry registry() {
        return agent.registry();
    }

    public NaruMeteringService meteringService() {
        return meteringService;
    }

    // Get the top RunContext (index 0) safely
    public RunContext getTopContext() {
        return todo.isEmpty() ? null : todo.get(0);
    }

    // Get param frame from top context
    public Map<String, Object> getParamFrame() {
        RunContext ctx = getTopContext();
        return ctx == null ? new HashMap<>() : new HashMap<>(ctx.params());
    }

    // Add to NaruSessionImpl:
    public void setVariable(String key, Object value) {
        RunContext ctx = getTopContext();
        if (ctx != null) {
            if (ctx.hasParam(key)) {
                //params are not modifiable
                return;
            }
            if (ctx.hasState(key)) {
                ctx.setState(key, value);
                return;
            }
            if (globalState.containsKey(key)) {
                globalState.put(key, value);
            } else {
                ctx.setState(key, value);
            }
            return;
        }
        globalState.put(key, value);
    }

    public Object resolveVariable(String key) {
        RunContext ctx = getTopContext();
        if (ctx != null) {
            if (ctx.hasParam(key)) return ctx.getParam(key);
            if (ctx.hasState(key)) return ctx.getState(key);
        }
        return globalState.get(key); // Fallback to session-wide
    }

    @Override
    public NaruSkill findSkill(String name) {
        return skillManager.findSkill(name);
    }

    @Override
    public boolean loadSkill(String name) {
        if (NBlankable.isBlank(name)) {
            return false;
        }
        if (skills.contains(name)) {
            return false;
        }
        NaruSkill s = skillManager.findSkill(name);
        if (s == null) {
            return false;
        }
        skills.add(name);
        return true;
    }

    @Override
    public boolean unloadSkill(String name) {
        if (NBlankable.isBlank(name)) {
            return false;
        }
        name = NNameFormat.LOWER_KEBAB_CASE.format(name.trim());
        if (skills.contains(name)) {
            skills.remove(name);
            return true;
        }
        return false;
    }

    @Override
    public List<NaruResourceInfo> listSkills() {
        return skills.stream().map(x -> skillManager.findSkillInfo(x)).filter(x -> x != null).collect(Collectors.toList());
    }

    @Override
    public NaruSession run() {
        while (hasMoreStatements()) {
            this.runStep();
        }
        return this;
    }

    @Override
    public NaruSession runOrReadline() {
        boolean doExit = false;
        while (!doExit) {
            try {
                if (hasMoreStatements()) {
                    while (hasMoreStatements()) {
                        this.runStep();
                    }
                } else {
                    pushStatements(NaruStatementHelper.ofReadLine());
                }
            } catch (NCancelException e) {
                break;
            } catch (Exception e) {
                log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error running step: %s", e));
                if(!hasMoreStatements()){
                    pushStatements(NaruStatementHelper.ofReadLine());
                }
            }
        }
        return this;
    }

    @Override
    public NaruSession runStep() {
        agent.invokeStep(this);
        return this;
    }
}
