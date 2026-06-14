package net.thevpc.naru.impl.engine.scheduler;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.budget.NaruTokenTransaction;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.mode.NaruStandardMode;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.routine.NaruTaskFrame;
import net.thevpc.naru.api.scheduler.*;
import net.thevpc.naru.api.skills.NaruSkill;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruStructuralDirective;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.task.NaruTaskStackFrame;
import net.thevpc.naru.api.task.NaruTaskStackItem;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.engine.stmt.*;
import net.thevpc.naru.impl.engine.stmt.shared.NaruStatementHelper;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.naru.impl.util.MarkdownWithHeader;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.core.NStoreKey;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.expr.*;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NChronometer;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.*;

import java.net.URL;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class NaruTaskImpl implements NaruTask, NaruTaskSchedulerView {
    private long id;
    private String name;
    private long parentId;
    private final List<NaruMessage> systemHistory = new ArrayList<>();
    private final List<NaruMessage> history = new ArrayList<>();
    private final Set<String> skills = new TreeSet<>();
    private NAruInputMode inputMode = NAruInputMode.LINE;
    private NPath workingDir;
    private int userQueriesCount;
    /**
     * Root directory of the project being worked on.
     */
    private NPath projectDir;
    private NaruPromptMode promptMode;
    private NaruTaskMode taskMode = NaruTaskMode.BATCH;
    private NaruTaskStatus status = NaruTaskStatus.READY;
    /**
     * multi line REPL buffer
     */
    private String inputBuffer = "";
    private NaruMessage lastResult;
    /**
     * result pushed by RETURN stmt
     */
    private Object returnResult;
    private boolean held;
    private NaruEventFilter eventFilter;
    private final Stack<NaruTaskFrameImpl> frames = new Stack<>();
    private final NaruSession session;
    private Instant creationDate;
    private Instant modificationDate;
    private NaruModelConfig model;
    private String extraContext;
    private final List<NaruEvent> awaitReceived = new ArrayList<>();
    private final Map<String, NaruEventSubscription> eventSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, NOptional<Object>> env = new ConcurrentHashMap<>();
    private final Map<String, String> eventHooks = new ConcurrentHashMap<>();
    private final Semaphore stepPermit = new Semaphore(0);
    private NaruSchedulerMode schedulerMode = NaruSchedulerMode.AUTO;
    private final List<String> linesDelivered = new ArrayList<>();
    private NMsg pendingPrompt;
    private NaruIncrementalStmt pendingStatement;
    private final NaruTaskInboxImpl inbox;
    private NaruStatement doing;

    public NaruTaskImpl(NElement element, NaruSession session) {
        this.session = session;
        this.inbox = new NaruTaskInboxImpl(this);
        load(element);
    }

    public NaruTaskImpl(long tid, long parentId, NaruSession session) {
        this.id = tid;
        this.inbox = new NaruTaskInboxImpl(this);
        this.parentId = parentId;
        this.session = session;
        this.creationDate = Instant.now();
        this.modificationDate = this.creationDate;
        this.held = true;
    }

    @Override
    public NaruTaskInbox inbox() {
        return inbox;
    }

    @Override
    public NaruTaskMode taskMode() {
        return taskMode;
    }

    @Override
    public NaruTask taskMode(NaruTaskMode newMode) {
        this.taskMode = newMode == null ? NaruTaskMode.BATCH : newMode;
        return this;
    }

    @Override
    public Instant creationTime() {
        return creationDate;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public NaruTask name(String newName) {
        this.name = newName;
        return this;
    }

    @Override
    public void requestInput(NMsg prompt) {
        this.pendingPrompt = prompt;
        status(NaruTaskStatus.BLOCKED_ON_INPUT);
    }

    @Override
    public String consumeInput() {
        if (!linesDelivered.isEmpty()) {
            return linesDelivered.remove(0);
        }
        return null;
    }

    @Override
    public void deliverInput(String line) {
        this.linesDelivered.add(line);
    }

    @Override
    public long parentId() {
        return parentId;
    }

    @Override
    public NaruTask kill() {
        status(NaruTaskStatus.KILLED);
        return this;
    }


    @Override
    public NOptional<NaruTask> parent() {
        if (parentId > 0) {
            return session.findTask(parentId);
        }
        return NOptional.ofNamedEmpty(NMsg.ofC("parent task"));
    }

    @Override
    public NaruSchedulerMode schedulerMode() {
        return schedulerMode;
    }

    @Override
    public NaruTask schedulerMode(NaruSchedulerMode mode) {
        this.schedulerMode = mode == null ? NaruSchedulerMode.AUTO : mode;
        return this;
    }

    @Override
    public List<NaruTaskStackItem> stacktrace() {
        List<NaruTaskStackItem> all = new ArrayList<>();
        for (NaruTaskFrameImpl frame : frames) {
            String u = null;
            int index = -1;
            String routineName = null;
            if (!frames.isEmpty()) {
                if (frames.peek().todo.isEmpty()) {
                    u = "<empty>";
                } else {
                    NaruStatement uu = frames.peek().todo.get(0);
                    u = uu.toString();
                }
            } else {
                routineName = frame.runningRoutine();
                if (routineName == null) {
                    u = "<empty>";
                } else {
                    NaruRoutine r = session().routine(routineName, this, false).orNull();
                    index = frame.pc();
                    if (r != null) {
                        u = r.lineCommandAt(index);
                    } else {
                        u = "<line not found " + index + ">";
                    }
                }
            }
            all.add(new NaruTaskStackItem(
                    routineName,
                    index,
                    u
            ));
        }
        Collections.reverse(all);
        return all;
    }

    @Override
    public List<NaruTaskStackFrame> stackframes() {
        List<NaruTaskStackFrame> all = new ArrayList<>();
        for (NaruTaskFrameImpl frame : frames) {
            String u = null;
            int index = -1;
            String routineName = null;
            Map<String, Object> params = frame.params();
            Map<String, Object> localVars = frame.params();
            if (!frames.isEmpty()) {
                if (frames.peek().todo.isEmpty()) {
                    u = "<empty>";
                } else {
                    NaruStatement uu = frames.peek().todo.get(0);
                    u = uu.toString();
                }
            } else {
                routineName = frame.runningRoutine();
                if (routineName == null) {
                    u = "<empty>";
                } else {
                    NaruRoutine r = session().routine(routineName, this, false).orNull();
                    index = frame.pc();
                    if (r != null) {
                        u = r.lineCommandAt(index);
                    } else {
                        u = "<line not found " + index + ">";
                    }
                }
            }
            params.putAll(frame.params());
            localVars.putAll(frame.localVars());
            all.add(new NaruTaskStackFrame(
                    routineName,
                    index,
                    u,
                    params,
                    localVars
            ));
        }
        Collections.reverse(all);
        return all;
    }

    @Override
    public NaruTask defaultAdvance(NaruStatement stmt) {
        if (stmt.injected()) {
            return this;
        }
        NaruTaskFrame c = frame();
        if (c != null) {
            if(!((NaruTaskFrameImpl)c).todo.isEmpty()){
                return this;
            }
            String r = c.runningRoutine();
            if (!NBlankable.isBlank(r)) {
                NaruRoutine routine = session().routine(r, this, false).orNull();
                if (routine != null) {
                    int a = routine.nextPc(c.pc());
                    if (a < 0) {
                        popFrame();
                    } else {
                        frame().pc(a);
//                        String u = routine.lineCommandAt(a);
//                        if (u == null) {
//                            NaruStmtResult lr = frame().getLastResult();
//                            popFrame();
////                        } else {
////                            NaruStatement s = parseStatement(u).get();
////                            prependStatement(s.injected(true));
//                        }
                    }
                }
            }
        }
        return this;
    }

    @Override
    public List<NaruEvent> awaitReceived() {
        return awaitReceived;
    }

    public boolean isHeld() {
        return held;
    }

    public NaruTask held(boolean held) {
        this.held = held;
        this.fireChanged();
        if (!held) {
            ((NaruSchedulerImpl) session.scheduler()).onUnhold(id());
        }
        return this;
    }

    @Override
    public NaruTask hold() {
        held(true);
        return this;
    }

    @Override
    public NaruTask unhold() {
        held(false);
        return this;
    }

    @Override
    public Map<String, NaruEventSubscription> eventSubscriptions() {
        return eventSubscriptions;
    }

    public NaruEventFilter awaitFilter() {
        return eventFilter;
    }

    public NaruTaskImpl awaitFilter(NaruEventFilter eventFilter) {
        this.eventFilter = eventFilter;
        if (eventFilter != null) {
            switch (status) {
                case KILLED:
                case DONE:
                case FAILED: {
                    return this;
                }
                case READY:
                case RUNNING: {
                    status(NaruTaskStatus.BLOCKED_ON_EVENT);
                    break;
                }
                case BLOCKED_ON_EVENT:
                case BLOCKED_ON_INPUT: {
                    break;
                }

            }
        }
        return this;
    }

    @Override
    public NaruTaskStatus status() {
        return status;
    }

    public NaruTask fg() {
        session.foregroundTaskId(id());
        return this;
    }

    @Override
    public NaruTask bg() {
        if (session.foregroundTaskId() == id) {
            session.foregroundTaskId(-1);
        }
        return this;
    }

    public void doing(NaruStatement doing) {
        this.doing = doing;
    }

    public void status(NaruTaskStatus newStatus) {
        NaruTaskStatus oldStatus = status;
        if (newStatus != status) {
            this.status = newStatus;
            switch (status) {
                case KILLED:
                case DONE:
                case FAILED: {
                    //frames.clear();
                    ((NaruSessionImpl) session).onTerminated(id());
                    break;
                }
            }
            if (
                    !(oldStatus == NaruTaskStatus.READY && newStatus == NaruTaskStatus.RUNNING)
                            && !(oldStatus == NaruTaskStatus.RUNNING && newStatus == NaruTaskStatus.READY)

            ) {
                log(NaruLogMode.SCHEDULER, NMsg.ofC("[%s] status %s->%s", id(), oldStatus, newStatus));
            }
            this.fireChanged();
        }
    }

    @Override
    public void reset() {
        history.clear();
        held = false;
        status = NaruTaskStatus.READY;
        userQueriesCount = 0;
        inputBuffer = "";
        lastResult = null;
        returnResult = null;
        frames.clear();
        env.clear();
        skills.clear();
        ((NaruSessionImpl) session).fireChanged();
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder o = NObjectElementBuilder.of();
        o.set("id", id());
        o.set("parentId", parentId());
        o.set("creationDate", NElement.ofInstant(creationDate));
        o.set("modificationDate", NElement.ofInstant(modificationDate));
        o.set("model", model == null ? null : model.toElement());
        o.set("projectDir", NElement.ofString(projectDir.toString()));
        o.set("workingDir", workingDir == null ? null : NElement.ofString(workingDir.toString()));
        o.set("userQueriesCount", userQueriesCount);

        o.set("extraContext", extraContext);
        o.set("lastResult", lastResult == null ? null : lastResult.toElement());
        o.set("inputMode", inputMode.name());
        o.set("inputBuffer", inputBuffer);
        NArrayElementBuilder _todos = NArrayElementBuilder.of();
        for (NaruTaskFrameImpl a : frames) {
            _todos.add(a.toElement());
        }
        NArrayElementBuilder _history = NArrayElementBuilder.of();
        for (NaruMessage a : history) {
            _history.add(a.toElement());
        }
        NObjectElementBuilder _props = NObjectElementBuilder.of();
        for (Map.Entry<String, NOptional<Object>> e : env.entrySet()) {
            _props.add(e.getKey(), NElements.of().toElement(e.getValue().orNull()));
        }
        o.set("history", _history.build());
        o.set("env", _props.build());
        o.set("frames", _todos.build());
        o.set("inbox", inbox.toElement());
        if (doing != null) {
            o.set("doing", doing.toElement());
        }
        return o.build();
    }

    @Override
    public NaruTask load(NElement element) {
        NObjectElement o = element.asObject().get();
        this.userQueriesCount = o.getIntValue("userQueriesCount").orElse(0);
        this.id = o.getLongValue("id").orElse(1L);
        this.parentId = o.getLongValue("parentId").orElse(-1L);
        this.creationDate = NUtils.firstNonNull(o.getInstantValue("creationDate").orElse(null), Instant.now());
        this.modificationDate = NUtils.firstNonNull(o.getInstantValue("modificationDate").orElse(null), creationDate);
        NElement mv = o.get("model").orElse(null);
        this.model = mv == null || mv.isNull() ? null : new NaruModelConfig(mv);
        this.extraContext = o.getStringValue("extraContext").orElse(null);
        this.projectDir = o.getStringValue("projectDir").map(x -> NPath.of(x)).orElse(projectDir);
        this.workingDir = o.getStringValue("workingDir").map(x -> NPath.of(x)).orElse(workingDir);
        this.lastResult = o.get("lastResult").map(x -> NaruMessage.of(x)).orNull();
        this.inputMode = o.get("inputMode").map(x -> NAruInputMode.parse(x).orElse(NAruInputMode.LINE)).orNull();
        this.inputBuffer = "";
        NOptional<NElement> ibe = o.get("inputBuffer");
        if (ibe.isPresent() && ibe.get().isAnyStringOrName()) {
            this.inputBuffer = ibe.get().asStringValue().get();
        }
        NArrayElement todo1 = o.get("frames").flatMap(x -> x.isNull() ? null : x.asArray()).orNull();
        frames.clear();
        if (todo1 != null) {
            for (NElement nElement : todo1) {
                frames.add(new NaruTaskFrameImpl(nElement));
            }
        }
        NArrayElement history1 = o.get("history").flatMap(x -> x.isNull() ? null : x.asArray()).orNull();
        history.clear();
        if (history1 != null) {
            for (NElement nElement : history1) {
                history.add(new NaruMessage(nElement));
            }
        }
        NObjectElement props1 = o.get("env").flatMap(x -> x.isNull() ? null : x.asObject()).orNull();
        env.clear();
        if (props1 != null) {
            for (NPairElement nElement : props1.namedPairs()) {
                env.put(nElement.key().asStringValue().orNull(),
                        NOptional.ofNullable(NElements.of().toSimple(nElement.value()))
                );
            }
        }
        NObjectElement inbox1 = o.get("inbox").flatMap(x -> x.isNull() ? null : x.asObject()).orNull();
        inbox.load(inbox1);
        NElement _doing = o.get("doing").orNull();
        this.doing = _doing == null ? null : NaruStatementHelper.of(_doing);
        return this;
    }


    @Override
    public long id() {
        return id;
    }

    @Override
    public boolean isFg() {
        return id == session.foregroundTaskId();
    }

    @Override
    public NPath projectDir() {
        return projectDir;
    }

    @Override
    public NPath workingDir() {
        return workingDir;
    }

    public NaruTaskImpl _setSkills(Set<String> skills) {
        this.skills.clear();
        this.skills.addAll(skills);
        return this;
    }

    public NaruTaskImpl _setInputMode(NAruInputMode inputMode) {
        this.inputMode = inputMode;
        return this;
    }

    public NaruTaskImpl _setWorkingDir(NPath workingDir) {
        this.workingDir = workingDir;
        return this;
    }

    public NaruTaskImpl _setProjectDir(NPath projectDir) {
        this.projectDir = projectDir;
        return this;
    }

    public NaruTaskImpl _setMode(NaruPromptMode mode) {
        this.promptMode = mode;
        return this;
    }


    public NaruTaskImpl _setInputBuffer(String inputBuffer) {
        this.inputBuffer = inputBuffer;
        return this;
    }

    public NaruTaskImpl _setLastResult(NaruMessage lastResult) {
        this.lastResult = lastResult;
        return this;
    }

    public NaruTaskImpl _setReturnResult(Object returnResult) {
        this.returnResult = returnResult;
        return this;
    }

    public NaruTaskImpl _setModel(NaruModelConfig model) {
        this.model = model;
        return this;
    }

    @Override
    public NaruTask setProjectDir(NPath projectDir) {
        this.projectDir = projectDir;
        fireChanged();
        return this;
    }

    @Override
    public NAruInputMode inputMode() {
        return inputMode;
    }

    @Override
    public NaruTask inputMode(NAruInputMode inputMode) {
        if (inputMode != null) {
            if (inputMode != this.inputMode) {
                this.inputMode = inputMode;
                fireChanged();
            }
        }
        return this;
    }

    protected void fireChanged() {
        this.modificationDate = Instant.now();
        ((NaruSessionImpl) session).fireChangedTask(id());
    }

    public NaruSession session() {
        return session;
    }

    @Override
    public NaruPromptMode promptMode() {
        if (promptMode != null) {
            return promptMode;
        }
        return session().registry().mode(NaruStandardMode.PLANNING).get();
    }

    @Override
    public NaruTask promptMode(NaruPromptMode newMode) {
        if (newMode != null) {
            this.promptMode = newMode;
        }
        return this;
    }

    @Override
    public String getExtraContext() {
        return extraContext;
    }

    @Override
    public NaruTask setExtraContext(String extraContext) {
        this.extraContext = extraContext;
        fireChanged();
        return this;
    }

    @Override
    public void log(NaruLogMode mode, NMsg s) {
        if (mode == NaruLogMode.SCHEDULER) {
            if (isTrace()) {
                session().log(mode, s);
            }
        } else {
            session().log(mode, s);
        }
    }

    @Override
    public NaruModelRequest context(NaruSource... sources) {
        List<NaruToolDefinition> defs = new ArrayList<>();
        NaruPromptMode mode = promptMode();
        for (NaruTool t : session().registry().tools().values()) {
            if (t.acceptMode(mode)) {
                defs.add(t.getDefinition(session()));
            }
        }
        List<MarkdownWithHeader> headerAndTexts = loadLoadModelAgentInfos(sources);
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
        all.add(NaruMessage.system(promptMode().systemPrompt()).setSource(NaruSource.MODE).setSourceName(NNameFormat.LOWER_KEBAB_CASE.format(promptMode().name())));
        HashMap<String, NElement> env = new HashMap<>();
        for (MarkdownWithHeader h : headerAndTexts) {
            if (!h.header().isEmpty()) {
                ((NaruSessionImpl) session)._reportUsing(h.source());
                env.putAll(h.header());
            }
            if (!NBlankable.isBlank(h.body())) {
                ((NaruSessionImpl) session)._reportUsing(h.source());
                switch (h.sourceType()) {
                    case CLASSPATH: {
                        all.add(
                                NaruMessage.system(
                                        "### AGENT CLASSPATH:\n" + h.body()
                                ).setSource(NaruSource.CLASSPATH).setSourceName(h.source().toString())
                        );
                        break;
                    }
                    case USER_HOME: {
                        all.add(
                                NaruMessage.system(
                                        "### USER CONTEXT:\n" + h.body()
                                ).setSource(NaruSource.USER_HOME).setSourceName(h.source().toString())
                        );
                        break;
                    }
                    case WORKSPACE: {
                        all.add(
                                NaruMessage.system(
                                        "### USER WORKSPACE CONTEXT:\n" + h.body()
                                ).setSource(NaruSource.USER_HOME).setSourceName(h.source().toString())
                        );
                        break;
                    }
                    case PROJECT: {
                        all.add(
                                NaruMessage.system(
                                        "### PROJECT CONTEXT:\n" + h.body()
                                ).setSource(NaruSource.PROJECT).setSourceName(h.source().toString())
                        );
                        break;
                    }
                    case FOLDER: {
                        all.add(
                                NaruMessage.system(
                                        "### PROJECT FOLDER CONTEXT: " + h.source().relativize(projectDir) + "\n" + h.body()
                                ).setSource(NaruSource.PROJECT).setSourceName(h.source().toString())
                        );
                        break;
                    }
                }
            }
        }

//        String[] agentFileNames = resolveAgentFileNames();
//        if (sourcesOk.contains(NaruSource.CLASSPATH)) {
//            all.addAll(loadAgentClassPath(agentFileNames));
//        }
        Predicate<String> namePredicate = x -> x.endsWith(".md");
        Predicate<NPath> pathPredicate = x -> x.name().endsWith(".md");
        Comparator<NPath> pathComparator = Comparator.comparing(x -> x.name());
        if (sourcesOk.contains(NaruSource.USER_HOME)) {
            for (NPath p : NPath.ofUserHome().resolve(".naru/agent/").list().stream().filter(pathPredicate)
                    .sorted(pathComparator)
                    .collect(Collectors.toList())) {
                MarkdownWithHeader h = MarkdownWithHeader.of(p, NaruSource.USER_HOME).orNull();
                if (h != null) {
                    ((NaruSessionImpl) session)._reportUsing(h.source());
                    env.putAll(h.header());
                    if (!h.body().isEmpty()) {
                        all.add(
                                NaruMessage.user(
                                        "### USER LOCAL SPECIFIC CONTEXT: " + h.source().relativize(projectDir) + "\n" + h.body()
                                ).setSource(NaruSource.PROJECT).setSourceName(h.source().toString())
                        );
                    }
                }
            }
        }
        // add project/folder level agent files
        if (sourcesOk.contains(NaruSource.PROJECT) || sourcesOk.contains(NaruSource.FOLDER)) {
            if (!workingDir.startsWith(projectDir)) {
                if (sourcesOk.contains(NaruSource.PROJECT)) {
                    for (NPath p : ((NaruSessionImpl) session).listOverridablePaths(
                            projectDir.resolve(".naru/agent"),
                            projectDir.resolve(".naru/local/agent"),
                            namePredicate
                    )) {
                        MarkdownWithHeader h = MarkdownWithHeader.of(p, NaruSource.USER_HOME).orNull();
                        if (h != null) {
                            ((NaruSessionImpl) session)._reportUsing(h.source());
                            env.putAll(h.header());
                            if (!h.body().isEmpty()) {
                                all.add(
                                        NaruMessage.user(
                                                "### USER PROJECT SPECIFIC CONTEXT: " + h.source().relativize(projectDir) + "\n" + h.body()
                                        ).setSource(NaruSource.PROJECT).setSourceName(h.source().toString())
                                );
                            }
                        }
                    }
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
                    NaruSource st = dir.equals(projectDir) ? NaruSource.PROJECT : NaruSource.FOLDER;

                    for (NPath pp : ((NaruSessionImpl) session).listOverridablePaths(
                            dir.resolve(".naru/agent"),
                            dir.resolve(".naru/local/agent"),
                            namePredicate
                    )) {
                        MarkdownWithHeader h = MarkdownWithHeader.of(pp, NaruSource.USER_HOME).orNull();
                        if (h != null) {
                            ((NaruSessionImpl) session)._reportUsing(h.source());
                            env.putAll(h.header());
                            if (!h.body().isEmpty()) {
                                all.add(
                                        NaruMessage.user(
                                                st == NaruSource.PROJECT ?
                                                        ("### USER PROJECT SPECIFIC CONTEXT: \n" + h.body())
                                                        : ("### USER FOLDER SPECIFIC CONTEXT: " + h.source().relativize(projectDir) + "\n" + h.body())
                                        ).setSource(st).setSourceName(h.source().toString())
                                );
                            }
                        }
                    }
                }
            }
        }

        if (sourcesOk.contains(NaruSource.SKILL)) {
            // add skills
            for (String skill : skills) {
                NaruSkill s = session().skillManager().findSkill(skill);
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
        return new NaruModelRequest(
                all,
                defs,
                env
        );
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

    @Override
    public int pc() {
        if (frames.isEmpty()) {
            return -1;
        }
        return frames.peek().pc();
    }

    @Override
    public NaruTask pc(int nextPc) {
        if (!frames.isEmpty()) {
            frames.peek().pc(nextPc);
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
        NaruModelConfig model = session().agent().env().get("model").map(x -> x == null || x.isNull() ? null : new NaruModelConfig(x)).orNull();
        if (model != null) {
            return model;
        }
        model = session().registry().modelsKeys(session()).stream().findFirst().map(NaruModelConfig::new).orElse(null);
        return model;
    }

    @Override
    public NaruTask setModel(NaruModelConfig model) {
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
    public boolean hasMoreStatements() {
        if (!frames.isEmpty()) {
            normalizeRunContext();
            return !frames.isEmpty();
        } else {
            return false;
        }
    }


    private void normalizeRunContext() {
        while (!frames.isEmpty()) {
            List<NaruStatement> a = frames.peek().todo;
            if (a.isEmpty()) {
                popFrame();
            } else {
                return;
            }
        }
        fireChanged();
    }

    @Override
    public NaruTask addStatement(NaruStatement any) {
        if (frames.isEmpty()) {
            frames.add(new NaruTaskFrameImpl());
        }
        if (frames.peek().todo.isEmpty()) {
            frames.peek().todo.add(any);
        } else {
            frames.peek().todo.add(any);
        }
        fireChanged();
        return this;
    }

    @Override
    public NaruTask prependStatement(NaruStatement any) {
        if (frames.isEmpty()) {
            frames.add(new NaruTaskFrameImpl());
        }
        if (frames.peek().todo.isEmpty()) {
            frames.peek().todo.add(any);
        } else {
            frames.peek().todo.add(0, any);
        }
        fireChanged();
        return this;
    }

    @Override
    public NaruTask prependStatements(NaruStatement... any) {
        if (frames.isEmpty()) {
            frames.add(new NaruTaskFrameImpl());
        }
        if (frames.peek().todo.isEmpty()) {
            frames.peek().todo.addAll(Arrays.asList(any));
        } else {
            frames.peek().todo.addAll(0, Arrays.asList(any));
        }
        fireChanged();
        return this;
    }

    @Override
    public NaruTask loadFiles(NPath... any) {
        for (NPath path : any) {
            if (path != null) {
                if (path.exists() && path.isFile()) {
                    addStatements(parseFile(path).get().toArray(new NaruStatement[0]));
                } else {
                    log(NaruLogMode.TRACE, NMsg.ofC("Skipping file %s: does not exist", path));
                }
            }
        }
        return this;
    }

    @Override
    public NaruTask loadLines(String... any) {
        for (String path : any) {
            if (path != null) {
                NaruStatement p = parseStatement(path).orNull();
                if (p != null) {
                    addStatements(p);
                }
            }
        }
        return this;

    }

    @Override
    public NaruTask addStatements(NaruStatement... any) {
        for (NaruStatement s : any) {
            addStatement(s);
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
    public NaruTask inputBuffer(String buffer) {
        String n = buffer == null ? "" : buffer;
        if (!Objects.equals(n, this.inputBuffer)) {
            this.inputBuffer = n;
            fireChanged();
        }
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
                userQueriesCount++;
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
    public void setReturnResult(Object returnResult) {
        this.returnResult = returnResult;
        fireChanged();
    }

    @Override
    public Object getReturnResult() {
        return returnResult;
    }

    @Override
    public NaruMessage getLastResult() {
        return lastResult;
    }

    @Override
    public boolean loadSkill(String name) {
        if (NBlankable.isBlank(name)) {
            return false;
        }
        if (skills.contains(name)) {
            return false;
        }
        NaruSkill s = session().skillManager().findSkill(name);
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

    public Set<String> skillNames() {
        return new TreeSet<>(skills);
    }

    @Override
    public List<NaruResourceInfo> skills() {
        return skills.stream().map(x -> session().skillManager().findSkillInfo(x)).filter(x -> x != null).collect(Collectors.toList());
    }

    @Override
    public void tick() {
        if (doing != null) {
            //after crash, redo pending command
            try {
                doing.exec(this);
            } finally {
                doing = null;
                fireChanged();
            }
            return;
        }
        if (pendingStatement != null) {
            // if still waiting for complex mutiline command (like /for and /while) to complete
            if (frames.isEmpty()) {
                //log(NaruLogMode.SCHEDULER, NMsg.ofC("PENDING %s", pendingStatement.toElement()));
                //throwError(NMsg.ofC("PENDING %s", pendingStatement.toElement()));
                return;
            }
            NaruTaskFrameImpl frame = frames.get(frames.size() - 1);
            if (!frame.todo.isEmpty()) {
                if (pendingStatement.acceptStatement(frame.todo.get(0), this)) {
                    frame.todo.remove(0);
                    if (!pendingStatement.isPending()) {
                        NaruIncrementalStmt op2 = pendingStatement;
                        pendingStatement = null;
                        _exeRollableStmt(op2);
                        return;
                    } else {
                        //still pending
                        return;
                    }
                } else {
                    log(NaruLogMode.SCHEDULER, NMsg.ofC("[%s] pending %s", id(), pendingStatement.toElement()));
                    throwError(NMsg.ofC("PENDING %s", pendingStatement.toElement()));
                    return;
                }
            }

            // 2. check live routine via PC
            String routineName = frame.runningRoutine();
            if (routineName == null) {
                //still pending
                return;
            }

            NaruRoutine routine = session()
                    .routine(routineName, this, false).orNull();
            if (routine == null) {
                //still pending
                return;
            }
            String line = routine.lineCommandAt(frame.pc());
            if (line == null) {
                log(NaruLogMode.SCHEDULER, NMsg.ofC("PENDING %s", pendingStatement.toElement()));
                throwError(NMsg.ofC("PENDING %s", pendingStatement.toElement()));
                return;
            }
            NaruStatement n = parseStatement(line).orNull();
            if (n != null) {
                if (pendingStatement.acceptStatement(n, this)) {
                    frame.pc(routine.nextPc(frame.pc()));
                    if (!pendingStatement.isPending()) {
                        NaruIncrementalStmt op2 = pendingStatement;
                        pendingStatement = null;
                        _exeRollableStmt(op2);
                        return;
                    } else {
                        //still pending
                        return;
                    }
                } else {
                    log(NaruLogMode.SCHEDULER, NMsg.ofC("[%s] pending %s", id(), pendingStatement.toElement()));
                    throwError(NMsg.ofC("PENDING %s", pendingStatement.toElement()));
                    return;
                }
            } else {
                //still pending
                return;
            }
        }
        if (taskMode() == NaruTaskMode.INTERACTIVE) {
            // if interactive mode and no stmts to run, inject readline
            if (!frames.isEmpty()) {
                if (frames.peek().todo.isEmpty() && NBlankable.isBlank(frames.peek().runningRoutine())) {
                    new NaruReadlineStmt().injected(true).exec(this);
                    return;
                }
            } else {
                new NaruReadlineStmt().injected(true).exec(this);
                return;
            }
        }
        // default run next statement or terminate
        NaruStatement op = this.nextStatement().orNull();
        if (op == null) {
            if (taskMode() == NaruTaskMode.INTERACTIVE) {
                new NaruReadlineStmt().injected(true).exec(this);
            }else {
                status(NaruTaskStatus.DONE);
            }
        } else {
            if (op instanceof NaruIncrementalStmt incrementalStmt && incrementalStmt.isPending()) {
                this.pendingStatement = incrementalStmt;
                fireChanged();
                defaultAdvance(op);
                return;
            }
            _exeRollableStmt(op);
        }
    }

    private void _exeRollableStmt(NaruStatement op) {
        log(NaruLogMode.SCHEDULER, NMsg.ofC("[%s] invoke : %s", id(), op.toElement()));
        doing = op;
        fireChanged();
        try {
            op.exec(this);
        } finally {
            doing = null;
            fireChanged();
        }
    }

    public boolean isTrace() {
        return getTaskEnv("trace", true).map(x -> NLiteral.of(x).asBoolean().orNull()).orElse(false);
    }

    @Override
    public void invokeDirective(String line) {
        if (line.startsWith("/")) {
            line = line.substring(1).trim();
        } else {
            log(NaruLogMode.TRACE, NMsg.ofC("Unknown directive : %s", line));
            return;
        }
        int idx = line.indexOf(' ');
        String cmd = line;
        if (idx != -1) {
            cmd = line.substring(0, idx);
            line = line.substring(idx + 1).trim();
        } else {
            line = "";
        }
        NaruDirective dir = session().registry().findDirective(cmd).orNull();
        if (dir == null) {
            log(NaruLogMode.TRACE, NMsg.ofC("ERROR: Unknown directive '/" + cmd + "'. Available tools: " + session().registry().directives().keySet()).asError());
            return;
        }
        try {
            dir.execute(new NaruDirectiveCallContextImpl(cmd, line, this));
        } catch (NCancelException e) {
            throw e;
        } catch (Exception e) {
            log(NaruLogMode.TRACE, NMsg.ofC("ERROR executing directive '/" + cmd + "': " + e.getMessage()).asError());
        }
    }

    private NOptional<NaruStatement> parseAsDirectiveStatement(String line) {
        if (line.startsWith("/")) {
            String line2 = line;
            if (line2.startsWith("/")) {
                line2 = line2.substring(1).trim();
            } else {
                return NOptional.ofNamedEmpty(NMsg.ofC("directive : %s", line2));
            }
            int idx = line2.indexOf(' ');
            String cmd = line2;
            if (idx != -1) {
                cmd = line2.substring(0, idx);
                line2 = line2.substring(idx + 1).trim();
            } else {
                line2 = "";
            }
            NOptional<NaruStatement> s = NaruStatementHelper.of(cmd, line2);
            if (s.isPresent()) {
                return s;
            }
            NaruDirective d = session().registry().findDirective(cmd).orNull();
            if (d == null) {
                return NOptional.ofNamedEmpty(NMsg.ofC("directive : %s", cmd));
            }
            if (d instanceof NaruStructuralDirective) {
                return NOptional.of(((NaruStructuralDirective) d).toStatement(line2, this));
            } else {
                return NOptional.of(new NaruDirectiveAsStmt(line));
            }
        }
        return NOptional.ofNamedEmpty(NMsg.ofC("directive : %s", line));
    }
    @Override
    public void invokeRoutine(String routineName) {
        NaruRoutine routine = session().routine(routineName, this, false).orNull();
        if (routine.isEmpty()) {
            log(NaruLogMode.TRACE, NMsg.ofC("Routine '%s' is empty. Nothing to execute.", NMsg.ofStyledPrimary1(routineName)));
            return;
        }
        String sysPrompt = "You are executing a script named '" + routineName + "'.\n" +
                "Here is the full script for context:\n" +
                routine.getFormattedText() + "\n" +
                "I will instruct you to execute one line at a time.";
        this.addHistory(NaruMessage.system(sysPrompt));
        this.pushFrame(routineName, false);
        this.tick();
    }

    private NaruStatement statementAtFrame(NaruTaskFrameImpl frame, boolean consume) {
        if (frame == null) {
            return null;
        }
        if (!frame.todo.isEmpty()) {
            NaruStatement r = frame.todo.get(0);
            if (consume) {
                frame.todo.remove(0);
                fireChanged();
            }
            return r;
        }

        String routineName = frame.runningRoutine();
        if (routineName == null) {
            return null; // anonymous frame, exhausted
        }
        NaruRoutine routine = session()
                .routine(routineName, this, false).orNull();
        if (routine == null) {
            return null;
        }
        String line = routine.lineCommandAt(frame.pc());
        if (line == null) {
            return null;
        }
        return parseStatement(line).orElse(new NaruNopStmt());
    }

    @Override
    public NOptional<NaruStatement> nextStatement() {
        while (!frames.isEmpty()) {
            NaruStatement z = statementAtFrame(frames.peek(), true);
            if (z != null) {
                return NOptional.of(z);
            }
            popFrame();
        }
        return NOptional.ofNamedEmpty("stmt");
    }

    @Override
    public NOptional<NaruStatement> peekStatement() {
        for (int i = frames.size() - 1; i >= 0; i--) {
            NaruStatement z = statementAtFrame(frames.get(i), false);
            if (z != null) {
                return NOptional.of(z);
            }
        }
        return NOptional.ofNamedEmpty("stmt");
    }

    @Override
    public NaruTaskFrame peekFrame() {
        if (frames.isEmpty()) {
            return null;
        }
        return frames.peek();
    }

    // Pop context (remove index 0)
    @Override
    public NaruTask popFrame() {
        if (!frames.isEmpty()) {
            NaruTaskFrame f2 = frame();
            NaruStmtResult lr = f2.getLastResult();
            frames.pop();
            if(!frames.isEmpty()) {
                NaruTaskFrameImpl p = frames.peek();
                if(p!=null){
                    p.lastResult(lr);
                }
            }
            normalizeRunContext();
            fireChanged();
        }
        return this;
    }

    @Override
    public NaruTaskFrame pushFrame(String routine, boolean inheritVars) {
        NaruTaskFrameImpl cc = new NaruTaskFrameImpl();
        if (NBlankable.isBlank(routine)) {
            cc.pc(-1);
            cc.inheritVars(inheritVars);
            frames.push(cc);
            fireChanged();
            return cc;
        } else {
            NaruRoutine r = session().routine(routine, this, false).orNull();
            if (r == null) {
                throwError(NMsg.ofC("Routine '%s' not found", routine));
                return null;
            }
            Integer pc = r.getLinesSet().firstKey();
            if (pc != null) {
                cc.pc(pc);
                cc.runningRoutine(routine);
                cc.inheritVars(inheritVars);
                frames.push(cc);
                fireChanged();
                return cc;
            } else {
                return null;
            }
        }
    }

    // Get the top RunContext (index 0) safely
    @Override
    public NaruTaskFrame frame() {
        return frames.isEmpty() ? null : frames.peek();
    }


    @Override
    public int[] pctrace() {
        List<Integer> list = new ArrayList<>();
        for (NaruTaskFrame ctx : frames) {
            list.add(ctx.pc());
        }
        return list.stream().mapToInt(x -> x).toArray();
    }

    public void _prependInitHooks() {
        List<NaruStatement> all = new ArrayList<>();
        if (workingDir.equals(projectDir)) {
            NPath p = NPath.of(NStoreKey.ofShared(NId.of("net.thevpc.naru:naru"))).resolve("init.naru");
            if (p.exists() && p.isFile()) {
                List<NaruStatement> c = parseFile(p).orNull();
                if (c != null) {
                    all.addAll(c);
                }
            }
        }
        for (NPath path : ((NaruSessionImpl) session).listOverridablePaths(
                workingDir.resolve(".naru/hooks"),
                workingDir.resolve(".naru/local/hooks"),
                a -> a.equals("init.naru")
        )) {
            List<NaruStatement> c = parseFile(path).orNull();
            if (c != null) {
                all.addAll(c);
            }
        }
        if (!all.isEmpty()) {
            prependStatements(all.toArray(new NaruStatement[0]));
        }
    }


    private class MyNExprVarResolver implements NExprVarResolver {
        @Override
        public NOptional<NExprVar> getVar(String varName, NExprContext context) {
            return NOptional.of(NExprVar.ofVar(varName,
                    a -> resolveVariable(varName).orNull(),
                    (a, v) -> setVariable(varName, a)
            ));
        }
    }

    public void setVariable(String key, Object value) {
        NaruTaskFrame ctx = frame();
        if (ctx != null) {
            boolean inheritVars = ctx.isInheritVars();
            NOptional<Object> k = ctx.getParam(key);
            if (k.isPresent()) {
                return;
            }
            k = ctx.getLocalVar(key);
            if (k.isPresent()) {
                ctx.setLocalVar(key, value);
                return;
            }
            if (inheritVars) {
                for (int i = frames.size() - 2; i >= 0; i--) {
                    ctx = frames.get(i);
                    NOptional<Object> k2 = ctx.getParam(key);
                    if (k2.isPresent()) {
                        ctx.setLocalVar(key, value);
                        return;
                    }
                    if (!ctx.isInheritVars()) {
                        break;
                    }
                }
            }
            NOptional<Object> s = getTaskEnv(key, false);
            if (s.isPresent()) {
                setTaskEnv(key, value);
            } else {
                s = session().getSessionEnv(key);
                if (s.isPresent()) {
                    session().setSessionEnv(key, value);
                } else {
                    ctx.setLocalVar(key, value);
                }
            }
        } else {
            setTaskEnv(key, value);
        }
    }

    @Override
    public NaruTask unsetTaskEnv(String key) {
        if (env.containsKey(key)) {
            env.remove(key);
            fireChanged();
        }
        return this;
    }

    @Override
    public NaruTask setTaskEnv(String key, Object value) {
        if (env.containsKey(key) && Objects.equals(env.get(key).get(), value)) {
            return this;
        }
        env.put(key, NOptional.ofNullable(value));
        fireChanged();
        return this;
    }

    @Override
    public NOptional<Object> getTaskEnv(String key, boolean inherited) {
        if (env.containsKey(key)) {
            return NOptional.ofNullable(env.get(key).get());
        }
        if (inherited) {
            return session().getSessionEnv(key);
        }
        return NOptional.ofNamedEmpty(key);
    }

    @Override
    public NOptional<Object> resolveVariable(String key) {
        NaruTaskFrame ctx = frame();
        boolean inherit = ctx != null && ctx.isInheritVars();
        if (ctx != null) {
            NOptional<Object> k = ctx.getParam(key);
            if (k.isPresent()) {
                return k;
            }
            NOptional<Object> s = ctx.getLocalVar(key);
            if (s.isPresent()) {
                return s;
            }
            if (inherit) {
                for (int i = frames.size() - 1; i >= 0; i--) {
                    ctx = frames.get(i);
                    k = ctx.getParam(key);
                    if (k.isPresent()) {
                        return k;
                    }
                    s = ctx.getLocalVar(key);
                    if (s.isPresent()) {
                        return s;
                    }
                    if (!ctx.isInheritVars()) {
                        break;
                    }
                }
            }
        }
        return getTaskEnv(key, true);
    }


    @Override
    public NaruTask pushStatementModelCall(String prompt) {
        addStatement(NaruStatementHelper.ofModelCall(prompt));
        return this;
    }

    @Override
    public NExprVarResolver varResolver() {
        return new MyNExprVarResolver();
    }

    @Override
    public NExprContextBuilder expressionBuilder() {
        return NExprContextBuilder.of()
                .declareBuiltins()
                .declareMathConstants()
                .declareMathFunctions()
                .declarePhysicsConstants()
                .declareVars(varResolver())
                .literalMapper((value, context) -> {
                    if (value instanceof NExprLiteralNode && ((NExprLiteralNode) value).value() instanceof String) {
                        return context.ofDollarInterpolatedString((String) ((NExprLiteralNode) value).value());
                    }
                    return value;
                })
                ;
    }

    @Override
    public Object evalExpression(String condition) {
        NExprContext ctx = expressionBuilder().build();
        NOptional<NExprNode> n = ctx.parse(condition);
        if (!n.isPresent()) {
            throwError(NMsg.ofC("Error parsing expression '%s'", condition));
        }
        try {
            NOptional<Object> eval = n.get().eval(ctx);
            if (!eval.isPresent()) {
                throwError(NMsg.ofC("Error evaluating expression '%s'", condition));
                return null;
            }
            return eval.get();
        } catch (Exception e) {
            throwError(NMsg.ofC("Error evaluating expression '%s'", condition));
            return null;
        }
    }

    @Override
    public String expandString(String condition) {
        NExprContext ctx = NExprContextBuilder.of()
                .declareBuiltins()
                .declareMathConstants()
                .declareMathFunctions()
                .declarePhysicsConstants()
                .declareVars(varResolver()).build();
        try {
            return ctx.ofTemplate().withBashStyle().compile(condition).runString();
        } catch (Exception e) {
            throwError(NMsg.ofC("Error evaluating expression '%s'", condition));
            return condition;
        }
    }


    @Override
    public NOptional<List<NaruStatement>> parseFile(NPath path) {
        List<NaruStatement> list = new ArrayList<>();
        if (path.isFile()) {
            for (String line : path.lines().toList()) {
                NOptional<NaruStatement> li = parseStatement(line);
                if (li.isPresent()) {
                    list.add(li.get());
                }
            }
        }
        return NOptional.of(list);
    }



    @Override
    public NOptional<NaruStatement> parseStatement(String line) {
        if (NBlankable.isBlank(line)) {
            return NOptional.ofNamedEmpty("statement");
        }
        line = line.trim();
        if (line.startsWith("#")) {
            return NOptional.of(new NaruNopStmt());
        }
        if (line.startsWith("/")) {
            NOptional<NaruStatement> a = parseAsDirectiveStatement(line);
            if (!a.isPresent()) {
                log(NaruLogMode.TRACE, NMsg.ofC("ERROR: Unknown directive '" + line + "'. Available tools: " + session().registry().directives().keySet()).asError());
            }
            return a;
        }
        Pattern LINE_PATTERN = Pattern.compile("^(\\d+)(?:\\s+(.*))?$");
        Matcher m = LINE_PATTERN.matcher(line);
        if (m.matches()) {
            int num = Integer.parseInt(m.group(1));
            String content = m.group(2) != null ? m.group(2).trim() : "";
            return NOptional.of(new NaruSetRoutineLineStmt(num, content));
        }

        LINE_PATTERN = Pattern.compile("^[+](\\d+)(?:\\s+(.*))?$");
        m = LINE_PATTERN.matcher(line);
        if (m.matches()) {
            int num = Integer.parseInt(m.group(1));
            String content = m.group(2) != null ? m.group(2).trim() : "";
            return NOptional.of(new NaruAppendRoutineLineStmt(num, content));
        }
        if (line.startsWith("+")) {
            String content = line.substring(1).trim();
            return NOptional.of(new NaruAppendRoutineLineStmt(0, content));
        }
        return NOptional.of(NaruStatementHelper.ofModelCall(line));
    }

    @Override
    public NPath resolve(String path) {
        if (NBlankable.isBlank(path)) return workingDir;
        NPath p = NPath.of(path);
        return p.isAbsolute() ? p : workingDir.resolve(p).normalize();
    }

    @Override
    public NaruResponse chat(NaruModelConfig modelKey, NaruModelRequest request) {
        Instant now = Instant.now();
        NChronometer chronometer = NChronometer.of();
        NaruResponse r = session().registry().protocol(modelKey, session()).get().chat(request, this);
        session().meteringService().trackTransaction(new NaruTokenTransaction(
                session().uuid(),
                null,
                model(),
                r.getPromptTokens(),
                r.getEvalTokens(),
                now,
                chronometer.stop().duration()
        ), session());
        return r;
    }

    List<MarkdownWithHeader> loadLoadModelAgentInfos(NaruSource... sources) {
        String[] agentFileNames = resolveModelAgentFileNames();
        HashSet<NaruSource> sourcesOk = new HashSet<>();
        if (sources != null) {
            for (NaruSource s : sources) {
                if (s != null) {
                    sourcesOk.add(s);
                }
            }
        }
        List<MarkdownWithHeader> toLoad = new ArrayList<>();
        for (String fileName : agentFileNames) {
            if (sourcesOk.contains(NaruSource.CLASSPATH)) {
                Set<ClassLoader> classLoaders = new LinkedHashSet<>(Arrays.asList(
                        getClass().getClassLoader(),
                        Thread.currentThread().getContextClassLoader()
                ));
                String r = "META-INF/naru/models/" + fileName;
                for (ClassLoader classLoader : classLoaders) {
                    URL u = classLoader.getResource(r);
                    if (u != null) {
                        toLoad.add(MarkdownWithHeader.of(NPath.of(u), NaruSource.CLASSPATH).orNull());
                    }
                }
            }
            if (sourcesOk.contains(NaruSource.USER_HOME)) {
                NPath agents = NPath.ofUserHome().resolve(".naru/models");
                NPath u = agents.resolve(fileName);
                if (u.isRegularFile()) {
                    toLoad.add(MarkdownWithHeader.of(u, NaruSource.USER_HOME).orNull());
                }
            }
            if (sourcesOk.contains(NaruSource.WORKSPACE)) {
                NPath agents = NPath.of(NStoreKey.ofShared(NId.of("net.thevpc.naru:naru"))).resolve("models");
                NPath u = agents.resolve(fileName);
                toLoad.add(MarkdownWithHeader.of(u, NaruSource.WORKSPACE).orNull());
            }
            NPath workingDir = this.workingDir == null ? projectDir : this.workingDir;
            if (sourcesOk.contains(NaruSource.PROJECT) || sourcesOk.contains(NaruSource.FOLDER)) {
                if (!workingDir.startsWith(projectDir)) {
                    if (sourcesOk.contains(NaruSource.PROJECT)) {
                        NPath u = projectDir.resolve(".naru/models").resolve(fileName);
                        toLoad.add(MarkdownWithHeader.of(u, NaruSource.PROJECT).orNull());
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
                        NPath u = dir.resolve(".naru/models").resolve(fileName);
                        if (u.isRegularFile()) {
                            if (dir.equals(projectDir)) {
                                toLoad.add(MarkdownWithHeader.of(u, NaruSource.PROJECT).orNull());
                            } else {
                                toLoad.add(MarkdownWithHeader.of(u, NaruSource.FOLDER).orNull());
                            }
                        }
                    }
                }
            }
        }
        return toLoad.stream().filter(Objects::nonNull).collect(Collectors.toList());
    }

    private String[] resolveModelAgentFileNames() {
        List<String> a = new ArrayList<>();
        a.add("default.md");
        NaruModelConfig mm = model();
        if (mm == null) {
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
            ((NaruSessionImpl) session)._reportUsing(a);
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

    public List<NaruStatement> loadDirectivesFile(NPath path) {
        List<NaruStatement> a = new ArrayList<>();
        if (path.isRegularFile()) {
            ((NaruSessionImpl) session)._reportUsing(path);
            for (String line : path.lines()) {
                line = line.trim();
                if (!line.startsWith("#") && !line.isEmpty()) {
                    a.add(parseStatement(line).get());
                }
            }
        }
        return a;
    }

    @Override
    public NaruTask setWorkingDir(NPath workingDir) {
        NPath nf = workingDir.toAbsolute(this.workingDir).normalize();
        if (!nf.equals(this.workingDir)) {
            this.workingDir = nf;
            _prependInitHooks();
            fireChanged();
        }
        return this;
    }

    @Override
    public NaruTask releaseStepPermit() {
        stepPermit.release();
        return this;
    }

    @Override
    public NaruTask acquireStepPermit() {
        try {
            stepPermit.acquire();
        } catch (InterruptedException e) {
            //throw new RuntimeException(e);
        }
        return this;
    }

    @Override
    public NMsg pendingPrompt() {
        return pendingPrompt;
    }

    @Override
    public NaruTask fireEvent(String eventType, Map<String, Object> args, NaruEventTarget target, NaruRetentionPolicy retention) {
        NaruEvent event = new NaruEvent(
                eventType, args,
                id(),
                parentId,
                Instant.now(),
                target != null ? target : NaruEventTargets.ofEveryone(),
                retention != null ? retention : NaruRetentionPolicies.ofForever()
        );
        session.eventLog().append(event);

        return this;
    }


    @Override
    public NaruTask sleep(NDuration duration) {
        //TODO
        //should be bloking based
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            //
        }
        return this;
    }

    @Override
    public NaruTask addAwaitReceived(NaruEvent event) {
        awaitReceived.add(event);
        return this;
    }

    @Override
    public NaruTask subscribe(String eventType, NaruEventSubscription subscription) {
        eventType = NStringUtils.trimToNull(eventType);
        if (eventType != null) {
            if (subscription == null) {
                eventSubscriptions.remove(eventType);
            } else {
                NAssert.requireNamedNonBlank(subscription.routineName(), "routineName");
                eventSubscriptions.put(eventType, subscription);
            }
        }
        return this;
    }


    @Override
    public NOptional<NaruRoutine> editRoutine() {
        String e = editRoutineName();
        if (e == null) {
            return NOptional.ofNamedEmpty("routine");
        }
        return session.routine(e, this, true);
    }

    @Override
    public String editRoutineName() {
        NaruTaskFrame f = frame();
        if (f != null) {
            String s = f.editRoutine();
            if (NBlankable.isBlank(s)) {
                s = f.runningRoutine();
            }
            return NStringUtils.trimToNull(s);
        }
        return null;
    }

    @Override
    public NOptional<NaruRoutine> useRoutine(String name) {
        NaruTaskFrame f = frame();
        if (f != null) {
            f.editRoutine(NStringUtils.trim(name));
        }
        String ern = editRoutineName();
        if (ern == null) {
            return NOptional.ofNamedEmpty(NMsg.ofC("routine %s", NStringUtils.firstNonBlankTrimmed(name, "self")));
        }
        return session.routine(ern, this, true);
    }

    @Override
    public void setRoutineLine(int index, String name) {
        NaruRoutine r = editRoutine().get();
        r.putLine(index, name);
    }

    @Override
    public void appendRoutineLine(int increment, String name) {
        NaruRoutine r = editRoutine().get();
        r.appendLine(increment, name);
    }

    @Override
    public Map<String, Object> getTaskEnv() {
        return env.entrySet().stream().collect(Collectors.toMap(x -> x.getKey(), x -> x.getValue().orNull()));
    }

    @Override
    public void call(String cmdline) {
        NCmdLine cmd = NCmdLine.of(cmdline);
        if (cmd.isEmpty()) {
            this.throwError(NMsg.ofC("Error statement: routine not found '%s'", ""));
        }
        String cc = cmd.next().get().image();
        NaruTaskFrame ff = this.pushFrame(cc, false);
        if (ff == null) {
            return;
        }
        // at least one stmt!
        while (!cmd.isEmpty()) {
            NArg aa = cmd.next().get();
            if (aa.value() != null) {
                ff.setParam(aa.key(), aa.stringValue());
            }
        }
//        this.prependStatement(this.parseStatement(this.session().routine(cc, this, false).get().lineCommandAt(ff.pc())).get());
    }
}
