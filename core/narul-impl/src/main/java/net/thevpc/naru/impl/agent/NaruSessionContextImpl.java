package net.thevpc.naru.impl.agent;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.List;

public class NaruSessionContextImpl implements NaruSessionContext {
    private final NaruAgentContext agentContext;
    private final List<NaruMessage> history = new ArrayList<>();
    private int userQueries;
    private NaruMessage lastResult;
    private boolean requireUserInput;
    private boolean forever;
    private final List<RunContext> todo = new ArrayList<>();
    private final NaruAgent runner;
    private NPath workingDir;

    private static class RunContext {
        private int pc = -1; // -1 means not currently executing a script
        List<NaruAgentOp> todo = new ArrayList<>();
    }

    public NaruSessionContextImpl(NaruAgentContext agentContext, NaruAgent runner) {
        this.agentContext = agentContext;
        this.runner = runner;
        this.workingDir = agentContext.getProjectDir();
    }

    @Override
    public void log(NaruLogMode mode, NMsg s) {
        runner.log(mode, s);
    }

    @Override
    public List<NaruMessage> history() {
        return history;
    }

    @Override
    public boolean removeHistoryAt(int index) {
        if (index >= 0 && index < history.size()) {
            history.remove(index);
            return true;
        }
        return false;
    }

    public int pc() {
        if (todo.isEmpty()) {
            return -1;
        }
        return todo.get(0).pc;
    }

    @Override
    public NaruSessionContext pc(int nextPc) {
        if (!todo.isEmpty()) {
            todo.get(0).pc = nextPc;
        }
        return this;
    }

    @Override
    public NPath resolve(String path) {
        if (NBlankable.isBlank(path)) return workingDir;
        NPath p = NPath.of(path);
        return p.isAbsolute() ? p : workingDir.resolve(p).normalize();
    }

    public NPath workingDir() {
        return workingDir;
    }

    public NaruSessionContext setWorkingDir(NPath workingDir) {
        this.workingDir = workingDir.toAbsolute(this.workingDir);
        return this;
    }

    @Override
    public int clearHistory() {
        int count = history.size();
        history.clear();
        return count;
    }

    @Override
    public int trimHistory(int count) {
        if (count > 0) {
            if (count >= history.size()) {
                return clearHistory();
            } else {
                history.subList(0, history.size() - count).clear();
                return count;
            }
        }
        return 0;
    }

    @Override
    public NPath projectDir() {
        return agentContext().getProjectDir();
    }

    @Override
    public NaruScriptManager scriptManager() {
        return agentContext().getScriptManager();
    }

    @Override
    public NaruSessionContext terminate() {
        todo.clear();
        return this;
    }

    public NaruAgentContext agentContext() {
        return agentContext;
    }

    public boolean hasMoreOps() {
        return !todo.isEmpty();
    }

    @Override
    public boolean isForever() {
        return forever;
    }

    @Override
    public NaruSessionContext setForever(boolean forever) {
        this.forever = forever;
        return this;
    }

    @Override
    public NaruAgent runner() {
        return runner;
    }

    @Override
    public NaruAgentOp popOperation() {
        if (todo.isEmpty()) {
            return null;
        }
        NaruAgentOp result = null;
        normalizeTodo();
        if (!todo.isEmpty()) {
            List<NaruAgentOp> a = todo.get(0).todo;
            result = a.remove(0);
            normalizeTodo();
        }
        return result;
    }

    @Override
    public NaruSessionContext pushContext() {
        todo.add(0, new RunContext());
        return this;
    }

    private void normalizeTodo() {
        while (!todo.isEmpty()) {
            List<NaruAgentOp> a = todo.get(0).todo;
            if (a.isEmpty()) {
                todo.remove(0);
            } else {
                return;
            }
        }
    }

    @Override
    public NaruSessionContext pushOperation(NaruAgentOp any) {
        if (todo.isEmpty()) {
            todo.add(0, new RunContext());
        }
        todo.get(0).todo.add(0, any);
        return this;
    }


    @Override
    public int userQueriesCount() {
        return userQueries;
    }

    @Override
    public boolean isRequireUserInput() {
        return requireUserInput;
    }

    @Override
    public NaruSessionContext setRequireUserInput(boolean requireUserInput) {
        this.requireUserInput = requireUserInput;
        return this;
    }

    @Override
    public boolean addHistory(String m) {
        if (!NBlankable.isBlank(m)) {
            addHistory(NaruMessage.user(m));
            return true;
        }
        return false;
    }

    @Override
    public void addHistory(NaruMessage assistantMsg) {
        if (assistantMsg.getRole().equals("user")) {
            userQueries++;
        }
        history.add(assistantMsg);
    }

    @Override
    public void setLastResult(NaruMessage lastResult) {
        this.lastResult = lastResult;
    }
}
