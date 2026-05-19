package net.thevpc.naru.impl.agent;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.budget.NaruMeteringService;
import net.thevpc.naru.api.budget.NaruTokenTransaction;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.routine.NaruRoutineManager;
import net.thevpc.naru.api.routine.RunContext;
import net.thevpc.naru.api.skills.NaruSkill;
import net.thevpc.naru.api.skills.NaruSkillManager;
import net.thevpc.naru.impl.skill.NaruSkillManagerImpl;
import net.thevpc.naru.impl.stmt.NaruIncrementalStmt;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.naru.impl.routine.NaruRoutineManagerImpl;
import net.thevpc.naru.impl.stmt.NaruStatementHelper;
import net.thevpc.naru.impl.util.StoredStringMap;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.expr.*;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NChronometer;
import net.thevpc.nuts.util.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class NaruSessionImpl implements NaruSession, NToElement {
    private final List<NaruMessage> systemHistory = new ArrayList<>();
    private final List<NaruMessage> history = new ArrayList<>();
    private int userQueries;
    private NaruMessage lastResult;
    private boolean requireUserInput;
    private boolean forever;
    private final List<RunContextImpl> todo = new ArrayList<>();
    private final NaruAgent runner;
    private boolean publicSession;
    private NPath workingDir;
    private NaruModelKey model;
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
    private String uuid = UUID.randomUUID().toString();
    private String name = "NO_NAME";
    private Instant creationDate = Instant.now();
    private Instant modificationDate = creationDate;
    private final NaruMeteringService meteringService;
    private final NaruSessionManagerImpl sessionManager;
    // Add to NaruSessionImpl fields:
    private final Map<String, Object> globalState = new ConcurrentHashMap<>();

    public boolean isPublicSession() {
        return publicSession;
    }

    public NaruSessionImpl setPublicSession(boolean publicSession) {
        this.publicSession = publicSession;
        return this;
    }

    // Accessors
    public void setSessionEnv(String key, Object value) {
        globalState.put(key, value);
        fireChanged();
    }

    @Override
    public void setProjectEnv(String key, String value) {
        StoredStringMap<String> a = ((NaruAgentImpl) runner).getProjectEnv();
        if (value == null) {
            a.remove(key);
        } else {
            a.put(key, value);
        }
    }

    public Object getGlobalState(String key) {
        return globalState.get(key);
    }

    public Map<String, Object> getGlobalStateSnapshot() {
        return new HashMap<>(globalState);
    }


    public NaruSessionImpl(NaruAgent runner, NaruModelKey model, NPath projectDir, NaruMeteringService meteringService) {
        this.runner = runner;
        this.projectDir = projectDir;
        this.workingDir = projectDir;
        this.model = model;
        this.meteringService = meteringService;
        this.sessionManager = new NaruSessionManagerImpl(this);
        this.routineManager = new NaruRoutineManagerImpl(this);
        this.skillManager = new NaruSkillManagerImpl(this);
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
    public Map<String, NaruModelKey> modelAliases() {
        return ((NaruAgentImpl) runner).getModelAliases().toMap();
    }

    @Override
    public NaruResponse chat(NaruModelKey modelKey, List<NaruMessage> messages, List<NaruToolDefinition> tools) {
        Instant now = Instant.now();
        NChronometer chronometer = NChronometer.of();
        NaruResponse r = registry().protocol(modelKey).get().chat(messages, tools);
        meteringService.trackTransaction(new NaruTokenTransaction(
                uuid(),
                null,
                model,
                r.getPromptTokens(),
                r.getEvalTokens(),
                now,
                chronometer.stop().duration()
        ), this);
        return r;
    }

    @Override
    public Map<NaruModelKey, List<String>> reversedModelAliases() {
        HashMap<NaruModelKey, List<String>> m = new HashMap<>();
        for (Map.Entry<String, NaruModelKey> e : modelAliases().entrySet()) {
            NaruModelKey k = e.getValue();
            List<String> v = m.computeIfAbsent(k, k1 -> new ArrayList<>());
            v.add(e.getKey());
        }
        return m;
    }

    public NOptional<NaruModelKey> findModel(String keyOrName) {
        List<NaruModelKey> models = registry().modelsKeys();
        NaruModelKey a = findModelAlias(keyOrName).orNull();
        if (a != null) {
            if (models.contains(a)) {
                return NOptional.of(a);
            }
        }
        if (keyOrName.contains("/")) {
            NOptional<NaruModelKey> r = NaruModelKey.parse(keyOrName);
            if (r.isPresent()) {
                if (models.contains(r.get())) {
                    return NOptional.of(r.get());
                }
            }
        } else {
            for (NaruModelKey m : models) {
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
        ((NaruAgentImpl) runner).getModelAliases().remove(alias);
        fireChanged();
    }

    @Override
    public void addModelAlias(String alias, NaruModelKey model) {
        alias = NStringUtils.trimToNull(alias);
        if (!NBlankable.isBlank(alias)) {
            if (model != null) {
                ((NaruAgentImpl) runner).getModelAliases().put(alias, model);
                fireChanged();
            }
        }
    }

    @Override
    public NOptional<NaruModelKey> findModelAlias(String alias) {
        return ((NaruAgentImpl) runner).getModelAliases().get(alias);
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
        NElementWriter.ofTson().setNtf(false).setFormatter(NElementFormatterStyle.PRETTY)
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
    public NaruSession reset() {
        this.uuid = UUID.randomUUID().toString();
        this.name = "NO_NAME";
        this.creationDate = Instant.now();
        this.modificationDate = creationDate;


        clearHistory();
        userQueries = 0;
        lastResult = null;
        todo.clear();
        if (forever) {
            pushStatement(NaruStatementHelper.ofReadLine());
        }
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
            setPublicSession(true);
        } else {
            load(privateFile);
            setPublicSession(false);
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
        o.set("forever", forever);
        o.set("requireUserInput", requireUserInput);
        o.set("projectDir", NElement.ofString(projectDir.toString()));
        o.set("workingDir", workingDir == null ? null : NElement.ofString(workingDir.toString()));
        o.set("userQueries", userQueries);
        o.set("lastResult", lastResult == null ? null : lastResult.toElement());
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
        this.model = mv == null ? null : new NaruModelKey(mv);
        this.extraContext = o.getStringValue("extraContext").orElse(null);
        this.forever = o.getBooleanValue("forever").orElse(false);
        this.userQueries = o.getIntValue("userQueries").orElse(0);
        this.requireUserInput = o.getBooleanValue("requireUserInput").orElse(false);
        this.projectDir = o.getStringValue("projectDir").map(x -> NPath.of(x)).orElse(projectDir);
        this.workingDir = o.getStringValue("workingDir").map(x -> NPath.of(x)).orElse(workingDir);
        this.lastResult = o.get("lastResult").map(x -> NaruMessage.of(x)).orNull();
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
        sessionManager.saveSnapshot();
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
        runner.log(mode, s);
    }

    @Override
    public List<NaruMessage> history() {
        return history(false);
    }

    @Override
    public List<NaruMessage> history(boolean includeSystem) {
        if (includeSystem) {
            List<NaruMessage> all = new ArrayList<>();
            all.addAll(systemHistory.stream().map(x -> x.copy().setSource(NaruSource.SYSTEM)).collect(Collectors.toList()));

            // add project/folder level agent files
            if (workingDir.startsWith(projectDir)) {
                all.addAll(loadAgentFiles(projectDir));
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
                    all.addAll(loadAgentFiles(dir));
                }
            }

            // add skills
            for (String skill : skills) {
                NaruSkill s = skillManager.findSkill(skill);
                if(s!=null){
                    String collected = s.getLines().stream().collect(Collectors.joining("\n"));
                    all.add(NaruMessage.user(
                            "## ACTIVE SKILL DIRECTIVE: " + s.getName().toUpperCase() + "\n" +collected
                    ).setSource(NaruSource.SKILL));
                }
            }
            all.addAll(history.stream().map(x -> x.copy().setSource(NaruSource.USER)).collect(Collectors.toList()));
            return all;
        }
        return new ArrayList<>(history);
    }

    public List<NaruMessage> loadDirectives(NPath folder) {
        if (folder == null || !folder.isDirectory()) {
            return new ArrayList<>();
        }
        List<NaruMessage> all = new ArrayList<>();
        NPath a = folder.resolve(".naru/directives.md");
        boolean parentInherited = false;
        for (NaruMessage naruMessage : loadDirectivesFile(a)) {
            String c = naruMessage.getContent();
            if (NStringUtils.trim(c).equals("/inherit")) {
                if (!parentInherited) {
                    parentInherited = true;
                    all.add(naruMessage);
                }
            } else {
                all.add(naruMessage);
            }
        }
        a = folder.resolve(".naru/local/directives.md");
        for (NaruMessage naruMessage : loadDirectivesFile(a)) {
            String c = naruMessage.getContent();
            if (NStringUtils.trim(c).equals("/inherit")) {
                if (!parentInherited) {
                    parentInherited = true;
                    all.add(naruMessage);
                }
            } else {
                all.add(naruMessage);
            }
        }
        return all;
    }

    private List<NaruMessage> loadAgentFiles(NPath path) {
        List<NaruMessage> all = new ArrayList<>();
        if (path.isDirectory()) {
            NPath a = path.resolve(".naru/agent.md");
            StringBuilder sb=new StringBuilder();
            if (a.isRegularFile()) {
                if (alreadyLoadedMdFiles.add(a)) {
                    log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loading agent from %s", a));
                }
                String content = a.readString().trim();
                if(!NBlankable.isBlank(content)){
                    sb.append(content);
                }
            }
            a = path.resolve(".naru/local/agent.md");
            if (a.isRegularFile()) {
                if (alreadyLoadedMdFiles.add(a)) {
                    log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loading agent from %s", a));
                }
                String content = a.readString().trim();
                if(!NBlankable.isBlank(content)){
                    if(sb.length()>0){
                        sb.append("\n");
                    }
                    sb.append(content);
                }
            }
            String u = sb.toString().trim();
            String folderName = path.name();
            if (NBlankable.isBlank(folderName)) {
                folderName = "Root Workspace";
            }
            if(!u.isEmpty()){
                all.add(NaruMessage.user(
                        "### WORKSPACE CONTEXT OVERRIDE (" + folderName + "):\n" + u
                ).setSource(NaruSource.PROJECT_FOLDER));
            }
        }
        return all;
    }

    private List<NaruMessage> loadDirectivesFile(NPath path) {
        List<NaruMessage> a = new ArrayList<>();
        if (path.isRegularFile()) {
            if (alreadyLoadedDirectiveFiles.add(path)) {
                log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loading directives from %s", path));
            }
            for (String line : path.lines()) {
                line = line.trim();
                a.add(NaruMessage.user(line).setSource(NaruSource.PROJECT_FOLDER));
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
            fireChanged();
            loadDirectives(workingDir);
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
    public NaruModelKey model() {
        if (model != null) {
            return model;
        }
        return runner.model();
    }

    public NaruSession setModel(NaruModelKey model) {
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
    public boolean isForever() {
        return forever;
    }

    @Override
    public NaruSession setForever(boolean forever) {
        this.forever = forever;
        fireChanged();
        return this;
    }

    @Override
    public NaruAgent runner() {
        return runner;
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

    @Override
    public NaruSession pushStatementReadlineForever() {
        pushStatement(NaruStatementHelper.ofReadLine());
        setForever(true);
        return this;
    }

    @Override
    public NaruSession pushStatementModelCall() {
        pushStatement(NaruStatementHelper.ofModelCall());
        return this;
    }

    @Override
    public NaruSession prepareWorkdir() {
        loadDirectives(workingDir);
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
                if (isForever()) {
                    pushStatement(NaruStatementHelper.ofReadLine());
                }
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
        return runner.registry();
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
        if(NBlankable.isBlank(name)){
            return false;
        }
        if(skills.contains(name)){
            return false;
        }
        NaruSkill s = skillManager.findSkill(name);
        if(s==null){
            return false;
        }
        skills.add(name);
        return true;
    }

    @Override
    public boolean unloadSkill(String name) {
        if(NBlankable.isBlank(name)){
            return false;
        }
        name = NNameFormat.LOWER_KEBAB_CASE.format(name.trim());
        if(skills.contains(name)){
            skills.remove(name);
            return true;
        }
        return false;
    }

    @Override
    public List<NaruResourceInfo> listSkills() {
        return skills.stream().map(x->skillManager.findSkillInfo(x)).filter(x->x!=null).collect(Collectors.toList());
    }
}
