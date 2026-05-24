package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.budget.NaruMeteringService;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.routine.NaruRoutineManager;
import net.thevpc.naru.api.routine.RunContext;
import net.thevpc.naru.api.skills.NaruSkill;
import net.thevpc.naru.api.skills.NaruSkillManager;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface NaruSession {
    boolean isPublicSession();
    String inputBuffer();
    NaruSession inputBuffer(String buffer);
    NAruInputMode inputMode();
    NaruSession inputMode(NAruInputMode newMode);

    NaruSession publicSession(boolean publicSession);

    NaruAgent agent();

    NaruStatement popStatement();

    NaruSession pushContext();

    NaruSession pushContext(int pc, Integer returnTo);

    NaruSession popContext();

    NaruSession pushStatements(NaruStatement... any);

    NaruSession pushStatement(NaruStatement any);

    int userQueriesCount();

    boolean isRequireUserInput();

    NaruSession setRequireUserInput(boolean requireUserInput);

    boolean addHistory(String m);

    void advancePcOrEnd();

    void addHistory(NaruMessage assistantMsg);

    void setLastResult(NaruMessage lastResult);

    boolean hasMoreStatements();

    boolean removeHistoryAt(int index);

    List<NaruMessage> history();

    List<NaruMessage> history(boolean includeSystem);

    List<NaruMessage> context(NaruSource... sources);

    int pc();

    NaruSession pc(int nextPc);

    NPath resolve(String path);

    NPath projectDir();

    NaruRoutineManager routineManager();

    NaruSkillManager skillManager();

    NaruSession terminate();

    String getExtraContext();

    void log(NaruLogMode mode, NMsg s);

    NPath workingDir();

    NaruSession setWorkingDir(NPath workingDir);

    int clearHistory();

    int trimHistory(int count);

    NaruModelConfig model();

    NaruSession setModel(NaruModelConfig model);

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

    NaruResponse chat(NaruModelConfig modelKey, NaruModelRequest request);

    NaruMeteringService meteringService();

    RunContext getTopContext();

    NOptional<NElement> getProjectEnv(String key);

    void setProjectEnv(String key, NElement value, NAruVisibility visibility);

    void setSessionEnv(String key, Object value);

    void throwError(NMsg nMsg);

    Object evalExpression(String condition);

    NaruSession pushStatementModelCall(String prompt);

    NaruSession prepareProject();

    NaruSkill findSkill(String name);

    boolean loadSkill(String name);

    boolean unloadSkill(String name);

    List<NaruResourceInfo> listSkills();

    NaruSession run();

    NaruSession runOrReadline();

    NaruSession runStep();
}
