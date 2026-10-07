package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.scheduler.NaruScheduler;
import net.thevpc.naru.api.scheduler.NaruSessionEventLog;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.api.registry.NaruRegistry;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface NaruSession {
    NaruVisibility getVisibility();

    NaruSession throttleDelay(long ms);

    NaruScheduler scheduler();

    NaruSession setVisibility(NaruVisibility visibility);

    NaruAgent agent();

    boolean hasMoreStatements();

    NPath projectDir();

    NaruSession terminate();

    void log(NaruLogMode mode, NMsg s);

    /**
     * Reports a fragment of output that is still being produced, for a user who is
     * meant to watch it arrive. Fragments of one mode join in order; the last call
     * for a mode passes {@code end=true}.
     *
     * <p>The default reports through {@link #log}, so a session that has no notion of
     * streaming still shows every fragment -- as whole messages, and in the same text.
     * Only the output is at stake here, never the work: this is a side channel, and a
     * consumer that cannot draw incrementally must not lose the text.
     */
    default void logStream(NaruLogMode mode, NMsg fragment, boolean end) {
        log(mode, fragment);
    }

    NPath workingDir();

    NaruSession setWorkingDir(NPath workingDir);

//    NaruSession load(NElement element);

//    NaruSession load(NPath path);

    NElement toElement();

    String uuid();

    /**
     * Reloads this session's state from the store it was last written to, discarding
     * anything in memory that is not there.
     *
     * <p>This is not a version restore: it reloads the current state, exactly as
     * {@code /session reload} does. To go back to an earlier state, use
     * {@code /session restore <version>}.
     */
    NaruSession restoreFromStore();

    NaruSession load(String otherUuid);

//    NaruSession reload();

    NaruSession save();

    /**
     * Writes this session's state to the store, without waiting for the write to finish.
     *
     * <p>The fire-and-forget counterpart of {@link #save()}. Called after every statement of
     * every turn, so its cost has to be proportional to what changed -- which is why the
     * store writes history one message at a time rather than rewriting the conversation.
     */
    NaruSession persist();

    NaruSession copy();

    NaruSession reset(boolean preserveIdentity);

    Instant creationInstant();

    Instant modificationInstant();

    String name();

    NaruSession setName(String name);

    /**
     * The catalog of sessions <b>saved to disk</b> in this project.
     * <p>
     * Distinct from {@link NaruAgent#sessions()}, which is the set of sessions currently
     * <i>running in this JVM</i>. Nothing here is live: these are directories under
     * {@code .naru/sessions/} that can be listed, restored, purged or deleted, and they
     * are not required to have ever been started in this process.
     */
    NaruSessionStoreManager sessionStoreManager();

    NaruRegistry registry();

    NOptional<NaruModelConfig> findModel(String modelNameOrId);

    /**
     * Snapshot of the models displayed by the last {@code /model} listing, in display
     * order. Positional indexes ({@code /model use <n>}) are resolved against this
     * persisted state first, so that a filtered listing such as {@code /model --free} or
     * {@code /model --provider=x} keeps its indexes valid even though filtering
     * renumbers the rows.
     *
     * @return last displayed models, or an empty list if no listing happened yet.
     */
    List<NaruModelKey> listedModels();

    /**
     * Records the models displayed by a {@code /model} listing so subsequent
     * {@code /model use <n>} calls resolve indexes against the same rows.
     */
    NaruSession setListedModels(List<NaruModelKey> models);

    /**
     * Every registration in this project, by id: the merged view of the two
     * visibility files (private fields overlay public ones).
     *
     * <p>Values carry literal credentials unmasked — anything that prints them
     * must go through {@link net.thevpc.naru.api.model.NaruModelRegistration#masked()}.
     */
    Map<String, NaruModelRegistration> registrations();

    /**
     * Creates or replaces a registration. Its fields are re-split across the two
     * visibility files as they are written: literal secrets private, everything
     * else (including {@code $NAME} references) public.
     */
    void putRegistration(NaruModelRegistration registration);

    /**
     * Deletes a registration from both visibility files.
     *
     * @return true when it was present
     */
    boolean removeRegistration(String id);


    NOptional<NElement> getProjectEnv(String key);

    /**
     * The config value for a key from one specific visibility, or empty.
     *
     * <p>{@link #getProjectEnv(String)} reports the value but not which file supplied
     * it, and "which file" is what a person needs when an edit looks like it did
     * nothing -- a private value silently shadows the public one they just wrote.
     */
    NOptional<NElement> getProjectEnv(String key, NaruVisibility visibility);

    void setProjectEnv(String key, NElement value, NaruVisibility visibility);

    NOptional<Object> getSessionEnv(String key);

    NaruSession unsetSessionEnv(String key);

    NaruSession setSessionEnv(String key, Object value);

    List<NaruTask> tasks();

    NaruTask newTask(NaruTaskSpec taskBuilder);

    /**
     * Create a task from {@code spec}, start it, and return it.
     * <p>
     * This is the main entry point for embedding. The returned task is its own handle: wait on
     * it, compose it as a future, or give it a completion callback, without the caller having
     * to track the task id or re-look it up. The latter matters because a task is deregistered
     * from the session as soon as it terminates, and stays answerable on its own afterwards.
     * <p>
     * The session must already be running; call {@link #start()} first, or use {@link #run()}
     * to do both in one step. A session stops itself when its last task ends, so a session
     * meant to serve several requests needs one long-lived task holding it open.
     *
     * @param spec what to run, must not be null
     * @return the new task, already started
     * @throws IllegalStateException if the session is not running
     */
    NaruTask run(NaruTaskSpec spec);

    /**
     * Start the session if it is not already running, then run the task it was configured
     * with, and return that task.
     * <p>
     * The one-liner for the common case, where a session exists to run exactly one task:
     * <pre>{@code
     * NaruTask t = agent.newSession()
     *         .task(NaruTaskSpec.of().statements("/return 1+1"))
     *         .build()
     *         .run();
     * t.await();
     * }</pre>
     * Calling it on a session configured without a task, or calling it twice, throws: the
     * configured task is one specific invocation, and silently running again would be a
     * surprising way to lose a result.
     *
     * @return the configured task, already started
     * @throws IllegalStateException if the session has no task configured, or already ran it
     */
    NaruTask run();

    NOptional<NaruTask> findTask(long tid);

    long foregroundTaskId();

    NaruSession foregroundTaskId(long taskId);

    boolean isRunning();

    NaruSession start();

    NaruSession stop();

    NaruSession waitFor();

    String systemPrompt();

    NaruSession systemPrompt(String systemPrompt);

    long[] findTaskIdsByParent(long taskId);

    NaruSessionEventLog eventLog();

    void addSessionListener(NaruSessionListener listener);

    void removeSessionListener(NaruSessionListener listener);

    /**
     * Hands a line of user input to the session, from any thread.
     * <p>
     * This is how a host answers a {@link NaruInputRequest}. A terminal hands over what
     * the readline thread read; a web front end hands over what the browser posted,
     * possibly long after the question was asked and from a different process. The line
     * goes to the foreground task if one is blocked on input, and is treated as a session
     * command otherwise.
     */
    void deliverInput(String line);

    // ── usage reporting ──────────────────────────────────────────────────────
    // The core announces provider-reported numbers; what they mean is up to a listener.

    /**
     * Registers a listener for model-call usage and provider rate limits. Notifications are
     * delivered on the thread that made the call, so a listener must not block.
     */
    void addUsageListener(NaruSessionUsageListener listener);

    void removeUsageListener(NaruSessionUsageListener listener);

    /**
     * Announces a provider's self-reported rate limits to every
     * {@link NaruSessionUsageListener}. Called by model protocols that receive limit
     * headers; providers that send none never call it. Observers that throw are ignored.
     */
    void reportProviderRateLimits(NaruProviderRateLimitInfo info);

    List<NaruResourceInfo> routines();

    NOptional<NaruRoutine> routine(String nameOrPath, NaruTask task, boolean orCreate);

    Map<String, Object> getSessionEnv();
}
