package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.budget.NaruMeteringService;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruToolDefinition;
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
    boolean isForever();

    boolean isPublicSession();

    NaruSession setPublicSession(boolean publicSession);

    NaruSession setForever(boolean forever);

    NaruAgent runner();

    NaruStatement popStatement();

    NaruSession pushContext();

    NaruSession pushContext(int pc, Integer returnTo);

    NaruSession popContext();

    NaruSession pushStatements(NaruStatement... any);

    NaruSession pushStatement(NaruStatement any);

    NaruSession pushStatementReadlineForever();

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

    NaruModelKey model();

    NaruSession setModel(NaruModelKey model);

    NaruSession load(NElement element);

    NaruSession load(NPath path);

    NElement toElement();

    String uuid();

    NaruSession load();

    NaruSession save();

    NaruSession save(NPath path);

    NaruSession copy();

    NaruSession reset();

    void removeModelAlias(String alias);

    void addModelAlias(String alias, NaruModelKey model);

    NOptional<NaruModelKey> findModelAlias(String alias);

    Instant creationDate();

    Instant modificationDate();

    String name();

    NaruSession setName(String name);

    NaruSessionManager sessionManager();

    NaruRegistry registry();

    NOptional<NaruModelKey> findModel(String modelNameOrId);

    Map<String, NaruModelKey> modelAliases();

    Map<NaruModelKey, List<String>> reversedModelAliases();

    NaruResponse chat(NaruModelKey modelKey, List<NaruMessage> messages, List<NaruToolDefinition> tools);

    NaruMeteringService meteringService();

    RunContext getTopContext();

    void setProjectEnv(String key, String value);
    void setSessionEnv(String key, Object value);

    void throwError(NMsg nMsg);

    Object evalExpression(String condition);

    NaruSession pushStatementModelCall();

    NaruSession prepareWorkdir();

    NaruSkill findSkill(String name);
    boolean loadSkill(String name);

    boolean unloadSkill(String name);

    List<NaruResourceInfo> listSkills();

    NaruSession runStep();
}
