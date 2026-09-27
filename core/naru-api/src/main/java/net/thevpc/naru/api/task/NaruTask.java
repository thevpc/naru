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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

public interface NaruTask extends NToElement {
    /**
     * Exit code reported when a task was killed rather than allowed to finish. Deliberately
     * the conventional 130: a host reading exit codes should not have to know this enum.
     */
    int EXIT_INTERRUPTED = 130;

    /** Exit code reported when a task failed without publishing one of its own. */
    int EXIT_FAILURE = 1;

    /**
     * A task is its own handle on its own result, so this one type answers both "what is this
     * task" and "what did it produce".
     * <p>
     * The contract changes shape at termination, and it is worth being explicit about it. While
     * a task is live, {@link #status()} moves and {@link #endTime()}, {@link #error()} and
     * {@link #duration()} are empty. Once it reaches a terminal state it stops changing
     * forever, and those describe the finished run. In other words: a task is live until it
     * ends, and a record of itself afterwards.
     * <p>
     * What freezes is the <i>outcome</i>: {@link #endTime()}, {@link #error()},
     * {@link #duration()}, {@link #value()}, {@link #exitCode()} and
     * {@link #vars()}. It is frozen because a task outlives its own registration, so it may be
     * the only remaining record of what happened, and a record that a later write can edit is
     * not a record. The descriptive setters ({@link #setProjectDir}, {@link #taskMode} and the
     * rest) stay live, because those describe the task rather than its run -- this type is not a
     * value type, so do not cache one expecting immutability. Ask it again when you need to know.
     * <p>
     * Nothing here is a snapshot of the session: once a task has been deregistered, this
     * object remains the only way to reach what happened, which is why the answer has to live
     * here rather than in a registry that forgets.
     */
    boolean isFg();

    long id();

    /**
     * When this task reached a terminal state, or empty while it is still live. Frozen at that
     * moment, and the marker that says the rest of the outcome is settled too.
     */
    NOptional<Instant> endTime();

    /**
     * How long this task ran, or empty until it has ended.
     */
    NOptional<NDuration> duration();

    /**
     * Why this task failed, or empty if it did not fail. Frozen at termination.
     */
    NOptional<String> error();

    /**
     * Whether this task has finished, which is final: a completed task never runs again.
     * <p>
     * A task waiting on input or on an event is <i>not</i> completed. It is still going to be
     * given another chance, and a host awaiting it is waiting for real work rather than for a
     * verdict -- so this reads false for {@link NaruTaskStatus#BLOCKED_ON_INPUT} and
     * {@link NaruTaskStatus#BLOCKED_ON_EVENT}, and true only for
     * {@link NaruTaskStatus#DONE}, {@link NaruTaskStatus#FAILED} and
     * {@link NaruTaskStatus#KILLED}.
     */
    boolean isCompleted();

    /**
     * Whether the task finished without complaint: completed, with no error and a zero exit
     * code. A task can be {@link #isCompleted() done} and still not successful -- it produced
     * an answer <i>and</i> an objection, and those are reported independently.
     */
    boolean isSuccess();

    /**
     * What the task produced, empty if it produced nothing. Frozen at termination.
     * <p>
     * Prefers an explicit {@code /return}, and falls back to the task's last result so that a
     * script which simply ends still reports what it last computed.
     */
    NOptional<Object> value();

    /**
     * The task's own verdict, from the exit code its last statement published. A killed task
     * reports {@link #EXIT_INTERRUPTED}, and a failure that published nothing reports
     * {@link #EXIT_FAILURE}, so a non-success never quietly looks like a success. Frozen at
     * termination.
     */
    int exitCode();

    /**
     * The task's own variables, as they stood when it ended. Task-local only: values a task
     * inherited from its session are not part of what it produced, and reporting them would
     * make every task's output a dump of the session it happened to run in. Frozen at
     * termination, and unmodifiable.
     */
    Map<String, Object> vars();

    /**
     * One of this task's variables.
     * <p>
     * Distinguishes <i>absent</i> from <i>present and set to nothing</i>, which a task can do
     * deliberately: an empty result is an {@link NOptional#isEmpty() empty} optional, whereas a
     * variable that exists and holds nothing is present with a null value. Collapsing the two
     * would make a script's explicit emptiness indistinguishable from never having set it.
     */
    default NOptional<Object> var(String key) {
        Map<String, Object> vars = vars();
        if (!vars.containsKey(key)) {
            return NOptional.ofNamedEmpty(NMsg.ofC("no variable named %s", key));
        }
        return NOptional.ofNullable(vars.get(key));
    }

    /**
     * One of this task's variables, or {@code fallback} if it has no such variable.
     */
    default Object varOrDefault(String key, Object fallback) {
        return vars().getOrDefault(key, fallback);
    }

    /**
     * Throw if this task did not succeed, otherwise return this task for chaining.
     */
    NaruTask throwIfFailed();

    /**
     * Block until this task is finished.
     * <p>
     * Rejected for an {@link NaruTaskMode#INTERACTIVE} task, which finishes only when a host
     * answers it -- so this would block for as long as the host keeps it alive, which is
     * almost never what the caller meant. Use {@link #await(Duration)} for those, or drive an
     * interactive task by answering it.
     */
    void await();

    /**
     * Block until this task is finished, or until {@code timeout} elapses.
     *
     * @return whether the task finished, so a timeout is not confused with a task that
     * produced nothing
     */
    boolean await(Duration timeout);

    /**
     * Run {@code callback} when this task finishes, on a shared background executor rather
     * than the scheduler thread that ran the task -- a callback that blocks must not stall
     * every other task in the session.
     * <p>
     * Registered after the task already finished, it runs immediately. A callback that throws
     * is contained: it cannot prevent the task from reporting completion to anyone else.
     */
    void onComplete(Consumer<NaruTask> callback);

    /**
     * This task's completion, for a caller that would rather compose than block. Completes
     * normally even when the task failed: a failure is a result, not an exceptional condition,
     * and reporting it that way would force every caller to unwrap. Use {@link #throwIfFailed()}
     * to treat a failure as exceptional.
     */
    CompletableFuture<NaruTask> toFuture();

    /**
     * Stop this task, and report it as {@link NaruTaskStatus#KILLED} with exit code
     * {@link #EXIT_INTERRUPTED}. Cooperative: a task that does not yield is not forced.
     * <p>
     * Idempotent, and harmless on a task that has already finished.
     */
    default NaruTask cancel() {
        return cancel(null);
    }

    /**
     * {@link #cancel()} with a reason to record against the task, which is then reported by
     * {@link #error()}.
     */
    NaruTask cancel(String reason);

    List<NaruTaskStackFrame> stackFrames();

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

    /**
     * Whether the answer from the last {@link #chat} was already shown to the user as
     * it arrived.
     *
     * <p>The statement that made the call still owns the response, and logging it
     * again would print the same words twice: once token by token, then once whole.
     * A caller that reports the answer should ask this first. False for a batched
     * call, so the normal path is unchanged.
     */
    default boolean isResponseStreamed() {
        return false;
    }

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

    Map<String, NaruEventSubscription> eventSubscriptions();

    NaruTask subscribe(String eventType, NaruEventSubscription subscription);

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
