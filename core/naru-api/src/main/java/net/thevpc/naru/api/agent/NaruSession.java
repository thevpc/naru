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
    NAruVisibility getVisibility();

    NaruSession throttleDelay(long ms);

    NaruScheduler scheduler();

    NaruSession setVisibility(NAruVisibility visibility);

    NaruAgent agent();

    boolean hasMoreStatements();

    NPath projectDir();

    NaruSession terminate();

    void log(NaruLogMode mode, NMsg s);

    NPath workingDir();

    NaruSession setWorkingDir(NPath workingDir);

//    NaruSession load(NElement element);

//    NaruSession load(NPath path);

    NElement toElement();

    String uuid();

    NaruSession restoreSnapshot();

    NaruSession load(String otherUuid);

    NaruSession reload();

    NaruSession save();

    NaruSession saveSnapshot();

    NaruSession copy();

    NaruSession reset(boolean preserveIdentity);

    void removeModelAlias(String alias);

    void addModelAlias(String alias, NaruModelConfig model);

    NOptional<NaruModelConfig> findModelAlias(String alias);

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
     * snapshot first, so that a filtered listing such as {@code /model --free} or
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

    Map<String, NaruModelConfig> modelAliases();

    Map<NaruModelConfig, List<String>> reversedModelAliases();


    NOptional<NElement> getProjectEnv(String key);

    void setProjectEnv(String key, NElement value, NAruVisibility visibility);

    NOptional<Object> getSessionEnv(String key);

    NaruSession unsetSessionEnv(String key);

    NaruSession setSessionEnv(String key, Object value);

    List<NaruTask> tasks();

    NaruTask newTask(NaruTaskSpec taskBuilder);

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

    NOptional<NaruModelConfig> loadModelConfig(String modelName);
    void saveModelConfig(String modelName,NaruModelConfig config);
}
