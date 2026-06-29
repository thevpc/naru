package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.budget.NaruMeteringService;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.scheduler.NaruScheduler;
import net.thevpc.naru.api.scheduler.NaruSessionEventLog;
import net.thevpc.naru.api.skills.NaruSkillManager;
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

    NaruSkillManager skillManager();

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

    NaruSessionManager sessionManager();

    NaruRegistry registry();

    NOptional<NaruModelConfig> findModel(String modelNameOrId);

    Map<String, NaruModelConfig> modelAliases();

    Map<NaruModelConfig, List<String>> reversedModelAliases();

    NaruMeteringService meteringService();

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

    void start();

    void stop();

    void waitFor();

    String systemPrompt();

    NaruSession systemPrompt(String systemPrompt);

    long[] findTaskIdsByParent(long taskId);

    NaruSessionEventLog eventLog();

    void addSessionListener(NaruSessionListener listener);

    void removeSessionListener(NaruSessionListener listener);

    List<NaruResourceInfo> routines();

    NOptional<NaruRoutine> routine(String nameOrPath, NaruTask task, boolean orCreate);

    Map<String, Object> getSessionEnv();
}
