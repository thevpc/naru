package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.scheduler.*;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.routine.NaruTaskFrame;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.expr.NExprContextBuilder;
import net.thevpc.nuts.expr.NExprVarResolver;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface NaruTask extends NToElement {
    boolean isFg();

    long id();

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

    NaruModelRequest context(NaruSource... sources);

    boolean removeHistoryAt(int index);

    int pc();

    NaruTask pc(int nextPc);

    int clearHistory();

    NaruModelConfig model();

    NaruTask setModel(NaruModelConfig model);

    int trimHistory(int count);

    NaruTask kill();

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

    void invokeDirective(String line);

    void invokeRoutine(String routineName);

    NaruStatement popStatement();

    NaruTaskFrame peekContext();

    // Pop context (remove index 0)
    NaruTask popFrame();

    NaruTask pushFrame();

    NaruTask pushFrame(int pc, Integer returnTo, String routine);

    // Get the top RunContext (index 0) safely
    NaruTaskFrame frame();

    int[] pctrace();

    NaruSchedulerMode schedulerMode();

    NaruTask schedulerMode(NaruSchedulerMode mode);

    NaruTask unsetTaskProperty(String key);

    NaruTask setTaskProperty(String key, Object value);

    NOptional<Object> getTaskProperty(String key, boolean inherited);

    Object resolveVariable(String key);

    NaruTask pushStatementModelCall(String prompt);

    NExprVarResolver sessionVarResolver();

    NExprContextBuilder expressionBuilder();

    Object evalExpression(String condition);

    String expandString(String condition);

    NOptional<List<NaruStatement>> parseFile(NPath path);

    NOptional<NaruStatement> parseAsDirectiveStatement(String line);

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

    Map<String, NaruEvent> awaitReceived();

    NaruTask releaseStepPermit();


    NaruTask addInbox(NaruEvent event);

    Map<String, NaruEventSubscription> eventSubscriptions();

    NaruEvent pollInbox();

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

}
