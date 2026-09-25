package net.thevpc.naru.api.task;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.scheduler.*;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.routine.NaruTaskFrame;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.expr.NExprContextBuilder;
import net.thevpc.nuts.expr.NExprVarResolver;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NOptional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public interface NaruTask extends NToElement {
    boolean isFg();

    long id();

    List<NaruTaskStackFrame> stackframes();

    List<NaruTaskStackItem> stacktrace();

    Instant creationTime();

    NaruTask fg();

    NaruTask bg();

    NaruTaskStatus status();

    NaruTask load(NElement element);

    NPath workingDir();

    NaruTask setProjectDir(NPath projectDir);

    NAruInputMode inputMode();

    NaruTask inputMode(NAruInputMode inputMode);

    NaruPromptMode promptMode();

    NaruTask promptMode(NaruPromptMode newMode);

    NaruTaskMode taskMode();

    NaruTask taskMode(NaruTaskMode newMode);

    NPath projectDir();

    String getExtraContext();

    NaruTask setExtraContext(String extraContext);

    void log(NaruLogMode mode, NMsg s);


    NaruTask addToolExclusion(String toolName);

    NaruTask removeToolExclusion(String toolName);

    Set<String> findToolExclusions();

    NaruTask removeToolTag(String toolTag);

    NaruTask addToolTag(String toolTag);

    List<NaruToolTag> findToolTags();

    List<NaruToolDefinition> findTools();

    NaruModelRequest context(NaruSource... sources);

    boolean removeHistoryAt(int index);

    int pc();

    NaruTask pc(int nextPc);

    int clearHistory();

    NaruModelConfig model();

    NaruTask setModel(NaruModelConfig model);

    int trimHistory(int count);

    NaruTask kill();

    /**
     * Whether {@link #kill()} has been requested and not yet cleared by
     * {@code reset()}.
     * <p>
     * Cancellation is cooperative: {@code kill()} marks the task so that the
     * scheduler stops re-queueing it at the next statement boundary, but a
     * statement already executing is not interrupted. Long running tools and
     * model calls should poll this and bail out early rather than run to
     * completion. {@link #status()} alone is not sufficient for polling, since
     * the scheduler transiently flips it to {@code RUNNING} around every tick.
     */
    boolean isKillRequested();

    boolean hasMoreStatements();

    NaruTask addStatement(NaruStatement any);

    NaruTask prependStatement(NaruStatement any);

    NaruTask prependStatements(NaruStatement... any);

    NaruTask loadLines(String... any);

    NaruTask loadFiles(NPath... any);

    NaruTask addStatements(NaruStatement... any);

    void throwError(NMsg nMsg);

    String inputBuffer();

    NaruTask inputBuffer(String buffer);

    boolean addHistory(String m);

    void addSystemHistory(Function<NaruTask, NaruMessage> sysHistory);

    void addHistory(NaruMessage assistantMsg);

    void setLastResult(NaruMessage lastResult);

    void setReturnResult(Object returnResult);

    Object getReturnResult();

    NaruMessage getLastResult();

    boolean loadSkill(String name);

    boolean unloadSkill(String name);

    Set<String> skillNames();

    List<NaruResourceInfo> skills();

    void tick();

    NaruStmtResult invokeDirective(NaruDirective dir, NaruDirectiveCallContext context);

    void invokeDirective(String line);

    void invokeRoutine(String routineName);

    NOptional<NaruStatement> nextStatement();

    NOptional<NaruStatement> peekStatement();

    NaruTaskFrame peekFrame();

    NaruTask popFrame();

    NaruTaskFrame pushFrame(String routine, boolean inheritVars);

    NaruTaskFrame frame();

    int[] pctrace();

    NaruSchedulerMode schedulerMode();

    NaruTask schedulerMode(NaruSchedulerMode mode);

    NaruTask unsetTaskEnv(String key);

    NaruTask setTaskEnv(String key, Object value);

    NOptional<Object> getTaskEnv(String key, boolean inherited);

    Object resolveVariable(String key);

    NaruTask pushStatementModelCall(String prompt);

    NExprVarResolver varResolver();

    NExprContextBuilder expressionBuilder();

    Object evalExpression(String condition);

    String expandString(String condition);

    NOptional<List<NaruStatement>> parseFile(NPath path);

    NOptional<NaruStatement> parseStatement(String line);

    NPath resolve(String path);

    NaruResponse chat(NaruModelConfig modelKey, NaruModelRequest request);

    NaruSession session();

    NaruTask setWorkingDir(NPath workingDir);

    void reset();

    NaruTask hold();

    NaruTask unhold();

    boolean isHeld();

    NaruTask awaitFilter(NaruEventFilter nv);

    NaruEventFilter awaitFilter();

    List<NaruEvent> awaitReceived();

    NaruTask releaseStepPermit();


    NaruTaskInbox inbox();
//    NaruTask addInbox(NaruEvent event);

    Map<String, NaruEventSubscription> eventSubscriptions();

    NaruTask subscribe(String eventType, NaruEventSubscription subscription);


//    NaruEvent pollInbox();

    NaruTask acquireStepPermit();

    long parentId();

    String name();

    NaruTask name(String newName);

    NOptional<NaruTask> parent();

    NaruTask defaultAdvance(NaruStatement stmt);

    // NaruTask — just signals need
    void requestInput(NMsg prompt);

    // NaruTask — consumes delivered input
    String consumeInput();

    NaruTask fireEvent(String eventType, Map<String, Object> args, NaruEventTarget target, NaruRetentionPolicy retention);

    NaruTask sleep(NDuration duration);

    NaruTask addAwaitReceived(NaruEvent event);

    NOptional<NaruRoutine> editRoutine();

    String editRoutineName();

    NOptional<NaruRoutine> useRoutine(String name);

    void setRoutineLine(int index, String name);

    void appendRoutineLine(int increment, String name);

    Map<String, Object> getTaskEnv();

    void call(String cmdline);

    void addResultMessage(NMsg msg);
}
