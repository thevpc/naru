package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;

import java.util.List;

public interface NaruSessionContext {
    boolean isForever();

    NaruSessionContext setForever(boolean forever);

    NaruAgent runner();

    NaruAgentOp popOperation();

    NaruSessionContext pushContext();

    NaruSessionContext pushOperation(NaruAgentOp any);

    int userQueriesCount();

    boolean isRequireUserInput();

    NaruSessionContext setRequireUserInput(boolean requireUserInput);

    boolean addHistory(String m);

    void addHistory(NaruMessage assistantMsg);

    void setLastResult(NaruMessage lastResult);

    boolean hasMoreOps();

    NaruAgentContext agentContext();

    boolean removeHistoryAt(int index);

    List<NaruMessage> history();

    int pc();

    NaruSessionContext pc(int nextPc);

    NPath resolve(String path);

    NPath projectDir();

    NaruScriptManager scriptManager();

    NaruSessionContext terminate();

    void log(NaruLogMode mode, NMsg s);

    NPath workingDir();

    NaruSessionContext setWorkingDir(NPath workingDir);

    int clearHistory();

    int trimHistory(int count);
}
