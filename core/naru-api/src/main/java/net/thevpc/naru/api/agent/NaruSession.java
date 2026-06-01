package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.budget.NaruMeteringService;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.routine.NaruRoutineManager;
import net.thevpc.naru.api.scheduler.NaruScheduler;
import net.thevpc.naru.api.skills.NaruSkillManager;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface NaruSession {
    NAruVisibility getVisibility();

    NaruScheduler scheduler();

    NaruSession setVisibility(NAruVisibility visibility);

    NaruAgent agent();

    boolean hasMoreStatements();

    NPath projectDir();

    NaruRoutineManager routineManager();

    NaruSkillManager skillManager();

    NaruSession terminate();

    void log(NaruLogMode mode, NMsg s);

    NPath workingDir();

    NaruSession setWorkingDir(NPath workingDir);

    NaruSession load(NElement element);

    NaruSession load(NPath path);

    NElement toElement();

    String uuid();

    NaruSession load();

    NaruSession save();

    NaruSession save(NPath path);

    NaruSession copy();

    NaruSession reset(boolean preserveIdentity);

    void removeModelAlias(String alias);

    void addModelAlias(String alias, NaruModelConfig model);

    NOptional<NaruModelConfig> findModelAlias(String alias);

    Instant creationDate();

    Instant modificationDate();

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

    NOptional<Object> getSessionProperty(String key);

    NaruSession unsetSessionProperty(String key);

    NaruSession setSessionProperty(String key, Object value);

    List<NaruTask> tasks();

    NaruTask newTask(long parentId, NPath cwd, String... statements);

    NOptional<NaruTask> findTask(long tid);

    long foregroundTaskId();

    NaruSession foregroundTaskId(long taskId);

    void start();

    void stop();

    void waitFor();
}
