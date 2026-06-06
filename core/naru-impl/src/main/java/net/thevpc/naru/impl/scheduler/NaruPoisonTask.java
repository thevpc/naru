package net.thevpc.naru.impl.scheduler;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.routine.NaruTaskFrame;
import net.thevpc.naru.api.scheduler.*;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskStackFrame;
import net.thevpc.naru.api.task.NaruTaskStackItem;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.expr.NExprContextBuilder;
import net.thevpc.nuts.expr.NExprVarResolver;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NUnsupportedOperationException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NaruPoisonTask implements NaruTask {
    public static final NaruPoisonTask INSTANCE = new NaruPoisonTask();

    @Override
    public long id() {
        return -1;
    }

    @Override
    public NaruTaskStatus status() {
        return NaruTaskStatus.KILLED;
    }

    @Override
    public NaruTask load(NElement element) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NPath workingDir() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask setProjectDir(NPath projectDir) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NAruInputMode inputMode() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask inputMode(NAruInputMode inputMode) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruPromptMode promptMode() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask promptMode(NaruPromptMode newMode) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NPath projectDir() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public String getExtraContext() {
        return "";
    }

    @Override
    public NaruTask setExtraContext(String extraContext) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public void log(NaruLogMode mode, NMsg s) {

    }

    @Override
    public NaruModelRequest context(NaruSource... sources) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public boolean removeHistoryAt(int index) {
        return false;
    }

    @Override
    public int pc() {
        return 0;
    }

    @Override
    public NaruTask pc(int nextPc) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public int clearHistory() {
        return 0;
    }

    @Override
    public NaruModelConfig model() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask setModel(NaruModelConfig model) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public int trimHistory(int count) {
        return 0;
    }

    @Override
    public boolean hasMoreStatements() {
        return false;
    }

    @Override
    public NaruTask addStatement(NaruStatement any) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask prependStatement(NaruStatement any) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask loadLines(String... any) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask loadFiles(NPath... any) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask addStatements(NaruStatement... any) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public void throwError(NMsg nMsg) {

    }

    @Override
    public String inputBuffer() {
        return "";
    }

    @Override
    public NaruTask inputBuffer(String buffer) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public boolean addHistory(String m) {
        return false;
    }

    @Override
    public void addHistory(NaruMessage assistantMsg) {

    }

    @Override
    public void setLastResult(NaruMessage lastResult) {

    }

    @Override
    public void setReturnResult(Object returnResult) {

    }

    @Override
    public Object getReturnResult() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruMessage getLastResult() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public boolean loadSkill(String name) {
        return false;
    }

    @Override
    public boolean unloadSkill(String name) {
        return false;
    }

    @Override
    public Set<String> skillNames() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public List<NaruResourceInfo> skills() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public void tick() {
        throw new NUnsupportedOperationException();

    }

    @Override
    public void invokeDirective(String line) {
        throw new NUnsupportedOperationException();

    }

    @Override
    public void invokeRoutine(String routineName) {
        throw new NUnsupportedOperationException();

    }

    @Override
    public NOptional<NaruStatement> nextStatement() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTaskFrame peekFrame() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask popFrame() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTaskFrame pushFrame(int pc, Integer returnTo, String routine, boolean inheritVars) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTaskFrame frame() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public int[] pctrace() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask unsetTaskEnv(String key) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask setTaskEnv(String key, Object value) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NOptional<Object> getTaskEnv(String key, boolean inherited) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public Object resolveVariable(String key) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask pushStatementModelCall(String prompt) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NExprVarResolver varResolver() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NExprContextBuilder expressionBuilder() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public Object evalExpression(String condition) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public String expandString(String condition) {
        return "";
    }

    @Override
    public NOptional<List<NaruStatement>> parseFile(NPath path) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NOptional<NaruStatement> parseAsDirectiveStatement(String line) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NOptional<NaruStatement> parseStatement(String line) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NPath resolve(String path) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruResponse chat(NaruModelConfig modelKey, NaruModelRequest request) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruSession session() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask setWorkingDir(NPath workingDir) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public void reset() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NElement toElement() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask fg() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask hold() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask unhold() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public boolean isHeld() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask awaitFilter(NaruEventFilter nv) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruEventFilter awaitFilter() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public List<NaruEvent> awaitReceived() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask releaseStepPermit() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public Map<String, NaruEventSubscription> eventSubscriptions() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask acquireStepPermit() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public long parentId() {
        return -1;
    }

    @Override
    public NOptional<NaruTask> parent() {
        return NOptional.ofNamedEmpty(NMsg.ofC("parent task"));
    }

    @Override
    public NaruTask defaultAdvance(NaruStatement stmt) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask bg() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask prependStatements(NaruStatement... any) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruSchedulerMode schedulerMode() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask schedulerMode(NaruSchedulerMode mode) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public void requestInput(NMsg prompt) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public String consumeInput() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public boolean isFg() {
        return false;
    }

    @Override
    public Instant creationTime() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public String name() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask name(String newName) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask kill() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTaskMode taskMode() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask taskMode(NaruTaskMode newMode) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public List<NaruTaskStackFrame> stackframes() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public List<NaruTaskStackItem> stacktrace() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NOptional<NaruStatement> peekStatement() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTaskInbox inbox() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask fireEvent(String eventType, Map<String, Object> args, NaruEventTarget target, NaruRetentionPolicy retention) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask sleep(NDuration duration) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask addAwaitReceived(NaruEvent event) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruTask subscribe(String eventType, NaruEventSubscription subscription) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NOptional<NaruRoutine> currentRoutine() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public String currentRoutineName() {
        throw new NUnsupportedOperationException();
    }

    @Override
    public NaruRoutine useRoutine(String name) {
        throw new NUnsupportedOperationException();
    }

    @Override
    public void setRoutineLine(int index, String name) {

    }
}
