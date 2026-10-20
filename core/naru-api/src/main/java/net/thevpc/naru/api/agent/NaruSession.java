package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.access.NaruAccessPolicy;
import net.thevpc.naru.api.access.NaruActor;
import net.thevpc.naru.api.access.NaruPrincipal;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.scheduler.NaruScheduler;
import net.thevpc.naru.api.scheduler.NaruSessionEventLog;
import net.thevpc.naru.api.spawn.NaruSpawnPolicy;
import net.thevpc.naru.api.spawn.NaruSpawnResolution;
import net.thevpc.naru.api.store.NaruSessionVersionStore;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.api.registry.NaruLineCompletion;
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

    NaruWorkspace agent();

    /**
     * The principal that owns this session — the user (or service identity) on
     * whose behalf every task in it runs. Never null.
     *
     * <p>It is the default owner of a spawned agent and the subject most rules
     * are written against; a task that was created without an explicit actor
     * inherits this one.
     */
    NaruPrincipal user();

    /** {@link #user()} as an acting principal (kind {@code USER}). Never null. */
    NaruActor userActor();

    /**
     * The transversal access policy for this session: one ordered rule set,
     * consulted by every domain (files, urls, tools, directives, shell).
     *
     * <p>It starts empty with a default effect of {@code ALLOW}, so nothing about
     * behaviour changes until a rule is written.
     */
    NaruAccessPolicy accessPolicy();

    boolean hasMoreStatements();

    NPath projectDir();

    /**
     * Changes the project root and re-resolves everything that depends on it (WP7):
     * skill roots, context files, model defaults and init hooks. Per-task selections and
     * tag sets are kept; only availability is re-resolved, and a feature's doctor reports
     * what disappeared. Fires the {@code project-change} event.
     * <p>
     * Storage is not migrated: a session keeps writing to the store it was created with
     * (O9). {@code /project} is a navigation change, not a move of the session.
     */
    NaruSession projectDir(NPath projectDir);

    /**
     * Appends an explicitly named event to the session event log, with no task as its
     * source. Used for session-scoped hooks such as {@code session-start} and
     * {@code project-change}; task-scoped events go through
     * {@link NaruTask#fireEvent(String, java.util.Map, net.thevpc.naru.api.scheduler.NaruEventTarget, net.thevpc.naru.api.scheduler.NaruRetentionPolicy)}.
     */
    NaruSession fireEvent(String eventName, java.util.Map<String, Object> payload);

    NaruSession terminate();

    void log(NaruLogMode mode, NMsg s);

    /**
     * Reports output together with the task that produced it. The default drops the id and
     * calls {@link #log(NaruLogMode, NMsg)}, so a session that has no per-task attribution is
     * still correct; {@code taskId} of {@code 0} means the session itself is the source.
     */
    default void log(NaruLogMode mode, NMsg s, long taskId) {
        log(mode, s);
    }

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

    /**
     * Streaming counterpart of {@link #log(NaruLogMode, NMsg, long)}: the fragment is attributed
     * to the task producing it. The default drops the id, so a session that streams without
     * attribution keeps behaving exactly as before.
     */
    default void logStream(NaruLogMode mode, NMsg fragment, boolean end, long taskId) {
        logStream(mode, fragment, end);
    }

    /**
     * Routes a typed tool-call lifecycle report to whoever is watching the session.
     *
     * <p>The default does nothing, so a session with no host for these events simply loses
     * them — the {@code PROGRESS} line the statement logs is unaffected. Implementations that
     * have an interaction forward it there. Nothing about the tool call itself depends on this:
     * it is a report of what already happened, and a consumer that throws is ignored.
     */
    default void reportToolCall(NaruToolCallEvent event) {
    }

    NPath workingDir();

    NaruSession workingDir(NPath workingDir);

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

    NaruSession name(String name);

    /**
     * The catalog of sessions <b>saved to disk</b> in this project.
     * <p>
     * Distinct from {@link NaruWorkspace#sessions()}, which is the set of sessions currently
     * <i>running in this JVM</i>. Nothing here is live: these are directories under
     * {@code .naru/sessions/} that can be listed, restored, purged or deleted, and they
     * are not required to have ever been started in this process.
     */
    NaruSessionStoreManager sessionStoreManager();

    /**
     * This session's committed versions: list, label, diff, restore.
     * <p>
     * Scoped to this session on purpose. The underlying {@code NaruSessionStore} takes a
     * {@code (uuid, scope)} pair on every call, which makes it a handle on <em>every</em> saved
     * session in the project — including writes to ones that are not this one. A host that wants
     * the whole catalog asks {@link #sessionStoreManager()}; a host that wants this session's
     * history asks here, and cannot reach a neighbour by accident.
     */
    NaruSessionVersionStore versionStore();

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

    /**
     * Session env value, optionally walking the wider chain when the session env
     * does not hold the key.
     *
     * <p>The stores are consulted in the resolution order of the settings chain:
     * session env first, then the per-process agent env ({@code /set --agent}, shared
     * by every session but not persisted), then the on-disk project settings
     * ({@code .naru/.../env.tson}, private winning). {@code inherited=false} keeps the
     * lookup on the session env alone, exactly like {@link #getSessionEnv(String)}.
     *
     * <p>This is the narrow-to-wide half of the chain a task lookup completes:
     * {@link net.thevpc.naru.api.task.NaruTask#getTaskEnv(String, boolean)} with
     * {@code inherited=true} delegates here after exhausting the task env, so a tool
     * that reads a setting once through the task can be overridden at any level.
     */
    default NOptional<Object> getSessionEnv(String key, boolean inherited) {
        NOptional<Object> sessionValue = getSessionEnv(key);
        if (sessionValue.isPresent()) {
            return sessionValue;
        }
        if (inherited) {
            NaruWorkspace a = agent();
            if (a != null) {
                NaruEnv agentStore = a.agentEnv();
                if (agentStore != null) {
                    NOptional<NElement> agentValue = agentStore.get(key);
                    if (agentValue != null && agentValue.isPresent()) {
                        return NOptional.ofNullable(agentValue.get());
                    }
                }
                NaruEnv settings = a.env();
                if (settings != null) {
                    NOptional<NElement> settingsValue = settings.get(key);
                    if (settingsValue != null && settingsValue.isPresent()) {
                        return NOptional.ofNullable(settingsValue.get());
                    }
                }
            }
        }
        return NOptional.ofNamedEmpty(key);
    }

    NaruSession unsetSessionEnv(String key);

    NaruSession setSessionEnv(String key, Object value);

    List<NaruTask> tasks();

    /** Live tasks and retained terminated conversation tasks in this activation. */
    List<NaruTask> conversationTasks();

    /**
     * Reactivates a retained conversation zombie in this live session, preserving its task id and history.
     * The expected version is the task.version() observed by the caller.
     */
    NaruTask reactivateTask(long taskId, long expectedVersion);

    NaruTask newTask(NaruTaskSpec taskBuilder);

    /**
     * Defines a named spawn policy for this session. Policies are session-scoped and are
     * re-declared by the directive that defines them (typically run from an init script),
     * so they are deliberately not persisted with the session state. Redefining a name
     * replaces it.
     */
    void defineSpawnPolicy(NaruSpawnPolicy policy);

    /** The named spawn policy, or empty when none was defined for that name. */
    NOptional<NaruSpawnPolicy> findSpawnPolicy(String name);

    /** The defined spawn policies, name-indexed. */
    Map<String, NaruSpawnPolicy> spawnPolicies();

    /**
     * Resolves a spawn exactly as {@link #newTask(NaruTaskSpec)} would — policy defaults,
     * call-site flags, contract validation and provenance — but without creating a task.
     * This is what {@code /start --explain} uses to print the resolved policy and
     * provenance without spawning.
     * <p>
     * Validation is applied just like a real spawn: an unsatisfied contract still throws,
     * so an explain run reports exactly what the real spawn would reject, alongside the
     * resolved sets and their sources.
     */
    NaruSpawnResolution resolveSpawn(NaruTaskSpec taskBuilder);

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

    /**
     * Complete {@code rawLine} with the caret at {@code caret}, from plain text, with no terminal
     * and no parsed command line.
     * <p>
     * Delegates to {@link NaruLineCompletion#complete(NaruSession, String, int)}, which stays the
     * entry point for a caller that has a session object but not this one — a completion preview for
     * a project that is not open yet, or a unit test that wants the pure function. Both shapes exist
     * so neither has to reach into the other.
     * <p>
     * It never mutates the session, never touches terminal state, and never blocks longer than one
     * directory listing, which is what lets a Swing host call it on every keystroke.
     */
    default NaruLineCompletion.Result complete(String rawLine, int caret) {
        return NaruLineCompletion.complete(this, rawLine, caret);
    }

    // ── who is asking, and what is still owed ────────────────────────────────
    // A terminal has one foreground and cannot be wrong about it. A host with many tasks on screen
    // needs the engine's answer, not its own guess.

    /**
     * Every input the session is currently waiting on, oldest task first.
     * <p>
     * A terminal can only ever have one — its own foreground. A desktop or web host can have
     * several tasks parked at once, and needs to know which is which before it answers any of
     * them; {@link #deliverInput(String)} cannot express "this one".
     */
    List<NaruPendingInput> pendingInputs();

    /**
     * Answer one parked request by id.
     *
     * @return false when nothing is parked under that id — it was already answered, or the task
     *         moved on, or it belongs to a session the caller is not looking at
     */
    boolean deliverPendingInput(String requestId, String text);

    /**
     * Answer one parked request, recording {@code idempotencyKey} so a retry of the same delivery
     * is recognised rather than applied twice. A browser that posts an answer and loses the
     * response has no way to tell a failed request from a slow one; this is it.
     */
    boolean deliverPendingInput(String requestId, String text, String idempotencyKey);

    /**
     * True when the session can be hibernated: it has tasks, every one of them is parked on input,
     * and nothing pinned by a toolset or a tool is still open.
     * <p>
     * A host must ask this rather than try {@code hibernate()} and catch, because the check is not
     * about this session's own state — it is about live resources other owners hold.
     */
    boolean isHibernateEligible();

    /**
     * Save a quiescent session and release its live resources while preserving input-blocked
     * tasks for a later activation. Call only when {@link #isHibernateEligible()} is true.
     */
    void hibernate();

    /**
     * True when the session still has tasks, whether or not any of them can make progress. This is
     * not "is anything running": a session with three parked tasks has live tasks and no work.
     */
    boolean hasLiveTasks();

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

    Map<String, Object> sessionEnv();
}
